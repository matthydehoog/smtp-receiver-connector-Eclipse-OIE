/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.mirth.connect.connectors.smtpreceiver.server;

import java.nio.charset.Charset;
import java.util.Base64;
import java.util.List;

/** Turns a received message into the text that goes into the channel: plain text, JSON or XML. */
public final class SmtpMessageFormatter {
    public static final String FORMAT_RFC822 = "RFC822";
    public static final String FORMAT_JSON = "JSON";
    public static final String FORMAT_XML = "XML";

    private SmtpMessageFormatter() {}

    /**
     * @param format RFC822 (the mail as it was sent), JSON or XML (the mail taken apart, with the envelope)
     * @param charset how the bytes become text; for JSON and XML only for parts that do not name their own
     * @param attachmentContent whether JSON and XML carry the attachments themselves (Base64) or only their description
     */
    public static String format(SmtpMessage message, String format, Charset charset, boolean attachmentContent) {
        if (FORMAT_JSON.equals(format)) {
            return json(message, charset, attachmentContent);
        }
        if (FORMAT_XML.equals(format)) {
            return xml(message, charset, attachmentContent);
        }
        return rfc822(message, charset);
    }

    /** The mail exactly as it was sent: headers, an empty line and the body, with CRLF line endings. */
    public static String rfc822(SmtpMessage message, Charset charset) {
        return message.dataAsString(charset);
    }

    // ---- JSON

    /**
     * <pre>
     * {
     *   "envelope": {"mailFrom": "a@b.nl", "rcptTo": ["c@d.nl"], "user": "", "authentication": "NA",
     *                "clientIp": "10.0.0.5", "helo": "client.example", "tls": false},
     *   "messageId": "...", "date": "2026-09-20T13:00:00Z", "subject": "Hi",
     *   "from": [{"name": "A", "address": "a@b.nl"}], "to": [...], "cc": [...], "replyTo": [...],
     *   "headers": [{"name": "Subject", "value": "Hi"}, ...],
     *   "text": "...", "html": "...",
     *   "attachments": [{"filename": "a.pdf", "contentType": "application/pdf", "size": 123,
     *                    "contentId": "", "disposition": "attachment", "content": "Base64..."}]
     * }
     * </pre>
     */
    public static String json(SmtpMessage message, Charset charset, boolean attachmentContent) {
        ParsedEmail mail = EmailParser.parse(message.data, charset, attachmentContent);
        StringBuilder sb = new StringBuilder(message.data.length + 1024);

        sb.append("{\n");
        sb.append("  \"envelope\": {\n");
        sb.append("    \"mailFrom\": ");
        jsonString(sb, message.mailFrom);
        sb.append(",\n    \"rcptTo\": [");
        for (int i = 0; i < message.recipients.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            jsonString(sb, message.recipients.get(i));
        }
        sb.append("],\n    \"user\": ");
        jsonString(sb, message.authenticatedUser);
        sb.append(",\n    \"authentication\": ");
        jsonString(sb, message.authenticatedUser == null ? "NA" : "SUCCESS");
        sb.append(",\n    \"clientIp\": ");
        jsonString(sb, message.clientAddress);
        sb.append(",\n    \"helo\": ");
        jsonString(sb, message.helo);
        sb.append(",\n    \"tls\": ").append(message.tls).append("\n  },\n");

        sb.append("  \"messageId\": ");
        jsonString(sb, mail.messageId);
        sb.append(",\n  \"date\": ");
        jsonString(sb, mail.date);
        sb.append(",\n  \"subject\": ");
        jsonString(sb, mail.subject);
        sb.append(",\n");

        jsonAddresses(sb, "from", mail.from);
        jsonAddresses(sb, "to", mail.to);
        jsonAddresses(sb, "cc", mail.cc);
        jsonAddresses(sb, "replyTo", mail.replyTo);

        sb.append("  \"headers\": [");
        for (int i = 0; i < mail.headers.size(); i++) {
            sb.append(i > 0 ? ",\n    " : "\n    ").append("{\"name\": ");
            jsonString(sb, mail.headers.get(i).name);
            sb.append(", \"value\": ");
            jsonString(sb, mail.headers.get(i).value);
            sb.append('}');
        }
        sb.append(mail.headers.isEmpty() ? "],\n" : "\n  ],\n");

        sb.append("  \"text\": ");
        jsonString(sb, mail.text);
        sb.append(",\n  \"html\": ");
        jsonString(sb, mail.html);
        sb.append(",\n  \"attachments\": [");
        for (int i = 0; i < mail.attachments.size(); i++) {
            ParsedEmail.Attachment a = mail.attachments.get(i);
            sb.append(i > 0 ? ",\n    {" : "\n    {");
            sb.append("\"filename\": ");
            jsonString(sb, a.filename);
            sb.append(", \"contentType\": ");
            jsonString(sb, a.contentType);
            sb.append(", \"size\": ").append(a.size);
            sb.append(", \"contentId\": ");
            jsonString(sb, a.contentId);
            sb.append(", \"disposition\": ");
            jsonString(sb, a.disposition);
            if (a.content != null) {
                sb.append(", \"content\": ");
                jsonString(sb, Base64.getEncoder().encodeToString(a.content));
            }
            sb.append('}');
        }
        sb.append(mail.attachments.isEmpty() ? "]" : "\n  ]");
        if (mail.parseError != null) {
            sb.append(",\n  \"parseError\": ");
            jsonString(sb, mail.parseError);
        }
        sb.append("\n}");
        return sb.toString();
    }

