/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.mirth.connect.connectors.smtpreceiver.server;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLSocket;

/** One client connection: the SMTP dialogue (RFC 5321) with AUTH PLAIN/LOGIN (RFC 4954) and STARTTLS (RFC 3207). */
class SmtpSession implements Runnable {
    private static final int MAX_COMMAND_LINE = 4096;
    private static final int MAX_ERRORS = 20;
    private static final int MAX_AUTH_FAILURES = 3;

    private static final Pattern MAIL_FROM = Pattern.compile("^FROM:\\s*<([^>]*)>\\s*(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern MAIL_FROM_BARE = Pattern.compile("^FROM:\\s*(\\S+)\\s*(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern RCPT_TO = Pattern.compile("^TO:\\s*<([^>]*)>\\s*(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern RCPT_TO_BARE = Pattern.compile("^TO:\\s*(\\S+)\\s*(.*)$", Pattern.CASE_INSENSITIVE);

    private final SmtpConfig config;
    private final SmtpServer.Handler handler;
    private final SmtpServer.Events events;

    private volatile Socket socket;
    private volatile boolean closed = false;
    private BufferedInputStream in;
    private OutputStream out;
    private boolean lineOverflow;

    /** True while a message is being received or handed to the channel: the session is not to be cut off then. */
    private volatile boolean busy = false;

    private boolean tls;
    private String helo = "";
    private String authUser;
    private int authFailures;
    private int errors;

    private String mailFrom;
    private final List<String> recipients = new ArrayList<String>();

    private final String remote;

    SmtpSession(Socket socket, SmtpConfig config, SmtpServer.Handler handler, SmtpServer.Events events) {
        this.socket = socket;
        this.config = config;
        this.handler = handler;
        this.events = events;
        this.tls = config.tlsMode == SmtpConfig.TlsMode.IMPLICIT;
        this.remote = socket.getInetAddress().getHostAddress() + ":" + socket.getPort();
    }

    String remote() {
        return remote;
    }

    /** Tells a session that is only waiting for its next command that the server is going down, and closes it. */
    void stopIfIdle() {
        if (!busy && !closed) {
            try {
                reply(421, "4.3.2", "Service shutting down, closing the connection");
            } catch (IOException ignored) {
                // the client is gone
            }
            close();
        }
    }

    /** Closes the connection from another thread; the session ends. */
    void close() {
        closed = true;
        closeQuietly();
    }

    @Override
    public void run() {
        events.connected(remote);
        try {
            socket.setSoTimeout(config.timeoutMillis);
            socket.setTcpNoDelay(true);
            open();
            reply(220, null, config.hostname + " ESMTP ready");

            boolean quit = false;
            while (!quit) {
                byte[] raw = readLine(MAX_COMMAND_LINE);
                if (raw == null) {
                    break;
                }
                if (lineOverflow) {
                    errors++;
                    reply(500, "5.5.2", "Line too long");
                } else {
                    quit = command(new String(raw, StandardCharsets.ISO_8859_1));
                }
                if (!quit && errors > MAX_ERRORS) {
                    reply(421, "4.7.0", "Too many errors, closing the connection");
                    quit = true;
                }
            }
        } catch (SocketTimeoutException e) {
            try {
                reply(421, "4.4.2", "Timeout waiting for the client, closing the connection");
            } catch (IOException ignored) {
                // the client is gone
            }
        } catch (IOException e) {
            if (!closed) {
                events.info("Connection with " + remote + " ended: " + e.getMessage());
            }
        } catch (Throwable t) {
            events.failure("Unexpected error in the SMTP session with " + remote + ": " + t, t);
        } finally {
            closeQuietly();
            events.disconnected(remote);
        }
    }

    // ---- commands

    /** @return true when the session is over */
    private boolean command(String line) throws IOException {
        int space = line.indexOf(' ');
        String verb = (space < 0 ? line : line.substring(0, space)).toUpperCase(Locale.ROOT);
        String arg = space < 0 ? "" : line.substring(space + 1).trim();

        switch (verb) {
            case "HELO":
                hello(arg, false);
                return false;
            case "EHLO":
                hello(arg, true);
                return false;
            case "STARTTLS":
                return startTls();
            case "AUTH":
                return auth(arg);
            case "MAIL":
                mail(arg);
                return false;
            case "RCPT":
                rcpt(arg);
                return false;
            case "DATA":
                data();
                return false;
            case "RSET":
                resetTransaction();
                reply(250, "2.0.0", "Ok");
                return false;
            case "NOOP":
                reply(250, "2.0.0", "Ok");
                return false;
            case "VRFY":
                reply(252, "2.5.2", "Cannot verify the user, but will accept the message");
                return false;
            case "HELP":
                reply(214, "2.0.0", "See RFC 5321");
                return false;
            case "QUIT":
                reply(221, "2.0.0", "Bye");
                return true;
            default:
                errors++;
                reply(500, "5.5.1", verb.isEmpty() ? "Syntax error, command expected" : "Command unrecognized");
                return false;
        }
    }

    private void hello(String name, boolean extended) throws IOException {
        if (name.isEmpty()) {
            errors++;
            reply(501, "5.5.4", "Syntax: " + (extended ? "EHLO" : "HELO") + " hostname");
            return;
        }
        helo = name;
        resetTransaction();

        String greeting = config.hostname + " greets " + socket.getInetAddress().getHostAddress();
        if (!extended) {
            reply(250, null, greeting);
            return;
        }

        List<String> lines = new ArrayList<String>();
        lines.add(greeting);
        lines.add("PIPELINING");
        lines.add("8BITMIME");
        lines.add("ENHANCEDSTATUSCODES");
        if (config.maxMessageBytes > 0) {
            lines.add("SIZE " + config.maxMessageBytes);
        }
        if (config.tlsMode == SmtpConfig.TlsMode.STARTTLS && !tls) {
            lines.add("STARTTLS");
        }
        if (authenticationAllowedNow()) {
            lines.add("AUTH PLAIN LOGIN");
        }
        replyLines(250, lines);
    }

    private boolean startTls() throws IOException {
        if (config.tlsMode != SmtpConfig.TlsMode.STARTTLS || config.sslContext == null) {
            errors++;
            reply(502, "5.5.1", "STARTTLS is not available");
            return false;
        }
        if (tls) {
            errors++;
            reply(503, "5.5.1", "TLS is already active");
            return false;
        }
        if (in.available() > 0) {
            // Data behind STARTTLS in the same packet would be read as if it came over TLS (CVE-2011-0411).
            reply(501, "5.5.4", "No data allowed behind STARTTLS");
            return true;
        }

        reply(220, "2.0.0", "Ready to start TLS");

        SSLSocket ssl = (SSLSocket) config.sslContext.getSocketFactory().createSocket(socket, socket.getInetAddress().getHostAddress(), socket.getPort(), true);
        ssl.setUseClientMode(false);
        ssl.setEnabledProtocols(SmtpServer.enabledProtocols(ssl.getSupportedProtocols()));
        ssl.setSoTimeout(config.timeoutMillis);
        ssl.startHandshake();
        socket = ssl;
        open();

        // What was said before TLS no longer counts (RFC 3207).
        tls = true;
        helo = "";
        authUser = null;
        resetTransaction();
        return false;
    }

    private boolean authenticationAllowedNow() {
        return config.authenticationPossible() && authUser == null && (config.tlsMode == SmtpConfig.TlsMode.NONE || tls);
    }

    private boolean auth(String arg) throws IOException {
        if (!config.authenticationPossible()) {
            errors++;
            reply(502, "5.5.1", "Authentication is not enabled");
            return false;
        }
        if (authUser != null) {
            errors++;
            reply(503, "5.5.1", "Already authenticated");
            return false;
        }
        if (config.tlsMode != SmtpConfig.TlsMode.NONE && !tls) {
            reply(538, "5.7.11", "Encryption required for requested authentication mechanism");
            return false;
        }
        if (mailFrom != null) {
            errors++;
            reply(503, "5.5.1", "AUTH is not allowed during a mail transaction");
            return false;
        }

        String[] parts = arg.split("\\s+", 2);
        String mechanism = parts[0].toUpperCase(Locale.ROOT);
        String initial = parts.length > 1 ? parts[1].trim() : null;

        String user;
        String password;
        try {
            if (mechanism.equals("PLAIN")) {
                String response = initial != null ? initial : challenge("");
                if (response.equals("*")) {
                    reply(501, "5.0.0", "Authentication canceled");
                    return false;
                }
                // authorization id NUL authentication id NUL password
                String decoded = new String(Base64.getMimeDecoder().decode(response.equals("=") ? "" : response), StandardCharsets.UTF_8);
                String[] fields = decoded.split("\0", -1);
                if (fields.length != 3) {
                    errors++;
                    reply(501, "5.5.2", "Cannot decode the response");
                    return false;
                }
                user = fields[1];
                password = fields[2];
            } else if (mechanism.equals("LOGIN")) {
                String userResponse = initial != null ? initial : challenge("VXNlcm5hbWU6");
                if (userResponse.equals("*")) {
                    reply(501, "5.0.0", "Authentication canceled");
                    return false;
                }
                user = new String(Base64.getMimeDecoder().decode(userResponse), StandardCharsets.UTF_8);
                String passwordResponse = challenge("UGFzc3dvcmQ6");
                if (passwordResponse.equals("*")) {
                    reply(501, "5.0.0", "Authentication canceled");
                    return false;
                }
                password = new String(Base64.getMimeDecoder().decode(passwordResponse), StandardCharsets.UTF_8);
            } else {
                errors++;
                reply(504, "5.5.4", "Unrecognized authentication type");
                return false;
            }
        } catch (IllegalArgumentException e) {
            errors++;
            reply(501, "5.5.2", "Cannot decode the response");
            return false;
        }

        if (passwordMatches(user, password)) {
            authUser = user;
            authFailures = 0;
            reply(235, "2.7.0", "Authentication successful");
            return false;
        }

        authFailures++;
        events.info("Failed login for \"" + user + "\" from " + remote);
        reply(535, "5.7.8", "Authentication credentials invalid");
        if (authFailures >= MAX_AUTH_FAILURES) {
            reply(421, "4.7.0", "Too many failed logins, closing the connection");
            return true;
        }
        return false;
    }

    private boolean passwordMatches(String user, String password) {
        String expected = config.users.get(user);
        if (expected == null) {
            // Same work for an unknown user, so the response time does not tell which users exist.
            expected = "";
            MessageDigest.isEqual(password.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
            return false;
        }
        return MessageDigest.isEqual(password.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }

    /** Sends a 334 challenge and returns the client's answer. */
    private String challenge(String text) throws IOException {
        send("334 " + text);
        byte[] raw = readLine(MAX_COMMAND_LINE);
        if (raw == null) {
            throw new EOFException();
        }
        return new String(raw, StandardCharsets.ISO_8859_1).trim();
    }

    private void mail(String arg) throws IOException {
        if (config.requireAuthentication && authUser == null) {
            errors++;
            reply(530, "5.7.0", "Authentication required");
            return;
        }
        if (mailFrom != null) {
            errors++;
            reply(503, "5.5.1", "Nested MAIL command");
            return;
        }

        Matcher m = MAIL_FROM.matcher(arg);
        if (!m.matches()) {
            m = MAIL_FROM_BARE.matcher(arg);
            if (!m.matches()) {
                errors++;
                reply(501, "5.5.4", "Syntax: MAIL FROM:<address>");
                return;
            }
        }

        for (String parameter : m.group(2).trim().split("\\s+")) {
            if (parameter.toUpperCase(Locale.ROOT).startsWith("SIZE=")) {
                try {
                    long size = Long.parseLong(parameter.substring(5));
                    if (config.maxMessageBytes > 0 && size > config.maxMessageBytes) {
                        reply(552, "5.3.4", "Message size exceeds the limit of " + config.maxMessageBytes + " bytes");
                        return;
                    }
                } catch (NumberFormatException e) {
                    errors++;
                    reply(501, "5.5.4", "Invalid SIZE parameter");
                    return;
                }
            }
            // BODY=8BITMIME and other parameters are accepted as they are.
        }

        mailFrom = m.group(1).trim();
        recipients.clear();
        reply(250, "2.1.0", "Sender ok");
    }

    private void rcpt(String arg) throws IOException {
        if (mailFrom == null) {
            errors++;
            reply(503, "5.5.1", "Need MAIL before RCPT");
            return;
        }

        Matcher m = RCPT_TO.matcher(arg);
        if (!m.matches()) {
            m = RCPT_TO_BARE.matcher(arg);
            if (!m.matches()) {
                errors++;
                reply(501, "5.5.4", "Syntax: RCPT TO:<address>");
                return;
            }
        }

        String address = m.group(1).trim();
        if (address.isEmpty()) {
            errors++;
            reply(501, "5.1.3", "Bad recipient address syntax");
            return;
        }
        if (recipients.size() >= config.maxRecipients) {
            reply(452, "4.5.3", "Too many recipients");
            return;
        }

        recipients.add(address);
        reply(250, "2.1.5", "Recipient ok");
    }

    private void data() throws IOException {
        if (mailFrom == null) {
            errors++;
            reply(503, "5.5.1", "Need MAIL before DATA");
            return;
        }
        if (recipients.isEmpty()) {
            errors++;
            reply(503, "5.5.1", "Need RCPT before DATA");
            return;
        }

        busy = true;
        try {
            receiveData();
        } finally {
            busy = false;
        }
    }

    private void receiveData() throws IOException {
        reply(354, null, "End data with <CR><LF>.<CR><LF>");

        long max = config.maxMessageBytes;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(8192);
        boolean tooBig = false;
        long total = 0;

        while (true) {
            // A line without end that is longer than what is still allowed is cut off, not kept in memory.
            int limit = max > 0 ? (int) Math.min(Integer.MAX_VALUE - 16, Math.max(1, max - total + 1024)) : Integer.MAX_VALUE - 16;
            byte[] line = readLine(limit);
            if (line == null) {
                throw new EOFException("The client closed the connection during DATA");
            }
            if (!lineOverflow && line.length == 1 && line[0] == '.') {
                break;
            }
            if (tooBig) {
                continue;
            }
            if (lineOverflow) {
                tooBig = true;
                continue;
            }

            // A line that starts with a dot was doubled by the client (dot-stuffing).
            int offset = line.length > 0 && line[0] == '.' ? 1 : 0;
            total += line.length - offset + 2;
            if (max > 0 && total > max) {
                tooBig = true;
                buffer = null;
                continue;
            }
            buffer.write(line, offset, line.length - offset);
            buffer.write('\r');
            buffer.write('\n');
        }

        if (tooBig) {
            resetTransaction();
            reply(552, "5.3.4", "Message size exceeds the limit of " + max + " bytes");
            return;
        }

        SmtpMessage message = new SmtpMessage(socket.getInetAddress().getHostAddress(), socket.getPort(), socket.getLocalAddress().getHostAddress(), socket.getLocalPort(), helo, mailFrom, recipients, authUser, tls, buffer.toByteArray());
        resetTransaction();

        SmtpReply result;
        try {
            result = handler.onMessage(message);
        } catch (Throwable t) {
            events.failure("Error processing a message from " + remote + ": " + t, t);
            result = SmtpReply.tryAgain("Local error in processing, try again later");
        }
        reply(result.code, result.enhanced, result.text);
    }

    private void resetTransaction() {
        mailFrom = null;
        recipients.clear();
    }

    // ---- I/O

    private void open() throws IOException {
        InputStream raw = socket.getInputStream();
        in = new BufferedInputStream(raw, 8192);
        out = socket.getOutputStream();
    }

    /**
     * Reads a line, without its line ending (CRLF or a lone LF). At most limit bytes are kept; when the line is
     * longer, lineOverflow is set and the rest of it is thrown away.
     *
     * @return null at the end of the stream
     */
    private byte[] readLine(int limit) throws IOException {
        lineOverflow = false;
        ByteArrayOutputStream line = new ByteArrayOutputStream(80);
        boolean any = false;
        int c;
        while ((c = in.read()) != -1) {
            any = true;
            if (c == '\n') {
                byte[] bytes = line.toByteArray();
                if (bytes.length > 0 && bytes[bytes.length - 1] == '\r') {
                    return java.util.Arrays.copyOf(bytes, bytes.length - 1);
                }
                return bytes;
            }
            if (line.size() < limit) {
                line.write(c);
            } else {
                lineOverflow = true;
            }
        }
        return any ? line.toByteArray() : null;
    }

    private void reply(int code, String enhanced, String text) throws IOException {
        send(code + " " + (enhanced != null ? enhanced + " " : "") + clean(text));
    }

    private void replyLines(int code, List<String> lines) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            sb.append(code).append(i < lines.size() - 1 ? '-' : ' ').append(clean(lines.get(i))).append("\r\n");
        }
        write(sb.toString());
    }

    private void send(String line) throws IOException {
        write(line + "\r\n");
    }

    // Also called from the thread that stops the server.
    private synchronized void write(String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    /** No line breaks in a reply: a channel could put anything in the text. */
    private static String clean(String text) {
        return text == null ? "" : text.replace('\r', ' ').replace('\n', ' ');
    }

    private void closeQuietly() {
        Socket s = socket;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // nothing to do
            }
        }
    }
}
