/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.mirth.connect.connectors.smtpreceiver.server;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A message as received: the envelope of the SMTP transaction plus the data. */
public final class SmtpMessage {
    public final String clientAddress;
    public final int clientPort;
    public final String localAddress;
    public final int localPort;
    /** Name the client gave in HELO/EHLO. */
    public final String helo;
    /** Envelope sender exactly as the client wrote it; empty for a bounce (MAIL FROM:&lt;&gt;). */
    public final String mailFrom;
    public final List<String> recipients;
    /** The authenticated user, or null. */
    public final String authenticatedUser;
    public final boolean tls;
    /** Header and body, dot-unstuffed, with CRLF line endings. */
    public final byte[] data;

    public SmtpMessage(String clientAddress, int clientPort, String localAddress, int localPort, String helo, String mailFrom, List<String> recipients, String authenticatedUser, boolean tls, byte[] data) {
        this.clientAddress = clientAddress;
        this.clientPort = clientPort;
        this.localAddress = localAddress;
        this.localPort = localPort;
        this.helo = helo;
        this.mailFrom = mailFrom;
        this.recipients = Collections.unmodifiableList(new ArrayList<String>(recipients));
        this.authenticatedUser = authenticatedUser;
        this.tls = tls;
        this.data = data;
    }

    public String dataAsString(Charset charset) {
        return new String(data, charset);
    }

    /** The data split into lines, blank lines included, without line endings. */
    public List<String> dataLines(Charset charset) {
        String text = dataAsString(charset);
        List<String> lines = new ArrayList<String>();
        int start = 0;
        while (start < text.length()) {
            int end = text.indexOf("\r\n", start);
            if (end < 0) {
                lines.add(text.substring(start));
                break;
            }
            lines.add(text.substring(start, end));
            start = end + 2;
        }
        return lines;
    }
}
