/*
 * Copyright 2004-2007 H2 Group. Licensed under the H2 License, Version 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.server.ftp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.sql.SQLException;

import org.h2.store.fs.FileSystem;
import org.h2.util.IOUtils;

public class FtpData extends Thread {

    private FtpServer server;
    private InetAddress address;
    private ServerSocket serverSocket;
    private volatile Socket socket;

    public FtpData(FtpServer server, InetAddress address, ServerSocket serverSocket) throws IOException {
        this.server = server;
        this.address = address;
        this.serverSocket = serverSocket;
    }

    public void run() {
        try {
            synchronized (this) {
                Socket s = serverSocket.accept();
                if (s.getInetAddress().equals(address)) {
                    server.log("Data connected:" + s.getInetAddress() + " expected:" + address);
                    socket = s;
                    notifyAll();
                } else {
                    server.log("Data REJECTED:" + s.getInetAddress() + " expected:" + address);
                    close();
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void waitUntilConnected() {
        while (serverSocket != null && socket == null) {
            try {
                wait();
            } catch (InterruptedException e) {
                // ignore
            }
        }
        server.log("connected");
    }

    public void close() {
        serverSocket = null;
        socket = null;
    }

    public synchronized void receive(FileSystem fs, String fileName) throws IOException, SQLException {
        waitUntilConnected();
        try {
            InputStream in = socket.getInputStream();
            OutputStream out = fs.openFileOutputStream(fileName, false);
            IOUtils.copy(in, out);
            out.close();
        } finally {
            socket.close();
        }
        server.log("closed");
    }

    public synchronized void send(FileSystem fs, String fileName, long skip) throws IOException {
        waitUntilConnected();
        try {
            OutputStream out = socket.getOutputStream();
            InputStream in = fs.openFileInputStream(fileName);
            in.skip(skip);
            IOUtils.copy(in, out);
            in.close();
        } finally {
            socket.close();
        }
        server.log("closed");
    }

    public synchronized void send(byte[] data) throws IOException {
        waitUntilConnected();
        try {
            OutputStream out = socket.getOutputStream();
            out.write(data);
        } finally {
            socket.close();
        }
        server.log("closed");
    }

}
