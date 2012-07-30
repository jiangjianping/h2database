/*
 * Copyright 2004-2008 H2 Group. Licensed under the H2 License, Version 1.0
 * (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.log;

import java.sql.SQLException;
import java.util.Comparator;
import java.util.HashMap;

import org.h2.api.DatabaseEventListener;
import org.h2.engine.Constants;
import org.h2.engine.Database;
import org.h2.engine.Session;
import org.h2.message.Trace;
import org.h2.store.DataPage;
import org.h2.store.DiskFile;
import org.h2.store.Record;
import org.h2.store.Storage;
import org.h2.util.FileUtils;
import org.h2.util.ObjectArray;
import org.h2.util.ObjectUtils;

/**
 * The transaction log system is responsible for the write ahead log mechanism
 * used in this database. A number of {@link LogFile} objects are used (one for
 * each file).
 */
public class LogSystem {

    public static final int LOG_WRITTEN = -1;

    private Database database;
    private ObjectArray activeLogs;
    private LogFile currentLog;
    private String fileNamePrefix;
    private HashMap storages = new HashMap();
    private HashMap sessions = new HashMap();
    private DataPage rowBuff;
    private ObjectArray undo;
    // TODO log file / deleteOldLogFilesAutomatically: 
    // make this a setting, so they can be backed up
    private boolean deleteOldLogFilesAutomatically = true;
    private long maxLogSize = Constants.DEFAULT_MAX_LOG_SIZE;
    private boolean readOnly;
    private boolean flushOnEachCommit;
    private ObjectArray inDoubtTransactions;
    private boolean disabled;
    private int keepFiles;
    private boolean closed;
    private String accessMode;

    public LogSystem(Database database, String fileNamePrefix, boolean readOnly, String accessMode) throws SQLException {
        this.database = database;
        this.readOnly = readOnly;
        this.accessMode = accessMode;
        closed = true;
        if (database == null) {
            return;
        }
        this.fileNamePrefix = fileNamePrefix;
        rowBuff = DataPage.create(database, Constants.DEFAULT_DATA_PAGE_SIZE);
    }

    public void setMaxLogSize(long maxSize) {
        this.maxLogSize = maxSize;
    }

    public long getMaxLogSize() {
        return maxLogSize;
    }

    public boolean containsInDoubtTransactions() {
        return inDoubtTransactions != null && inDoubtTransactions.size() > 0;
    }

    private void flushAndCloseUnused() throws SQLException {
        currentLog.flush();
        DiskFile file = database.getDataFile();
        if (file == null) {
            return;
        }
        file.flush();
        if (containsInDoubtTransactions()) {
            // if there are any in-doubt transactions 
            // (even if they are resolved), can't update or delete the log files
            return;
        }
        Session[] sessions = database.getSessions();
        int firstUncommittedLog = currentLog.getId();
        int firstUncommittedPos = currentLog.getPos();
        for (int i = 0; i < sessions.length; i++) {
            Session session = sessions[i];
            int log = session.getFirstUncommittedLog();
            int pos = session.getFirstUncommittedPos();
            if (pos != LOG_WRITTEN) {
                if (log < firstUncommittedLog || (log == firstUncommittedLog && pos < firstUncommittedPos)) {
                    firstUncommittedLog = log;
                    firstUncommittedPos = pos;
                }
            }
        }
        for (int i = activeLogs.size() - 1; i >= 0; i--) {
            LogFile l = (LogFile) activeLogs.get(i);
            if (l.getId() < firstUncommittedLog) {
                l.setFirstUncommittedPos(LOG_WRITTEN);
            } else if (l.getId() == firstUncommittedLog) {
                if (firstUncommittedPos == l.getPos()) {
                    l.setFirstUncommittedPos(LOG_WRITTEN);
                } else {
                    l.setFirstUncommittedPos(firstUncommittedPos);
                }
            }
        }
        for (int i = 0; i < activeLogs.size(); i++) {
            LogFile l = (LogFile) activeLogs.get(i);
            if (l.getFirstUncommittedPos() == LOG_WRITTEN) {
                // must remove the log file first
                // if we don't do that, the file is closed but still in the list
                activeLogs.remove(i);
                i--;
                closeOldFile(l);
            }
        }
    }

    public void close() throws SQLException {
        if (database == null) {
            return;
        }
        synchronized (database) {
            if (closed) {
                return;
            }
            if (readOnly) {
                for (int i = 0; i < activeLogs.size(); i++) {
                    LogFile l = (LogFile) activeLogs.get(i);
                    l.close(false);
                }
                closed = true;
                return;
            }
            // TODO refactor flushing and closing files when we know what to do exactly
            SQLException closeException = null;
            try {
                flushAndCloseUnused();
                if (!containsInDoubtTransactions()) {
                    checkpoint();
                }
            } catch (SQLException e) {
                closeException = e;
            }
            for (int i = 0; i < activeLogs.size(); i++) {
                LogFile l = (LogFile) activeLogs.get(i);
                try {
                    // if there are any in-doubt transactions 
                    // (even if they are resolved), can't delete the log files
                    if (l.getFirstUncommittedPos() == LOG_WRITTEN && !containsInDoubtTransactions()) {
                        closeOldFile(l);
                    } else {
                        l.close(false);
                    }
                } catch (SQLException e) {
                    // TODO log exception
                    if (closeException == null) {
                        closeException = e;
                    }
                }
            }
            closed = true;
            if (closeException != null) {
                throw closeException;
            }
        }
    }

