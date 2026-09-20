/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.mirth.connect.connectors.smtpreceiver.server;

import java.util.Collections;
import java.util.Map;

import javax.net.ssl.SSLContext;

/** Everything the SMTP server needs to know. Independent of the engine, so it can be tested on its own. */
public class SmtpConfig {
    public enum TlsMode {
        NONE, STARTTLS, IMPLICIT
    }

    /** Address to bind to; null or empty means all interfaces. */
    public String bindHost = "0.0.0.0";
    public int port = 2525;
    /** Name used in the greeting. */
    public String hostname = "localhost";

    public boolean requireAuthentication = false;
    /** username to password. */
    public Map<String, String> users = Collections.emptyMap();

    public TlsMode tlsMode = TlsMode.NONE;
    public SSLContext sslContext;

    /** Bytes; 0 means no limit. */
    public long maxMessageBytes = 25L * 1024 * 1024;
    public int maxConnections = 10;
    public int maxRecipients = 100;
    /** Milliseconds a client may stay silent. */
    public int timeoutMillis = 120000;

    public boolean authenticationPossible() {
        return !users.isEmpty();
    }
}