    private static void jsonAddresses(StringBuilder sb, String key, List<ParsedEmail.Address> list) {
        sb.append("  \"").append(key).append("\": [");
        for (int i = 0; i < list.size(); i++) {
            sb.append(i > 0 ? ", " : "").append("{\"name\": ");
            jsonString(sb, list.get(i).name);
            sb.append(", \"address\": ");
            jsonString(sb, list.get(i).address);
            sb.append('}');
        }
        sb.append("],\n");
    }

    private static void jsonString(StringBuilder sb, String value) {
        sb.append('"');
        String v = value == null ? "" : value;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    // ---- XML

    /**
     * The same content as {@link #json}, as
     * <pre>
     * &lt;email&gt;
     *   &lt;envelope&gt;&lt;mailFrom&gt;..&lt;/mailFrom&gt;&lt;rcptTo&gt;&lt;address&gt;..&lt;/address&gt;&lt;/rcptTo&gt;...&lt;/envelope&gt;
     *   &lt;messageId&gt;..&lt;/messageId&gt; &lt;date&gt;..&lt;/date&gt; &lt;subject&gt;..&lt;/subject&gt;
     *   &lt;from&gt;&lt;address name="A"&gt;a@b.nl&lt;/address&gt;&lt;/from&gt; ...
     *   &lt;headers&gt;&lt;header name="Subject"&gt;Hi&lt;/header&gt;...&lt;/headers&gt;
     *   &lt;text&gt;..&lt;/text&gt; &lt;html&gt;..&lt;/html&gt;
     *   &lt;attachments&gt;&lt;attachment filename=".." contentType=".." size=".." ..&gt;Base64&lt;/attachment&gt;&lt;/attachments&gt;
     * &lt;/email&gt;
     * </pre>
     */
    public static String xml(SmtpMessage message, Charset charset, boolean attachmentContent) {
        ParsedEmail mail = EmailParser.parse(message.data, charset, attachmentContent);
        StringBuilder sb = new StringBuilder(message.data.length + 1024);

        sb.append("<email>\n");
        sb.append("  <envelope>\n");
        xmlElement(sb, 4, "mailFrom", message.mailFrom);
        sb.append("    <rcptTo>");
        for (String recipient : message.recipients) {
            sb.append("<address>");
            xmlText(sb, recipient);
            sb.append("</address>");
        }
        sb.append("</rcptTo>\n");
        xmlElement(sb, 4, "user", message.authenticatedUser);
        xmlElement(sb, 4, "authentication", message.authenticatedUser == null ? "NA" : "SUCCESS");
        xmlElement(sb, 4, "clientIp", message.clientAddress);
        xmlElement(sb, 4, "helo", message.helo);
        xmlElement(sb, 4, "tls", String.valueOf(message.tls));
        sb.append("  </envelope>\n");

        xmlElement(sb, 2, "messageId", mail.messageId);
        xmlElement(sb, 2, "date", mail.date);
        xmlElement(sb, 2, "subject", mail.subject);
        xmlAddresses(sb, "from", mail.from);
        xmlAddresses(sb, "to", mail.to);
        xmlAddresses(sb, "cc", mail.cc);
        xmlAddresses(sb, "replyTo", mail.replyTo);

        if (mail.headers.isEmpty()) {
            sb.append("  <headers/>\n");
        } else {
            sb.append("  <headers>\n");
            for (ParsedEmail.Header h : mail.headers) {
                sb.append("    <header name=\"");
                xmlAttribute(sb, h.name);
                sb.append("\">");
                xmlText(sb, h.value);
                sb.append("</header>\n");
            }
            sb.append("  </headers>\n");
        }

        xmlElement(sb, 2, "text", mail.text);
        xmlElement(sb, 2, "html", mail.html);

        if (mail.attachments.isEmpty()) {
            sb.append("  <attachments/>\n");
        } else {
            sb.append("  <attachments>\n");
            for (ParsedEmail.Attachment a : mail.attachments) {
                sb.append("    <attachment filename=\"");
                xmlAttribute(sb, a.filename);
                sb.append("\" contentType=\"");
                xmlAttribute(sb, a.contentType);
                sb.append("\" size=\"").append(a.size).append("\" contentId=\"");
                xmlAttribute(sb, a.contentId);
                sb.append("\" disposition=\"");
                xmlAttribute(sb, a.disposition);
                sb.append("\">");
                if (a.content != null) {
                    sb.append(Base64.getEncoder().encodeToString(a.content));
                }
                sb.append("</attachment>\n");
            }
            sb.append("  </attachments>\n");
        }

        if (mail.parseError != null) {
            xmlElement(sb, 2, "parseError", mail.parseError);
        }
        sb.append("</email>");
        return sb.toString();
    }

    private static void xmlAddresses(StringBuilder sb, String name, List<ParsedEmail.Address> list) {
        if (list.isEmpty()) {
            sb.append("  <").append(name).append("/>\n");
            return;
        }
        sb.append("  <").append(name).append(">");
        for (ParsedEmail.Address a : list) {
            sb.append("<address name=\"");
            xmlAttribute(sb, a.name);
            sb.append("\">");
            xmlText(sb, a.address);
            sb.append("</address>");
        }
        sb.append("</").append(name).append(">\n");
    }

    private static void xmlElement(StringBuilder sb, int indent, String name, String value) {
        for (int i = 0; i < indent; i++) {
            sb.append(' ');
        }
        if (value == null || value.isEmpty()) {
            sb.append('<').append(name).append("/>\n");
            return;
        }
        sb.append('<').append(name).append('>');
        xmlText(sb, value);
        sb.append("</").append(name).append(">\n");
    }

    private static void xmlText(StringBuilder sb, String value) {
        String v = value == null ? "" : value;
        for (int i = 0; i < v.length(); ) {
            int cp = v.codePointAt(i);
            i += Character.charCount(cp);
            if (!isXmlChar(cp)) {
                continue; // a control character that XML 1.0 cannot hold
            }
            switch (cp) {
                case '&':
                    sb.append("&amp;");
                    break;
                case '<':
                    sb.append("&lt;");
                    break;
                case '>':
                    sb.append("&gt;");
                    break;
                case '\r':
                    sb.append("&#13;");
                    break;
                default:
                    sb.appendCodePoint(cp);
            }
        }
    }

    private static void xmlAttribute(StringBuilder sb, String value) {
        String v = value == null ? "" : value;
        for (int i = 0; i < v.length(); ) {
            int cp = v.codePointAt(i);
            i += Character.charCount(cp);
            if (!isXmlChar(cp)) {
                continue;
            }
            switch (cp) {
                case '&':
                    sb.append("&amp;");
                    break;
                case '<':
                    sb.append("&lt;");
                    break;
                case '>':
                    sb.append("&gt;");
                    break;
                case '"':
                    sb.append("&quot;");
                    break;
                case '\n':
                    sb.append("&#10;");
                    break;
                case '\r':
                    sb.append("&#13;");
                    break;
                case '\t':
                    sb.append("&#9;");
                    break;
                default:
                    sb.appendCodePoint(cp);
            }
        }
    }

    private static boolean isXmlChar(int cp) {
        return cp == 0x9 || cp == 0xA || cp == 0xD || (cp >= 0x20 && cp <= 0xD7FF) || (cp >= 0xE000 && cp <= 0xFFFD) || (cp >= 0x10000 && cp <= 0x10FFFF);
    }
}