    void addUndoLogRecord(LogFile log, int logRecordId, int sessionId) {
        getOrAddSessionState(sessionId);
        LogRecord record = new LogRecord(log, logRecordId, sessionId);
        undo.add(record);
    }

    public boolean recover() throws SQLException {
        if (database == null) {
            return false;
        }
        synchronized (database) {
            if (closed) {
                return false;
            }
            undo = new ObjectArray();
            for (int i = 0; i < activeLogs.size(); i++) {
                LogFile log = (LogFile) activeLogs.get(i);
                log.redoAllGoEnd();
            }
            database.getDataFile().flushRedoLog();
            database.getIndexFile().flushRedoLog();
            int end = currentLog.getPos();
            Object[] states = sessions.values().toArray();
            inDoubtTransactions = new ObjectArray();
            for (int i = 0; i < states.length; i++) {
                SessionState state = (SessionState) states[i];
                if (state.inDoubtTransaction != null) {
                    inDoubtTransactions.add(state.inDoubtTransaction);
                }
            }
            for (int i = undo.size() - 1; i >= 0 && sessions.size() > 0; i--) {
                database.setProgress(DatabaseEventListener.STATE_RECOVER, null, undo.size() - 1 - i, undo.size());
                LogRecord record = (LogRecord) undo.get(i);
                if (sessions.get(ObjectUtils.getInteger(record.sessionId)) != null) {
                    // undo only if the session is not yet committed
                    record.log.undo(record.logRecordId);
                    database.getDataFile().flushRedoLog();
                    database.getIndexFile().flushRedoLog();
                }
            }
            currentLog.go(end);
            boolean fileChanged = undo.size() > 0;
            undo = null;
            storages.clear();
            if (!readOnly && fileChanged && !containsInDoubtTransactions()) {
                checkpoint();
            }
            return fileChanged;
        }
    }

    private void closeOldFile(LogFile l) throws SQLException {
        l.close(deleteOldLogFilesAutomatically && keepFiles == 0);
    }

    public void open() throws SQLException {
        String path = FileUtils.getParent(fileNamePrefix);
        String[] list = FileUtils.listFiles(path);
        activeLogs = new ObjectArray();
        for (int i = 0; i < list.length; i++) {
            String s = list[i];
            LogFile l = null;
            try {
                l = LogFile.openIfLogFile(this, fileNamePrefix, s);
            } catch (SQLException e) {
                database.getTrace(Trace.LOG).debug("Error opening log file, header corrupt: "+s, e);
                // this can happen if the system crashes just 
                // after creating a new file (before writing the header)
                // rename it, so that it doesn't get in the way the next time
                FileUtils.delete(s + ".corrupt");
                FileUtils.rename(s, s + ".corrupt");
            }
            if (l != null) {
                if (l.getPos() == LOG_WRITTEN) {
                    closeOldFile(l);
                } else {
                    activeLogs.add(l);
                }
            }
        }
        activeLogs.sort(new Comparator() {
            public int compare(Object a, Object b) {
                return ((LogFile) a).getId() - ((LogFile) b).getId();
            }
        });
        if (activeLogs.size() == 0) {
            LogFile l = new LogFile(this, 0, fileNamePrefix);
            activeLogs.add(l);
        }
        currentLog = (LogFile) activeLogs.get(activeLogs.size() - 1);
        closed = false;
    }

    Storage getStorageForRecovery(int id) throws SQLException {
        boolean dataFile;
        if (id < 0) {
            dataFile = false;
            id = -id;
        } else {
            dataFile = true;
        }
        Integer i = ObjectUtils.getInteger(id);
        Storage storage = (Storage) storages.get(i);
        if (storage == null) {
            storage = database.getStorage(null, id, dataFile);
            storages.put(i, storage);
        }
        return storage;
    }

    boolean isSessionCommitted(int sessionId, int logId, int pos) {
        Integer key = ObjectUtils.getInteger(sessionId);
        SessionState state = (SessionState) sessions.get(key);
        if (state == null) {
            return true;
        }
        return state.isCommitted(logId, pos);
    }

    void setLastCommitForSession(int sessionId, int logId, int pos) {
        SessionState state = getOrAddSessionState(sessionId);
        state.lastCommitLog = logId;
        state.lastCommitPos = pos;
        state.inDoubtTransaction = null;
    }

    SessionState getOrAddSessionState(int sessionId) {
        Integer key = ObjectUtils.getInteger(sessionId);
        SessionState state = (SessionState) sessions.get(key);
        if (state == null) {
            state = new SessionState();
            sessions.put(key, state);
            state.sessionId = sessionId;
        }
        return state;
    }

