/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.mirth.connect.connectors.smtpreceiver.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

/** Tests the SMTP server with a real SMTP client (Jakarta Mail) and with raw sockets for the protocol details. */
public class SmtpServerTest {

    private SmtpConfig config;
    private SmtpServer server;
    private final List<SmtpMessage> received = new CopyOnWriteArrayList<SmtpMessage>();
    private volatile SmtpReply nextReply;
    private volatile boolean handlerThrows;
    /** When set, the handler waits for it: a message that is being processed. */
    private volatile java.util.concurrent.CountDownLatch handlerGate;
    private final List<String> failures = new CopyOnWriteArrayList<String>();

    @Before
    public void setUp() {
        config = new SmtpConfig();
        config.bindHost = "127.0.0.1";
        config.port = 0;
        config.hostname = "test.local";
        config.timeoutMillis = 5000;
    }

    @After
    public void tearDown() {
        if (server != null) {
            server.halt();
        }
    }

    private int start() throws IOException {
        server = new SmtpServer(config, message -> {
            if (handlerThrows) {
                throw new IllegalStateException("boom");
            }
            java.util.concurrent.CountDownLatch gate = handlerGate;
            if (gate != null) {
                try {
                    gate.await(10, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            received.add(message);
            SmtpReply reply = nextReply;
            return reply != null ? reply : SmtpReply.ok("Ok: queued as " + received.size());
        }, new SmtpServer.Events() {
            @Override
            public void connected(String remote) {}

            @Override
            public void disconnected(String remote) {}

            @Override
            public void info(String text) {}

            @Override
            public void failure(String text, Throwable t) {
                failures.add(text);
            }
        });
        server.start();
        return server.getPort();
    }

    // ---- helpers

    private Session session(int port, Properties extra) {
        Properties p = new Properties();
        p.put("mail.smtp.host", "127.0.0.1");
        p.put("mail.smtp.port", String.valueOf(port));
        p.put("mail.smtp.connectiontimeout", "5000");
        p.put("mail.smtp.timeout", "5000");
        p.putAll(extra);
        return Session.getInstance(p);
    }

    private MimeMessage mail(Session session, String from, String subject, String body, String... to) throws MessagingException {
        MimeMessage m = new MimeMessage(session);
        m.setFrom(new InternetAddress(from));
        for (String t : to) {
            m.addRecipient(Message.RecipientType.TO, new InternetAddress(t));
        }
        m.setSubject(subject);
        m.setText(body, "us-ascii");
        return m;
    }

    private void send(int port, MimeMessage m) throws MessagingException {
        Transport.send(m);
    }

    private static String text(SmtpMessage m) {
        return m.dataAsString(StandardCharsets.ISO_8859_1);
    }

    /** A bare socket: sends what it is told and reads whole replies. */
    private static class Raw implements Closeable {
        final Socket socket;
        final InputStream in;
        final OutputStream out;

        Raw(int port) throws IOException {
            socket = new Socket("127.0.0.1", port);
            socket.setSoTimeout(5000);
            in = socket.getInputStream();
            out = socket.getOutputStream();
        }

        String line() throws IOException {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            int c;
            while ((c = in.read()) != -1) {
                if (c == '\n') {
                    break;
                }
                if (c != '\r') {
                    b.write(c);
                }
            }
            if (c == -1 && b.size() == 0) {
                return null;
            }
            return new String(b.toByteArray(), StandardCharsets.ISO_8859_1);
        }

        /** A whole reply, all lines of a multi-line reply. */
        String reply() throws IOException {
            StringBuilder all = new StringBuilder();
            String l;
            do {
                l = line();
                if (l == null) {
                    return all.length() == 0 ? null : all.toString().trim();
                }
                all.append(l).append('\n');
            } while (l.length() > 3 && l.charAt(3) == '-');
            return all.toString().trim();
        }

        Raw send(String s) throws IOException {
            out.write(s.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            return this;
        }

        String cmd(String command) throws IOException {
            send(command + "\r\n");
            return reply();
        }

        boolean isClosedByServer() throws IOException {
            try {
                return in.read() == -1;
            } catch (java.net.SocketException e) {
                return true;
            }
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    private static void expect(String code, String reply) {
        assertNotNull("no reply, expected " + code, reply);
        assertTrue("expected " + code + " but got: " + reply, reply.startsWith(code));
    }

    private void addUser(String user, String password) {
        Map<String, String> users = new LinkedHashMap<String, String>(config.users);
        users.put(user, password);
        config.users = users;
    }

    // ---- delivery with a real client

    @Test
    public void receivesAMailWithHeadersAndBody() throws Exception {
        int port = start();
        Session s = session(port, new Properties());

        send(port, mail(s, "Sender@Example.COM", "Hello there", "First line\n\nThird line", "Rcpt.One@Example.org"));

        assertEquals(1, received.size());
        SmtpMessage m = received.get(0);
        assertEquals("Sender@Example.COM", m.mailFrom);
        assertEquals(Collections.singletonList("Rcpt.One@Example.org"), m.recipients);
        assertEquals("127.0.0.1", m.clientAddress);
        assertFalse(m.tls);
        assertNull(m.authenticatedUser);

        String data = text(m);
        assertTrue(data, data.contains("Subject: Hello there\r\n"));
        assertTrue(data, data.contains("\r\n\r\nFirst line\r\n\r\nThird line"));
        assertTrue(data, data.endsWith("\r\n"));
    }

    @Test
    public void everyRecipientIsReported() throws Exception {
        int port = start();
        send(port, mail(session(port, new Properties()), "a@b.nl", "Multi", "x", "one@x.nl", "Two@X.nl", "three@x.nl"));

        assertEquals(3, received.get(0).recipients.size());
        assertEquals("Two@X.nl", received.get(0).recipients.get(1));
    }

    @Test
    public void severalMessagesOverOneConnection() throws Exception {
        int port = start();
        Session s = session(port, new Properties());
        Transport t = s.getTransport("smtp");
        t.connect();
        try {
            MimeMessage m1 = mail(s, "a@b.nl", "One", "first", "c@d.nl");
            MimeMessage m2 = mail(s, "a@b.nl", "Two", "second", "c@d.nl");
            t.sendMessage(m1, m1.getAllRecipients());
            t.sendMessage(m2, m2.getAllRecipients());
        } finally {
            t.close();
        }

        assertEquals(2, received.size());
        assertTrue(text(received.get(0)).contains("Subject: One"));
        assertTrue(text(received.get(1)).contains("Subject: Two"));
    }

    @Test
    public void linesThatStartWithADotComeThroughIntact() throws Exception {
        int port = start();
        send(port, mail(session(port, new Properties()), "a@b.nl", "Dots", "start\n.one dot\n..two dots\n.\nend", "c@d.nl"));

        String data = text(received.get(0));
        assertTrue(data, data.contains("\r\n.one dot\r\n"));
        assertTrue(data, data.contains("\r\n..two dots\r\n"));
        assertTrue(data, data.contains("\r\n.\r\nend"));
    }

    @Test
    public void emptyLinesAreKept() throws Exception {
        int port = start();
        send(port, mail(session(port, new Properties()), "a@b.nl", "Blank", "a\n\n\nb", "c@d.nl"));

        assertTrue(text(received.get(0)), text(received.get(0)).contains("\r\n\r\na\r\n\r\n\r\nb"));
    }

    @Test
    public void eightBitDataIsNotChanged() throws Exception {
        int port = start();
        try (Raw raw = new Raw(port)) {
            expect("220", raw.reply());
            expect("250", raw.cmd("EHLO client"));
            expect("250", raw.cmd("MAIL FROM:<a@b.nl> BODY=8BITMIME"));
            expect("250", raw.cmd("RCPT TO:<c@d.nl>"));
            expect("354", raw.cmd("DATA"));
            raw.out.write(new byte[] { 'S', 'u', 'b', 'j', 'e', 'c', 't', ':', ' ', (byte) 0xC3, (byte) 0xA9, (byte) 0xE2, (byte) 0x82, (byte) 0xAC, '\r', '\n', '\r', '\n', 'b', 'o', 'd', 'y', '\r', '\n', '.', '\r', '\n' });
            raw.out.flush();
            expect("250", raw.reply());
        }

        String utf8 = received.get(0).dataAsString(StandardCharsets.UTF_8);
        assertTrue(utf8, utf8.startsWith("Subject: é€\r\n\r\nbody\r\n"));
    }

    @Test
    public void lineEndingsWithoutCarriageReturnAreAccepted() throws Exception {
        int port = start();
        try (Raw raw = new Raw(port)) {
            expect("220", raw.reply());
            raw.send("EHLO client\n");
            expect("250", raw.reply());
            raw.send("MAIL FROM:<a@b.nl>\nRCPT TO:<c@d.nl>\nDATA\n");
            expect("250", raw.reply());
            expect("250", raw.reply());
            expect("354", raw.reply());
            raw.send("Subject: LF only\n\nbody\n.\n");
            expect("250", raw.reply());
        }

        assertEquals("Subject: LF only\r\n\r\nbody\r\n", text(received.get(0)));
    }

    // ---- what the channel answers

    @Test
    public void replyOfTheHandlerIsWhatTheClientGets() throws Exception {
        int port = start();
        try (Raw raw = new Raw(port)) {
            expect("220", raw.reply());
            raw.cmd("EHLO c");
            raw.cmd("MAIL FROM:<a@b.nl>");
            raw.cmd("RCPT TO:<c@d.nl>");
            raw.cmd("DATA");
            raw.send("x\r\n.\r\n");
            assertEquals("250 2.0.0 Ok: queued as 1", raw.reply());
        }
    }

    @Test
    public void aRejectedMessageGetsATemporaryFailure() throws Exception {
        nextReply = SmtpReply.tryAgain("Channel is busy");
        int port = start();
        try {
            send(port, mail(session(port, new Properties()), "a@b.nl", "Nope", "x", "c@d.nl"));
            fail("expected a failure");
        } catch (MessagingException e) {
            assertTrue(e.toString(), e.toString().contains("451"));
        }
    }

    @Test
    public void anExceptionInTheHandlerBecomesA451() throws Exception {
        handlerThrows = true;
        int port = start();
        try (Raw raw = new Raw(port)) {
            expect("220", raw.reply());
            raw.cmd("EHLO c");
            raw.cmd("MAIL FROM:<a@b.nl>");
            raw.cmd("RCPT TO:<c@d.nl>");
            raw.cmd("DATA");
            raw.send("x\r\n.\r\n");
            expect("451", raw.reply());
            // and the connection still works
            expect("250", raw.cmd("NOOP"));
        }
        assertEquals(1, failures.size());
    }

    @Test
    public void aReplyTextCannotBreakTheProtocol() throws Exception {
        nextReply = SmtpReply.ok("line one\r\n250 injected");
        int port = start();
        try (Raw raw = new Raw(port)) {
            expect("220", raw.reply());
            raw.cmd("EHLO c");
            raw.cmd("MAIL FROM:<a@b.nl>");
            raw.cmd("RCPT TO:<c@d.nl>");
            raw.cmd("DATA");
            raw.send("x\r\n.\r\n");
            String reply = raw.reply();
            assertFalse(reply, reply.contains("\n"));
            expect("250", raw.cmd("NOOP"));
        }
    }

    // ---- authentication

    @Test
    public void withoutALoginTheMailIsRefused() throws Exception {
        config.requireAuthentication = true;
        addUser("mirth", "secret");
        int port = start();

        try (Raw raw = new Raw(port)) {
            expect("220", raw.reply());
            expect("250", raw.cmd("EHLO c"));
            expect("530", raw.cmd("MAIL FROM:<a@b.nl>"));
        }
        assertTrue(received.isEmpty());
    }

    @Test
    public void loginWithPlainDelivers() throws Exception {
        config.requireAuthentication = true;
        addUser("mirth", "secret");
        int port = start();

        Properties p = new Properties();
        p.put("mail.smtp.auth", "true");
        p.put("mail.smtp.auth.mechanisms", "PLAIN");
        Session s = session(port, p);
        MimeMessage m = mail(s, "a@b.nl", "Auth", "x", "c@d.nl");
        Transport t = s.getTransport("smtp");
        t.connect("mirth", "secret");
        try {
            t.sendMessage(m, m.getAllRecipients());
        } finally {
            t.close();
        }

        assertEquals(1, received.size());
        assertEquals("mirth", received.get(0).authenticatedUser);
    }

    @Test
    public void loginWithLoginDelivers() throws Exception {
        config.requireAuthentication = true;
        addUser("test", "456");
        int port = start();

        Properties p = new Properties();
        p.put("mail.smtp.auth", "true");
        p.put("mail.smtp.auth.mechanisms", "LOGIN");
        Session s = session(port, p);
        MimeMessage m = mail(s, "a@b.nl", "Auth", "x", "c@d.nl");
        Transport t = s.getTransport("smtp");
        t.connect("test", "456");
        try {
            t.sendMessage(m, m.getAllRecipients());
        } finally {
            t.close();
        }

        assertEquals("test", received.get(0).authenticatedUser);
    }

    @Test
    public void aWrongPasswordIsRefused() throws Exception {
        config.requireAuthentication = true;
        addUser("mirth", "secret");
        int port = start();

        Properties p = new Properties();
        p.put("mail.smtp.auth", "true");
        Session s = session(port, p);
        Transport t = s.getTransport("smtp");
        try {
            t.connect("mirth", "wrong");
            fail("expected a failed login");
        } catch (jakarta.mail.AuthenticationFailedException e) {
            assertTrue(e.toString(), e.toString().contains("535"));
        }
        assertTrue(received.isEmpty());
    }

    @Test
    public void anUnknownUserIsRefusedLikeAWrongPassword() throws Exception {
        config.requireAuthentication = true;
        addUser("mirth", "secret");
        int port = start();

        try (Raw raw = new Raw(port)) {
            expect("220", raw.reply());
            raw.cmd("EHLO c");
            String plain = Base64.getEncoder().encodeToString("\0nobody\0secret".getBytes(StandardCharsets.UTF_8));
            expect("535", raw.cmd("AUTH PLAIN " + plain));
        }
    }

    @Test
    public void afterThreeFailedLoginsTheConnectionIsClosed() throws Exception {
        config.requireAuthentication = true;
        addUser("mirth", "secret");
        int port = start();

        try (Raw raw = new Raw(port)) {
            expect("220", raw.reply());
            raw.cmd("EHLO c");
            String bad = Base64.getEncoder().encodeToString("\0mirth\0wrong".getBytes(StandardCharsets.UTF_8));
            expect("535", raw.cmd("AUTH PLAIN " + bad));
            expect("535", raw.cmd("AUTH PLAIN " + bad));
            raw.send("AUTH PLAIN " + bad + "\r\n");
            expect("535", raw.reply());
            expect("421", raw.reply());
            assertTrue(raw.isClosedByServer());
        }
    }

    @Test
    public void passwordsMayContainColonsAndNonAscii() throws Exception {
        config.requireAuthentication = true;
        addUser("mirth", "p:aéss");
        int port = start();

        try (Raw raw = new Raw(port)) {
            expect("220", raw.reply());
            raw.cmd("EHLO c");
            String plain = Base64.getEncoder().encodeToString("\0mirth\0p:aéss".getBytes(StandardCharsets.UTF_8));
            expect("235", raw.cmd("AUTH PLAIN " + plain));
        }
    }

    @Test
    public void authPlainAcceptsTheCredentialsInASecondStep() throws Exception {
        config.requireAuthentication = true;
        addUser("mirth", "secret");
        int port = start();

        try (Raw raw = new Raw(port)) {
            expect("220", raw.reply());
            raw.cmd("EHLO c");
            expect("334", raw.cmd("AUTH PLAIN"));
            expect("235", raw.cmd(Base64.getEncoder().encodeToString("\0mirth\0secret".getBytes(StandardCharsets.UTF_8))));
            expect("503", raw.cmd("AUTH PLAIN"));
        }
    }

    @Test
    public void authLoginAsksForUserAndPassword() throws Exception {
        config.requireAuthentication = true;
        addUser("mirth", "secret");
        int port = start();

        try (Raw raw = new Raw(port)) {
            expect("220", raw.reply());
            raw.cmd("EHLO c");
            assertEquals("334 VXNlcm5hbWU6", raw.cmd("AUTH LOGIN"));
            assertEquals("334 UGFzc3dvcmQ6", raw.cmd(Base64.getEncoder().encodeToString("mirth".getBytes())));
            expect("235", raw.cmd(Base64.getEncoder().encodeToString("secret".getBytes())));
        }
    }

    @Test
    public void authCanBeCanceledAndGarbageIsRefused() throws Exception {
        config.requireAuthentication = true;
        addUser("mirth", "secret");
        int port = start();

        try (Raw raw = new Raw(port)) {
            expect("220", raw.reply());
            raw.cmd("EHLO c");
            expect("334", raw.cmd("AUTH PLAIN"));
            expect("501", raw.cmd("*"));
            expect("501", raw.cmd("AUTH PLAIN !!!not-base64!!!"));
            expect("504", raw.cmd("AUTH CRAM-MD5"));
        }
    }

    @Test
    public void anOptionalLoginIsAllowedButNotRequired() throws Exception {
        config.requireAuthentication = false;
        addUser("mirth", "secret");
        int port = start();

        send(port, mail(session(port, new Properties()), "a@b.nl", "Anon", "x", "c@d.nl"));
        assertEquals(1, received.size());
        assertNull(received.get(0).authenticatedUser);
    }

    @Test
    public void authIsNotOfferedWithoutUsers() throws Exception {
        int port = start();
        try (Raw raw = new Raw(port)) {
            expect("220", raw.reply());
            String ehlo = raw.cmd("EHLO c");
            assertFalse(ehlo, ehlo.contains("AUTH"));
            expect("502", raw.cmd("AUTH PLAIN"));
        }
    }

    @Test
    public void ehloListsTheCapabilities() throws Exception {
        config.requireAuthentication = true;
        config.maxMessageBytes = 1234567;
        addUser("mirth", "secret");
        int port = start();

        try (Raw raw = new Raw(port)) {
            expect("220", raw.reply());
            String ehlo = raw.cmd("EHLO client.example");
            assertTrue(ehlo, ehlo.startsWith("250-test.local greets 127.0.0.1"));
            assertTrue(ehlo, ehlo.contains("250-SIZE 1234567"));
            assertTrue(ehlo, ehlo.contains("250-8BITMIME"));
            assertTrue(ehlo, ehlo.contains("250-PIPELINING"));
            assertTrue(ehlo, ehlo.contains("250-ENHANCEDSTATUSCODES"));
            assertTrue(ehlo, ehlo.contains("AUTH PLAIN LOGIN"));
            assertFalse(ehlo, ehlo.contains("STARTTLS"));
            String[] lines = ehlo.split("\n");
            assertTrue(lines[lines.length - 1], lines[lines.length - 1].startsWith("250 "));
        }
    }

    // ---- limits

    @Test
    public void aTooBigMessageIsRefusedAndTheConnectionStaysUsable() throws Exception {
        config.maxMessageBytes = 1024;
        int port = start();

        try (Raw raw = new Raw(port)) {
            expect("220", raw.reply());
            raw.cmd("EHLO c");
            raw.cmd("MAIL FROM:<a@b.nl>");
            raw.cmd("RCPT TO:<c@d.nl>");
            raw.cmd("DATA");
            StringBuilder big = new StringBuilder();
            for (int i = 0; i < 100; i++) {
                big.append("0123456789012345678901234567890123456789012345678901234567890123456789\r\n");
            }
            raw.send(big + ".\r\n");
            expect("552", raw.reply());
            assertTrue(received.isEmpty());

            // a small one afterwards works
            raw.cmd("MAIL FROM:<a@b.nl>");
            raw.cmd("RCPT TO:<c@d.nl>");
            raw.cmd("DATA");
            raw.send("small\r\n.\r\n");
            expect("250", raw.reply());
        }
        assertEquals(1, received.size());
    }

    @Test
    public void aSingleEnormousLineIsNotKeptInMemory() throws Exception {
        config.maxMessageBytes = 1024;
        int port = start();

        try (Raw raw = new Raw(port)) {
            expect("220", raw.reply());
            raw.cmd("EHLO c");
            raw.cmd("MAIL FROM:<a@b.nl>");
            raw.cmd("RCPT TO:<c@d.nl>");
            raw.cmd("DATA");
            byte[] line = new byte[3 * 1024 * 1024];
            java.util.Arrays.fill(line, (byte) 'x');
            raw.out.write(line);
            raw.send("\r\n.\r\n");
            expect("552", raw.reply());
        }
    }

    @Test
    public void theSizeParameterIsCheckedAtMailFrom() throws Exception {
        config.maxMessageBytes = 1024;
        int port = start();

        try (Raw raw = new Raw(port)) {
            expect("220", raw.reply());
            raw.cmd("EHLO c");
            expect("552", raw.cmd("MAIL FROM:<a@b.nl> SIZE=999999"));
            expect("250", raw.cmd("MAIL FROM:<a@b.nl> SIZE=100"));
            expect("250", raw.cmd("RSET"));
            expect("501", raw.cmd("MAIL FROM:<a@b.nl> SIZE=abc"));
        }
    }

    @Test
    public void noLimitWhenMaxSizeIsZero() throws Exception {
        config.maxMessageBytes = 0;
        int port = start();
        try (Raw raw = new Raw(port)) {
            expect("220", raw.reply());
            String ehlo = raw.cmd("EHLO c");
            assertFalse(ehlo, ehlo.contains("SIZE"));
            raw.cmd("MAIL FROM:<a@b.nl> SIZE=999999999");
            raw.cmd("RCPT TO:<c@d.nl>");
            raw.cmd("DATA");
            raw.send(new String(new char[200000]).replace('\0', 'y') + "\r\n.\r\n");
            expect("250", raw.reply());
        }
        assertEquals(200002, received.get(0).data.length);
    }

    @Test
    public void tooManyRecipientsAreRefused() throws Exception {
        config.maxRecipients = 2;
        int port = start();
        try (Raw raw = new Raw(port)) {
            expect("220", raw.reply());
            raw.cmd("EHLO c");
            raw.cmd("MAIL FROM:<a@b.nl>");
            expect("250", raw.cmd("RCPT TO:<one@d.nl>"));
            expect("250", raw.cmd("RCPT TO:<two@d.nl>"));
            expect("452", raw.cmd("RCPT TO:<three@d.nl>"));
            raw.cmd("DATA");
            raw.send("x\r\n.\r\n");
            expect("250", raw.reply());
        }
        assertEquals(2, received.get(0).recipients.size());
    }

    @Test
    public void connectionsBeyondTheMaximumAreTurnedAway() throws Exception {
        config.maxConnections = 1;
        int port = start();

        try (Raw first = new Raw(port)) {
            expect("220", first.reply());
            try (Raw second = new Raw(port)) {
                expect("421", second.reply());
                assertTrue(second.isClosedByServer());
            }
            expect("221", first.cmd("QUIT"));
        }

        // the slot is free again
        Thread.sleep(200);
        try (Raw third = new Raw(port)) {
            expect("220", third.reply());
        }
    }

    @Test
    public void aSilentClientIsDisconnected() throws Exception {
        config.timeoutMillis = 300;
        int port = start();
        try (Raw raw = new Raw(port)) {
            expect("220", raw.reply());
            Thread.sleep(700);
            expect("421", raw.reply());
            assertTrue(raw.isClosedByServer());
        }
    }

    @Test
    public void tooManyMistakesEndTheSession() throws Exception {
        int port = start();
        try (Raw raw = new Raw(port)) {
            expect("220", raw.reply());
            String reply = null;
            for (int i = 0; i < 25; i++) {
                reply = raw.cmd("FROB");
                if (reply == null || reply.startsWith("421")) {
                    break;
                }
                expect("500", reply);
            }
            expect("421", reply);
        }
    }

    @Test
    public void aCommandLineThatIsTooLongIsRefused() throws Exception {
        int port = start();
        try (Raw raw = new Raw(port)) {
            expect("220", raw.reply());
            expect("500", raw.cmd("NOOP " + new String(new char[5000]).replace('\0', 'z')));
            expect("250", raw.cmd("NOOP"));
        }
    }

    // ---- protocol

    @Test
    public void commandsMustComeInTheRightOrder() throws Exception {
        int port = start();
        try (Raw raw = new Raw(port)) {
            expect("220", raw.reply());
            expect("503", raw.cmd("RCPT TO:<c@d.nl>"));
            expect("503", raw.cmd("DATA"));
            expect("250", raw.cmd("MAIL FROM:<a@b.nl>"));
            expect("503", raw.cmd("MAIL FROM:<x@y.nl>"));
            expect("503", raw.cmd("DATA"));
            expect("250", raw.cmd("RCPT TO:<c@d.nl>"));
            expect("250", raw.cmd("RSET"));
            expect("503", raw.cmd("DATA"));
        }
    }

    @Test
    public void syntaxErrorsGetA501() throws Exception {
        int port = start();
        try (Raw raw = new Raw(port)) {
            expect("220", raw.reply());
            expect("501", raw.cmd("HELO"));
            expect("501", raw.cmd("MAIL"));
            expect("501", raw.cmd("MAIL TO:<a@b.nl>"));
            raw.cmd("MAIL FROM:<a@b.nl>");
            expect("501", raw.cmd("RCPT FROM:<a@b.nl>"));
            expect("501", raw.cmd("RCPT TO:<>"));
        }
    }

    @Test
    public void miscellaneousCommands() throws Exception {
        int port = start();
        try (Raw raw = new Raw(port)) {
            expect("220", raw.reply());
            expect("250", raw.cmd("HELO client"));
            expect("250", raw.cmd("NOOP"));
            expect("252", raw.cmd("VRFY someone"));
            expect("214", raw.cmd("HELP"));
            expect("500", raw.cmd("BLAH"));
            expect("500", raw.cmd(""));
            expect("250", raw.cmd("noop"));
            expect("221", raw.cmd("QUIT"));
            assertTrue(raw.isClosedByServer());
        }
    }

    @Test
    public void addressesKeepTheirCaseAndBouncesHaveAnEmptySender() throws Exception {
        int port = start();
        try (Raw raw = new Raw(port)) {
            expect("220", raw.reply());
            raw.cmd("EHLO c");
            expect("250", raw.cmd("MAIL FROM:<>"));
            expect("250", raw.cmd("RCPT TO:<Jan.De.Vries@Bedrijf.NL>"));
            expect("250", raw.cmd("RCPT TO: <  spaced@x.nl >"));
            raw.cmd("DATA");
            raw.send("x\r\n.\r\n");
            expect("250", raw.reply());
        }
        assertEquals("", received.get(0).mailFrom);
        assertEquals("Jan.De.Vries@Bedrijf.NL", received.get(0).recipients.get(0));
        assertEquals("spaced@x.nl", received.get(0).recipients.get(1));
    }

    @Test
    public void addressesWithoutAngleBracketsAreAccepted() throws Exception {
        int port = start();
        try (Raw raw = new Raw(port)) {
            expect("220", raw.reply());
            raw.cmd("EHLO c");
            expect("250", raw.cmd("MAIL FROM:a@b.nl"));
            expect("250", raw.cmd("RCPT TO:c@d.nl"));
        }
    }

    @Test
    public void theEnvelopeIsResetAfterEveryMessage() throws Exception {
        int port = start();
        try (Raw raw = new Raw(port)) {
            expect("220", raw.reply());
            raw.cmd("EHLO client.example");
            raw.cmd("MAIL FROM:<a@b.nl>");
            raw.cmd("RCPT TO:<one@d.nl>");
            raw.cmd("DATA");
            raw.send("first\r\n.\r\n");
            expect("250", raw.reply());
            raw.cmd("MAIL FROM:<x@y.nl>");
            raw.cmd("RCPT TO:<two@d.nl>");
            raw.cmd("DATA");
            raw.send("second\r\n.\r\n");
            expect("250", raw.reply());
        }
        assertEquals(Collections.singletonList("one@d.nl"), received.get(0).recipients);
        assertEquals(Collections.singletonList("two@d.nl"), received.get(1).recipients);
        assertEquals("client.example", received.get(1).helo);
    }

    @Test
    public void pipelinedCommandsAreAnswered() throws Exception {
        int port = start();
        try (Raw raw = new Raw(port)) {
            expect("220", raw.reply());
            raw.send("EHLO c\r\nMAIL FROM:<a@b.nl>\r\nRCPT TO:<c@d.nl>\r\nDATA\r\n");
            expect("250", raw.reply());
            expect("250", raw.reply());
            expect("250", raw.reply());
            expect("354", raw.reply());
            raw.send("pipelined\r\n.\r\nQUIT\r\n");
            expect("250", raw.reply());
            expect("221", raw.reply());
        }
        assertEquals(1, received.size());
    }

    @Test
    public void theGreetingUsesTheConfiguredName() throws Exception {
        int port = start();
        try (Raw raw = new Raw(port)) {
            assertEquals("220 test.local ESMTP ready", raw.reply());
        }
    }

    // ---- TLS

    private File keystore() throws Exception {
        File file = File.createTempFile("smtp-receiver-test", ".p12");
        file.delete();
        file.deleteOnExit();
        String keytool = new File(System.getProperty("java.home"), "bin/keytool").getPath();
        Process p = new ProcessBuilder(keytool, "-genkeypair", "-alias", "test", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2", "-dname", "CN=localhost", "-ext", "san=ip:127.0.0.1,dns:localhost", "-keystore", file.getPath(), "-storetype", "PKCS12", "-storepass", "changeit", "-keypass", "changeit").redirectErrorStream(true).start();
        byte[] output = new byte[8192];
        int n = p.getInputStream().read(output);
        assertEquals("keytool failed: " + new String(output, 0, Math.max(0, n)), 0, p.waitFor());
        return file;
    }

    private SSLContext sslContext(File keystore) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = new FileInputStream(keystore)) {
            ks.load(in, "changeit".toCharArray());
        }
        KeyManagerFactory f = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        f.init(ks, "changeit".toCharArray());
        SSLContext c = SSLContext.getInstance("TLS");
        c.init(f.getKeyManagers(), null, null);
        return c;
    }

    @Test
    public void starttlsUpgradesTheConnection() throws Exception {
        config.tlsMode = SmtpConfig.TlsMode.STARTTLS;
        config.sslContext = sslContext(keystore());
        int port = start();

        Properties p = new Properties();
        p.put("mail.smtp.starttls.enable", "true");
        p.put("mail.smtp.starttls.required", "true");
        p.put("mail.smtp.ssl.trust", "*");
        send(port, mail(session(port, p), "a@b.nl", "Secure", "x", "c@d.nl"));

        assertEquals(1, received.size());
        assertTrue(received.get(0).tls);
    }

    @Test
    public void starttlsIsOfferedOnlyBeforeTlsAndLoginOnlyAfter() throws Exception {
        config.tlsMode = SmtpConfig.TlsMode.STARTTLS;
        config.sslContext = sslContext(keystore());
        config.requireAuthentication = true;
        addUser("mirth", "secret");
        int port = start();

        try (Raw raw = new Raw(port)) {
            expect("220", raw.reply());
            String ehlo = raw.cmd("EHLO c");
            assertTrue(ehlo, ehlo.contains("STARTTLS"));
            assertFalse(ehlo, ehlo.contains("AUTH"));
            expect("538", raw.cmd("AUTH PLAIN " + Base64.getEncoder().encodeToString("\0mirth\0secret".getBytes())));
            expect("530", raw.cmd("MAIL FROM:<a@b.nl>"));
        }
    }

    @Test
    public void loginOverStarttls() throws Exception {
        config.tlsMode = SmtpConfig.TlsMode.STARTTLS;
        config.sslContext = sslContext(keystore());
        config.requireAuthentication = true;
        addUser("mirth", "secret");
        int port = start();

        Properties p = new Properties();
        p.put("mail.smtp.starttls.enable", "true");
        p.put("mail.smtp.starttls.required", "true");
        p.put("mail.smtp.ssl.trust", "*");
        p.put("mail.smtp.auth", "true");
        Session s = session(port, p);
        MimeMessage m = mail(s, "a@b.nl", "Secure login", "x", "c@d.nl");
        Transport t = s.getTransport("smtp");
        t.connect("mirth", "secret");
        try {
            t.sendMessage(m, m.getAllRecipients());
        } finally {
            t.close();
        }

        assertEquals("mirth", received.get(0).authenticatedUser);
        assertTrue(received.get(0).tls);
    }

    @Test
    public void commandsSentBehindStarttlsAreNotTrusted() throws Exception {
        config.tlsMode = SmtpConfig.TlsMode.STARTTLS;
        config.sslContext = sslContext(keystore());
        int port = start();

        try (Raw raw = new Raw(port)) {
            expect("220", raw.reply());
            raw.cmd("EHLO c");
            raw.send("STARTTLS\r\nNOOP\r\n");
            expect("501", raw.reply());
            assertTrue(raw.isClosedByServer());
        }
    }

    @Test
    public void starttlsWithoutTlsConfiguredIsRefused() throws Exception {
        int port = start();
        try (Raw raw = new Raw(port)) {
            expect("220", raw.reply());
            String ehlo = raw.cmd("EHLO c");
            assertFalse(ehlo, ehlo.contains("STARTTLS"));
            expect("502", raw.cmd("STARTTLS"));
        }
    }

    @Test
    public void implicitTlsIsEncryptedFromTheFirstByte() throws Exception {
        config.tlsMode = SmtpConfig.TlsMode.IMPLICIT;
        config.sslContext = sslContext(keystore());
        int port = start();

        Properties p = new Properties();
        p.put("mail.smtps.host", "127.0.0.1");
        p.put("mail.smtps.port", String.valueOf(port));
        p.put("mail.smtps.ssl.trust", "*");
        p.put("mail.smtps.timeout", "5000");
        p.put("mail.smtps.connectiontimeout", "5000");
        Session s = Session.getInstance(p);
        MimeMessage m = mail(s, "a@b.nl", "Implicit", "x", "c@d.nl");
        Transport t = s.getTransport("smtps");
        t.connect();
        try {
            t.sendMessage(m, m.getAllRecipients());
        } finally {
            t.close();
        }

        assertEquals(1, received.size());
        assertTrue(received.get(0).tls);
    }

    // ---- lifecycle

    @Test
    public void stoppingClosesThePortAndIdleConnections() throws Exception {
        int port = start();
        Raw idle = new Raw(port);
        expect("220", idle.reply());

        long begin = System.currentTimeMillis();
        server.stop(5000);
        // an idle client does not hold the stop up: it is told the service is going down
        assertTrue("stop took too long", System.currentTimeMillis() - begin < 2000);
        expect("421", idle.reply());
        assertTrue(idle.isClosedByServer());
        idle.close();

        try {
            new Socket("127.0.0.1", port).close();
            fail("the port should be closed");
        } catch (IOException expected) {
            // fine
        }
        server = null;
    }

    @Test
    public void stopWaitsForAMessageThatIsBeingProcessed() throws Exception {
        java.util.concurrent.CountDownLatch gate = new java.util.concurrent.CountDownLatch(1);
        handlerGate = gate;
        int port = start();
        try (Raw raw = new Raw(port)) {
            expect("220", raw.reply());
            raw.cmd("EHLO c");
            raw.cmd("MAIL FROM:<a@b.nl>");
            raw.cmd("RCPT TO:<c@d.nl>");
            raw.cmd("DATA");
            raw.send("busy\r\n.\r\n");

            Thread stopper = new Thread(() -> server.stop(5000));
            stopper.start();
            Thread.sleep(400);
            assertTrue("stop must wait for the message that is being processed", stopper.isAlive());

            // the channel finishes: the client still gets its answer, and only then the connection is closed
            gate.countDown();
            expect("250", raw.reply());
            expect("421", raw.reply());
            assertTrue(raw.isClosedByServer());
            stopper.join(5000);
            assertFalse(stopper.isAlive());
        }
        assertEquals(1, received.size());
        server = null;
    }

    @Test
    public void stopGivesUpOnAMessageThatTakesTooLong() throws Exception {
        handlerGate = new java.util.concurrent.CountDownLatch(1);
        int port = start();
        try (Raw raw = new Raw(port)) {
            expect("220", raw.reply());
            raw.cmd("EHLO c");
            raw.cmd("MAIL FROM:<a@b.nl>");
            raw.cmd("RCPT TO:<c@d.nl>");
            raw.cmd("DATA");
            raw.send("stuck\r\n.\r\n");
            Thread.sleep(200);

            long begin = System.currentTimeMillis();
            server.stop(500);
            assertTrue("stop must not wait forever", System.currentTimeMillis() - begin < 4000);
            assertTrue(raw.isClosedByServer());
        }
        server = null;
    }

    @Test
    public void haltClosesEverythingAtOnce() throws Exception {
        int port = start();
        try (Raw raw = new Raw(port)) {
            expect("220", raw.reply());
            server.halt();
            assertTrue(raw.isClosedByServer());
        }
        server = null;
    }

    @Test
    public void theServerCanBeStartedAgainOnTheSamePort() throws Exception {
        int port = start();
        server.stop(100);
        config.port = port;
        server = null;
        start();
        try (Raw raw = new Raw(port)) {
            expect("220", raw.reply());
        }
    }

    @Test
    public void portZeroPicksAFreePort() throws Exception {
        int port = start();
        assertTrue(port > 0);
        assertEquals(port, server.getPort());
    }

    @Test
    public void aPortThatIsInUseFailsToStart() throws Exception {
        int port = start();
        SmtpConfig second = new SmtpConfig();
        second.bindHost = "127.0.0.1";
        second.port = port;
        SmtpServer other = new SmtpServer(second, m -> SmtpReply.ok("x"), null);
        try {
            other.start();
            fail("expected a bind error");
        } catch (java.net.BindException expected) {
            // fine
        }
    }

    @Test
    public void activeConnectionsAreCounted() throws Exception {
        int port = start();
        try (Raw a = new Raw(port); Raw b = new Raw(port)) {
            expect("220", a.reply());
            expect("220", b.reply());
            assertEquals(2, server.activeConnections());
        }
        for (int i = 0; i < 50 && server.activeConnections() > 0; i++) {
            Thread.sleep(50);
        }
        assertEquals(0, server.activeConnections());
    }

    @Test
    public void aMailWithAnAttachmentBecomesJsonAndXml() throws Exception {
        int port = start();
        Session session = session(port, new Properties());
        MimeMessage m = mail(session, "Jan <jan@bedrijf.nl>", "Résumé", "Hello €", "a@x.nl");
        jakarta.mail.internet.MimeBodyPart body = new jakarta.mail.internet.MimeBodyPart();
        body.setText("Hello €", "UTF-8");
        jakarta.mail.internet.MimeBodyPart file = new jakarta.mail.internet.MimeBodyPart();
        file.setDataHandler(new jakarta.activation.DataHandler(new jakarta.mail.util.ByteArrayDataSource(new byte[] { 0, 1, 2, (byte) 255 }, "application/octet-stream")));
        file.setFileName("data.bin");
        jakarta.mail.internet.MimeMultipart multi = new jakarta.mail.internet.MimeMultipart();
        multi.addBodyPart(body);
        multi.addBodyPart(file);
        m.setContent(multi);
        send(port, m);

        assertEquals(1, received.size());
        String json = SmtpMessageFormatter.format(received.get(0), "JSON", StandardCharsets.UTF_8, true);
        com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
        assertEquals("Résumé", o.get("subject").getAsString());
        assertEquals("jan@bedrijf.nl", o.getAsJsonArray("from").get(0).getAsJsonObject().get("address").getAsString());
        assertTrue(o.get("text").getAsString().contains("Hello €"));
        com.google.gson.JsonObject a = o.getAsJsonArray("attachments").get(0).getAsJsonObject();
        assertEquals("data.bin", a.get("filename").getAsString());
        assertEquals(4, a.get("size").getAsInt());
        assertEquals(java.util.Arrays.toString(new byte[] { 0, 1, 2, (byte) 255 }), java.util.Arrays.toString(Base64.getDecoder().decode(a.get("content").getAsString())));

        String xml = SmtpMessageFormatter.format(received.get(0), "XML", StandardCharsets.UTF_8, true);
        org.w3c.dom.Document doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new java.io.ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        assertEquals("Résumé", doc.getElementsByTagName("subject").item(0).getTextContent());
        assertEquals("data.bin", ((org.w3c.dom.Element) doc.getElementsByTagName("attachment").item(0)).getAttribute("filename"));
    }
}
