/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.mirth.connect.connectors.smtpreceiver.server;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLServerSocket;

/** A small SMTP server. It knows nothing about the engine: every complete message goes to the {@link Handler}. */
public class SmtpServer {

    public interface Handler {
        /** Called for every complete message; the returned reply is what the client gets. */
        SmtpReply onMessage(SmtpMessage message);
    }

    /** Notifications for the dashboard and the log. */
    public interface Events {
        void connected(String remote);

        void disconnected(String remote);

        void info(String text);

        void failure(String text, Throwable t);
    }

    public static final Events NO_EVENTS = new Events() {
        @Override
        public void connected(String remote) {}

        @Override
        public void disconnected(String remote) {}

        @Override
        public void info(String text) {}

        @Override
        public void failure(String text, Throwable t) {}
    };

    private static final int BACKLOG = 256;

    private final SmtpConfig config;
    private final Handler handler;
    private final Events events;

    private final Set<SmtpSession> sessions = ConcurrentHashMap.newKeySet();
    private volatile boolean closing = false;
    private ServerSocket serverSocket;
    private Thread acceptor;
    private ExecutorService pool;
    private Semaphore permits;

    public SmtpServer(SmtpConfig config, Handler handler, Events events) {
        this.config = config;
        this.handler = handler;
        this.events = events == null ? NO_EVENTS : events;
    }

    /** Binds the port and starts accepting connections. */
    public synchronized void start() throws IOException {
        if (serverSocket != null) {
            throw new IllegalStateException("The SMTP server is already running");
        }
        closing = false;

        InetAddress bind = config.bindHost == null || config.bindHost.trim().isEmpty() || config.bindHost.trim().equals("0.0.0.0") ? null : InetAddress.getByName(config.bindHost.trim());

        ServerSocket socket;
        if (config.tlsMode == SmtpConfig.TlsMode.IMPLICIT) {
            if (config.sslContext == null) {
                throw new IOException("TLS is switched on but there is no SSL context");
            }
            SSLServerSocket ssl = (SSLServerSocket) config.sslContext.getServerSocketFactory().createServerSocket();
            ssl.setEnabledProtocols(enabledProtocols(ssl.getSupportedProtocols()));
            socket = ssl;
        } else {
            socket = new ServerSocket();
        }
        socket.setReuseAddress(true);
        try {
            socket.bind(new InetSocketAddress(bind, config.port), BACKLOG);
        } catch (IOException e) {
            socket.close();
            throw e;
        }
        serverSocket = socket;

        permits = new Semaphore(config.maxConnections);
        final AtomicInteger counter = new AtomicInteger();
        pool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "SMTP Receiver session " + counter.incrementAndGet() + " on port " + serverSocket.getLocalPort());
            t.setDaemon(true);
            return t;
        });

        acceptor = new Thread(this::acceptLoop, "SMTP Receiver acceptor on port " + socket.getLocalPort());
        acceptor.setDaemon(true);
        acceptor.start();
    }

    /** The port that is really in use (useful when port 0 was asked for). */
    public int getPort() {
        return serverSocket == null ? -1 : serverSocket.getLocalPort();
    }

    public int activeConnections() {
        return sessions.size();
    }

    /** Stops accepting, lets running sessions finish for a while, then closes what is left. */
    public void stop(long graceMillis) {
        closing = true;
        closeServerSocket();
        joinAcceptor();

        // Sessions that only wait for a command are closed at once; the ones busy with a message get time to finish.
        long deadline = System.currentTimeMillis() + graceMillis;
        while (!sessions.isEmpty() && System.currentTimeMillis() < deadline) {
            for (SmtpSession session : new ArrayList<SmtpSession>(sessions)) {
                session.stopIfIdle();
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        closeSessions();
        shutdownPool();
        serverSocket = null;
    }

    /** Stops at once: the listening socket and every connection are closed. */
    public void halt() {
        closing = true;
        closeServerSocket();
        closeSessions();
        joinAcceptor();
        if (pool != null) {
            pool.shutdownNow();
        }
        serverSocket = null;
    }

    private void acceptLoop() {
        while (!closing) {
            Socket client;
            try {
                client = serverSocket.accept();
            } catch (IOException e) {
                if (!closing) {
                    events.failure("Error accepting a connection: " + e.getMessage(), e);
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException ie) {
                        return;
                    }
                    continue;
                }
                return;
            }

            if (!permits.tryAcquire()) {
                refuse(client);
                continue;
            }

            final SmtpSession session = new SmtpSession(client, config, handler, events);
            sessions.add(session);
            try {
                pool.execute(() -> {
                    try {
                        session.run();
                    } finally {
                        sessions.remove(session);
                        permits.release();
                    }
                });
            } catch (RuntimeException e) {
                // The pool was shut down between accept and execute.
                sessions.remove(session);
                permits.release();
                try {
                    client.close();
                } catch (IOException ignored) {
                    // nothing to do
                }
            }
        }
    }

    private void refuse(Socket client) {
        events.info("Refused a connection from " + client.getInetAddress().getHostAddress() + ": the maximum number of connections (" + config.maxConnections + ") is in use");
        try {
            client.setSoTimeout(2000);
            client.getOutputStream().write(("421 4.3.2 " + config.hostname + " Too many connections, try again later\r\n").getBytes(StandardCharsets.ISO_8859_1));
            client.getOutputStream().flush();
        } catch (IOException ignored) {
            // the client may be gone already
        } finally {
            try {
                client.close();
            } catch (IOException ignored) {
                // nothing to do
            }
        }
    }

    private void closeServerSocket() {
        ServerSocket s = serverSocket;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // nothing to do
            }
        }
    }

    private void closeSessions() {
        for (SmtpSession session : new ArrayList<SmtpSession>(sessions)) {
            session.close();
        }
    }

    private void joinAcceptor() {
        Thread t = acceptor;
        if (t != null && t != Thread.currentThread()) {
            try {
                t.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void shutdownPool() {
        if (pool != null) {
            pool.shutdown();
            try {
                if (!pool.awaitTermination(2, TimeUnit.SECONDS)) {
                    pool.shutdownNow();
                }
            } catch (InterruptedException e) {
                pool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    /** TLS 1.3 and 1.2 only, as far as the JVM supports them. */
    static String[] enabledProtocols(String[] supported) {
        List<String> enabled = new ArrayList<String>();
        for (String wanted : new String[] { "TLSv1.3", "TLSv1.2" }) {
            for (String s : supported) {
                if (s.equals(wanted)) {
                    enabled.add(wanted);
                }
            }
        }
        return enabled.isEmpty() ? supported : enabled.toArray(new String[0]);
    }
}