    void setPreparedCommitForSession(LogFile log, int sessionId, int pos, String transaction, int blocks) {
        SessionState state = getOrAddSessionState(sessionId);
        // this is potentially a commit, so 
        // don't roll back the action before it (currently)
        setLastCommitForSession(sessionId, log.getId(), pos);
        state.inDoubtTransaction = new InDoubtTransaction(log, sessionId, pos, transaction, blocks);
    }

    public ObjectArray getInDoubtTransactions() {
        return inDoubtTransactions;
    }

    void removeSession(int sessionId) {
        sessions.remove(ObjectUtils.getInteger(sessionId));
    }

    public void prepareCommit(Session session, String transaction) throws SQLException {
        if (database == null || readOnly) {
            return;
        }
        synchronized (database) {
            if (closed) {
                return;
            }
            currentLog.prepareCommit(session, transaction);
        }
    }

    public void commit(Session session) throws SQLException {
        if (database == null || readOnly) {
            return;
        }
        synchronized (database) {
            if (closed) {
                return;
            }
            currentLog.commit(session);
            session.setAllCommitted();
        }
    }

    public void flush() throws SQLException {
        if (database == null || readOnly) {
            return;
        }
        synchronized (database) {
            if (closed) {
                return;
            }
            currentLog.flush();
        }
    }

    /**
     * Add a truncate entry.
     * 
     * @param session the session
     * @param file the disk file
     * @param storageId the storage id
     * @param recordId the id of the first record
     * @param blockCount the number of blocks
     */
    public void addTruncate(Session session, DiskFile file, int storageId, int recordId, int blockCount)
            throws SQLException {
        if (database == null) {
            return;
        }
        synchronized (database) {
            if (disabled || closed) {
                return;
            }
            database.checkWritingAllowed();
            if (!file.isDataFile()) {
                storageId = -storageId;
            }
            currentLog.addTruncate(session, storageId, recordId, blockCount);
            if (currentLog.getFileSize() > maxLogSize) {
                checkpoint();
            }
        }
    }

    public void add(Session session, DiskFile file, Record record) throws SQLException {
        if (database == null) {
            return;
        }
        synchronized (database) {
            if (disabled || closed) {
                return;
            }
            database.checkWritingAllowed();
            int storageId = record.getStorageId();
            if (!file.isDataFile()) {
                storageId = -storageId;
            }
            int log = currentLog.getId();
            int pos = currentLog.getPos();
            session.addLogPos(log, pos);
            record.setLastLog(log, pos);
            currentLog.add(session, storageId, record);
            if (currentLog.getFileSize() > maxLogSize) {
                checkpoint();
            }
        }
    }

    public void checkpoint() throws SQLException {
        if (readOnly || database == null) {
            return;
        }
        synchronized (database) {
            if (closed || disabled) {
                return;
            }
            flushAndCloseUnused();
            currentLog = new LogFile(this, currentLog.getId() + 1, fileNamePrefix);
            activeLogs.add(currentLog);
            writeSummary();
            currentLog.flush();
        }
    }

    public ObjectArray getActiveLogFiles() {
        synchronized (database) {
            ObjectArray list = new ObjectArray();
            list.addAll(activeLogs);
            return list;
        }
    }

    private void writeSummary() throws SQLException {
        byte[] summary;
        DiskFile file;
        file = database.getDataFile();
        if (file == null) {
            return;
        }
        summary = file.getSummary();
        if (summary != null) {
            currentLog.addSummary(true, summary);
        }
        if (database.getLogIndexChanges() || database.getIndexSummaryValid()) {
            file = database.getIndexFile();
            summary = file.getSummary();
            if (summary != null) {
                currentLog.addSummary(false, summary);
            }
        } else {
            // invalidate the index summary
            currentLog.addSummary(false, null);
        }
    }

    Database getDatabase() {
        return database;
    }

    DataPage getRowBuffer() {
        return rowBuff;
    }

    public void setFlushOnEachCommit(boolean b) {
        flushOnEachCommit = b;
    }

    boolean getFlushOnEachCommit() {
        return flushOnEachCommit;
    }

    public void sync() throws SQLException {
        if (database == null || readOnly) {
            return;
        }
        synchronized (database) {
            if (currentLog != null) {
                currentLog.flush();
                currentLog.sync();
            }
        }
    }

    public void setDisabled(boolean disabled) {
        this.disabled = disabled;
    }

    void addRedoLog(Storage storage, int recordId, int blockCount, DataPage rec) throws SQLException {
        DiskFile file = storage.getDiskFile();
        file.addRedoLog(storage, recordId, blockCount, rec);
    }

    public void invalidateIndexSummary() throws SQLException {
        currentLog.addSummary(false, null);
    }

    public synchronized void updateKeepFiles(int incrementDecrement) {
        keepFiles += incrementDecrement;
    }

    String getAccessMode() {
        return accessMode;
    }

}