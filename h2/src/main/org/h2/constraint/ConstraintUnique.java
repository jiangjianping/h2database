/*
 * Copyright 2004-2011 H2 Group. Multiple-Licensed under the H2 License,
 * Version 1.0, and under the Eclipse Public License, Version 1.0
 * (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.constraint;

import java.util.HashSet;
import org.h2.command.Parser;
import org.h2.engine.Session;
import org.h2.index.Index;
import org.h2.result.Row;
import org.h2.schema.Schema;
import org.h2.table.Column;
import org.h2.table.IndexColumn;
import org.h2.table.Table;
import org.h2.util.New;
import org.h2.util.StatementBuilder;
import org.h2.util.StringUtils;

/**
 * A unique constraint. This object always backed by a unique index.
 */
public class ConstraintUnique extends Constraint {

    private Index index;
    private boolean indexOwner;
    private IndexColumn[] columns;
    private final boolean primaryKey;

    public ConstraintUnique(Schema schema, int id, String name, Table table, boolean primaryKey) {
        super(schema, id, name, table);
        this.primaryKey = primaryKey;
    }

    public String getConstraintType() {
        return primaryKey ? Constraint.PRIMARY_KEY : Constraint.UNIQUE;
    }

    public String getCreateSQLForCopy(Table forTable, String quotedName) {
        return getCreateSQLForCopy(forTable, quotedName, true);
    }

    private String getCreateSQLForCopy(Table forTable, String quotedName, boolean internalIndex) {
        StatementBuilder buff = new StatementBuilder("ALTER TABLE ");
        buff.append(forTable.getSQL()).append(" ADD CONSTRAINT ");
        if (forTable.isHidden()) {
            buff.append("IF NOT EXISTS ");
        }
        buff.append(quotedName);
        if (comment != null) {
            buff.append(" COMMENT ").append(StringUtils.quoteStringSQL(comment));
        }
        buff.append(' ').append(getTypeName()).append('(');
        for (IndexColumn c : columns) {
            buff.appendExceptFirst(", ");
            buff.append(Parser.quoteIdentifier(c.column.getName()));
        }
        buff.append(')');
        if (internalIndex && indexOwner && forTable == this.table) {
            buff.append(" INDEX ").append(index.getSQL());
        }
        return buff.toString();
    }

    private String getTypeName() {
        if (primaryKey) {
            return "PRIMARY KEY";
        }
        return "UNIQUE";
    }

    public String getCreateSQLWithoutIndexes() {
        return getCreateSQLForCopy(table, getSQL(), false);
    }

    public String getCreateSQL() {
        return getCreateSQLForCopy(table, getSQL());
    }

    public void setColumns(IndexColumn[] columns) {
        this.columns = columns;
    }

    public IndexColumn[] getColumns() {
        return columns;
    }

    /**
     * Set the index to use for this unique constraint.
     *
     * @param index the index
     * @param isOwner true if the index is generated by the system and belongs
     *            to this constraint
     */
    public void setIndex(Index index, boolean isOwner) {
        this.index = index;
        this.indexOwner = isOwner;
    }

    public void removeChildrenAndResources(Session session) {
        table.removeConstraint(this);
        if (indexOwner) {
            table.removeIndexOrTransferOwnership(session, index);
        }
        database.removeMeta(session, getId());
        index = null;
        columns = null;
        table = null;
        invalidate();
    }

    public void checkRow(Session session, Table t, Row oldRow, Row newRow) {
        // unique index check is enough
    }

    public boolean usesIndex(Index idx) {
        return idx == index;
    }

    public void setIndexOwner(Index index) {
        indexOwner = true;
    }

    public HashSet<Column> getReferencedColumns(Table table) {
        HashSet<Column> result = New.hashSet();
        for (IndexColumn c : columns) {
            result.add(c.column);
        }
        return result;
    }

    public boolean isBefore() {
        return true;
    }

    public void checkExistingData(Session session) {
        // no need to check: when creating the unique index any problems are found
    }

    public Index getUniqueIndex() {
        return index;
    }

    public void rebuild() {
        // nothing to do
    }

}
