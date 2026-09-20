/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.mirth.connect.connectors.smtpreceiver.server;

/** An SMTP reply: three-digit code, enhanced status code (RFC 3463) and text. */
public final class SmtpReply {
    public final int code;
    public final String enhanced;
    public final String text;

    public SmtpReply(int code, String enhanced, String text) {
        this.code = code;
        this.enhanced = enhanced;
        this.text = text;
    }

    public static SmtpReply ok(String text) {
        return new SmtpReply(250, "2.0.0", text);
    }

    /** Temporary failure: the sender keeps the mail and tries again later. */
    public static SmtpReply tryAgain(String text) {
        return new SmtpReply(451, "4.3.0", text);
    }

    /** Permanent failure: the sender gives up and reports a bounce. */
    public static SmtpReply reject(String text) {
        return new SmtpReply(554, "5.6.0", text);
    }

    public boolean isPositive() {
        return code >= 200 && code < 400;
    }

    @Override
    public String toString() {
        return code + " " + enhanced + " " + text;
    }
}
