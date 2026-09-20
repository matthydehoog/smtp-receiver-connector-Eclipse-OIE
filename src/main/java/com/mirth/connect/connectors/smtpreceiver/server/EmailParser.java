/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.mirth.connect.connectors.smtpreceiver.server;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.mail.BodyPart;
import jakarta.mail.Header;
import jakarta.mail.MessagingException;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.internet.MimePart;
import jakarta.mail.internet.MimePartDataSource;
import jakarta.mail.internet.MimeUtility;

/**
 * Takes a mail apart with Jakarta Mail. Parts are read through their input streams and never through
 * getContent(): the engine ships an older mail library whose mailcap handlers clash with the one bundled
 * with this extension (see the Email Reader).
 */
public final class EmailParser {
    private static final Session SESSION = Session.getInstance(new Properties());

    private EmailParser() {}

    /**
     * @param fallbackCharset used for text parts that do not say which character set they are in
     * @param withContent whether attachments carry their bytes
     * @return the mail; never throws. A mail that cannot be read has parseError set and its raw text in text
     */
    public static ParsedEmail parse(byte[] data, Charset fallbackCharset, boolean withContent) {
        try {
            return parseStrict(data, fallbackCharset, withContent);
        } catch (Exception e) {
            ParsedEmail broken = new ParsedEmail();
            broken.parseError = e.toString();
            broken.text = new String(data, fallbackCharset);
            return broken;
        }
    }

    private static ParsedEmail parseStrict(byte[] data, Charset fallbackCharset, boolean withContent) throws MessagingException, IOException {
        MimeMessage message = new MimeMessage(SESSION, new ByteArrayInputStream(data));
        ParsedEmail mail = new ParsedEmail();

        mail.messageId = message.getMessageID();
        mail.subject = message.getSubject();
        Date sent = message.getSentDate();
        mail.date = sent == null ? null : DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(sent.getTime()));

        addresses(message, "From", mail.from);
        addresses(message, "To", mail.to);
        addresses(message, "Cc", mail.cc);
        addresses(message, "Reply-To", mail.replyTo);

        Enumeration<Header> headers = message.getAllHeaders();
        while (headers.hasMoreElements()) {
            Header h = headers.nextElement();
            mail.headers.add(new ParsedEmail.Header(h.getName(), decodeHeader(h.getValue())));
        }

        StringBuilder text = new StringBuilder();
        StringBuilder html = new StringBuilder();
        walk(message, text, html, mail.attachments, fallbackCharset, withContent);

        mail.html = normalizeLineEndings(html.toString());
        String plain = normalizeLineEndings(text.toString());
        // A mail with only HTML still gets a readable text.
        mail.text = plain.trim().isEmpty() && !mail.html.trim().isEmpty() ? HtmlText.toText(mail.html) : plain;
        return mail;
    }

    private static void walk(Part part, StringBuilder text, StringBuilder html, List<ParsedEmail.Attachment> attachments, Charset fallbackCharset, boolean withContent) throws MessagingException, IOException {
        if (part.isMimeType("multipart/*")) {
            MimeMultipart multipart = new MimeMultipart(new MimePartDataSource((MimePart) part));
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart child = multipart.getBodyPart(i);
                walk(child, text, html, attachments, fallbackCharset, withContent);
            }
            return;
        }

        String filename = decodeFilename(part.getFileName());
        String disposition = part.getDisposition();
        boolean attached = Part.ATTACHMENT.equalsIgnoreCase(disposition) || !filename.isEmpty();

        boolean plain = part.isMimeType("text/plain");
        boolean isHtml = part.isMimeType("text/html");
        if ((plain || isHtml) && !attached) {
            String content = readText(part, fallbackCharset);
            StringBuilder target = plain ? text : html;
            if (target.length() > 0) {
                target.append('\n');
            }
            target.append(content);
            return;
        }

        byte[] bytes = readBytes(part);
        String[] contentId = part.getHeader("Content-ID");
        attachments.add(new ParsedEmail.Attachment(
                filename,
                baseType(part),
                bytes.length,
                contentId == null || contentId.length == 0 ? "" : contentId[0].trim().replaceAll("^<|>$", ""),
                Part.INLINE.equalsIgnoreCase(disposition) || (disposition == null && contentId != null) ? Part.INLINE : Part.ATTACHMENT,
                withContent ? bytes : null));
    }

    private static void addresses(MimeMessage message, String header, List<ParsedEmail.Address> into) throws MessagingException {
        String[] values = message.getHeader(header);
        if (values == null || values.length == 0) {
            return;
        }
        String joined = String.join(",", values);
        try {
            for (InternetAddress a : InternetAddress.parseHeader(joined, false)) {
                into.add(new ParsedEmail.Address(a.getPersonal(), a.getAddress()));
            }
        } catch (AddressException e) {
            // Keep what the sender wrote rather than lose it.
            into.add(new ParsedEmail.Address(null, decodeHeader(joined)));
        }
    }

    private static String decodeHeader(String value) {
        if (value == null) {
            return "";
        }
        try {
            return MimeUtility.decodeText(MimeUtility.unfold(value));
        } catch (Exception e) {
            return value;
        }
    }

    private static String decodeFilename(String filename) {
        if (filename == null) {
            return "";
        }
        try {
            return MimeUtility.decodeText(filename);
        } catch (Exception e) {
            return filename;
        }
    }

    private static String baseType(Part part) {
        try {
            return new ContentType(part.getContentType()).getBaseType().toLowerCase();
        } catch (Exception e) {
            return "application/octet-stream";
        }
    }

    private static byte[] readBytes(Part part) throws MessagingException, IOException {
        try (InputStream in = part.getInputStream()) {
            return in.readAllBytes();
        }
    }

    private static String readText(Part part, Charset fallback) throws MessagingException, IOException {
        return new String(readBytes(part), charsetOf(part, fallback));
    }

    private static Charset charsetOf(Part part, Charset fallback) {
        try {
            String contentType = part.getContentType();
            String charset = contentType == null ? null : new ContentType(contentType).getParameter("charset");
            if (charset != null) {
                return Charset.forName(MimeUtility.javaCharset(charset));
            }
        } catch (Exception e) {
            // unknown or invalid: use the fallback
        }
        return fallback == null ? StandardCharsets.UTF_8 : fallback;
    }

    private static final Pattern LINE_ENDINGS = Pattern.compile("\r\n?");

    private static String normalizeLineEndings(String s) {
        Matcher m = LINE_ENDINGS.matcher(s);
        return m.find() ? m.replaceAll("\n") : s;
    }
}
