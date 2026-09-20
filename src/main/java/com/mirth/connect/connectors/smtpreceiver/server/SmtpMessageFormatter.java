/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.mirth.connect.connectors.smtpreceiver.server;

import java.nio.charset.Charset;
import java.util.List;

/** Turns a received message into the text that goes into the channel. */
public final class SmtpMessageFormatter {
    private SmtpMessageFormatter() {}

    /** The mail exactly as it was sent: headers, an empty line and the body, with CRLF line endings. */
    public static String rfc822(SmtpMessage message, Charset charset) {
        return message.dataAsString(charset);
    }

    /**
     * JSON with the envelope and the data as a list of lines. Empty lines are kept, so the boundary between
     * the headers and the body is still there.
     *
     * <pre>
     * {"MAILFROM":"a@b.nl","RCPTTO":["c@d.nl"],"USER":"","AUTHENTICATION":"NA","CLIENTIP":"10.0.0.5",
     *  "HELO":"client.example","TLS":false,"DATA":["Subject: Hi","","Body"]}
     * </pre>
     */
    public static String json(SmtpMessage message, Charset charset) {
        StringBuilder sb = new StringBuilder(message.data.length + 256);
        sb.append('{');
        field(sb, "MAILFROM", message.mailFrom);
        sb.append(",\"RCPTTO\":[");
        List<String> recipients = message.recipients;
        for (int i = 0; i < recipients.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            string(sb, recipients.get(i));
        }
        sb.append(']');
        sb.append(',');
        field(sb, "USER", message.authenticatedUser == null ? "" : message.authenticatedUser);
        sb.append(',');
        field(sb, "AUTHENTICATION", message.authenticatedUser == null ? "NA" : "SUCCESS");
        sb.append(',');
        field(sb, "CLIENTIP", message.clientAddress);
        sb.append(',');
        field(sb, "HELO", message.helo);
        sb.append(",\"TLS\":").append(message.tls);
        sb.append(",\"DATA\":[");
        List<String> lines = message.dataLines(charset);
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            string(sb, lines.get(i));
        }
        sb.append("]}");
        return sb.toString();
    }

    private static void field(StringBuilder sb, String name, String value) {
        string(sb, name);
        sb.append(':');
        string(sb, value == null ? "" : value);
    }

    private static void string(StringBuilder sb, String value) {
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
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
}
