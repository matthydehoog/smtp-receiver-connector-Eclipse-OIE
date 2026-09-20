/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.mirth.connect.connectors.smtpreceiver.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

public class SmtpMessageFormatterTest {

    private static SmtpMessage message(String data, String user) {
        return new SmtpMessage("10.0.0.5", 4711, "10.0.0.1", 2525, "client.example", "Jan@Bedrijf.NL", Arrays.asList("a@x.nl", "B@X.nl"), user, false, data.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void rfc822IsTheDataAsItWasSent() {
        String data = "Subject: Hi\r\n\r\nBody\r\n";

        assertEquals(data, SmtpMessageFormatter.rfc822(message(data, null), StandardCharsets.UTF_8));
    }

    @Test
    public void jsonHasTheEnvelopeAndEveryLineOfTheData() {
        String json = SmtpMessageFormatter.json(message("Subject: Hi\r\n\r\nBody\r\n", null), StandardCharsets.UTF_8);

        assertEquals("{\"MAILFROM\":\"Jan@Bedrijf.NL\",\"RCPTTO\":[\"a@x.nl\",\"B@X.nl\"],\"USER\":\"\",\"AUTHENTICATION\":\"NA\",\"CLIENTIP\":\"10.0.0.5\",\"HELO\":\"client.example\",\"TLS\":false,\"DATA\":[\"Subject: Hi\",\"\",\"Body\"]}", json);
    }

    @Test
    public void jsonShowsWhoLoggedIn() {
        String json = SmtpMessageFormatter.json(message("x\r\n", "mirth"), StandardCharsets.UTF_8);

        assertTrue(json, json.contains("\"USER\":\"mirth\",\"AUTHENTICATION\":\"SUCCESS\""));
    }

    @Test
    public void jsonEscapesWhatNeedsEscaping() {
        String json = SmtpMessageFormatter.json(message("say \"hi\" \\ tab\there\r\n\u0001 caf\u00e9 \u2028\r\n", null), StandardCharsets.UTF_8);

        assertTrue(json, json.contains("\"say \\\"hi\\\" \\\\ tab\\there\""));
        assertTrue(json, json.contains("\"\\u0001 caf\u00e9 \\u2028\""));
    }

    @Test
    public void jsonDoesNotDropEmptyLines() {
        String json = SmtpMessageFormatter.json(message("a\r\n\r\n\r\nb\r\n", null), StandardCharsets.UTF_8);

        assertTrue(json, json.endsWith("\"DATA\":[\"a\",\"\",\"\",\"b\"]}"));
    }

    @Test
    public void jsonOfAnEmptyBody() {
        SmtpMessage m = new SmtpMessage("1.1.1.1", 1, "2.2.2.2", 2, "", "", Collections.<String>emptyList(), null, true, new byte[0]);

        assertEquals("{\"MAILFROM\":\"\",\"RCPTTO\":[],\"USER\":\"\",\"AUTHENTICATION\":\"NA\",\"CLIENTIP\":\"1.1.1.1\",\"HELO\":\"\",\"TLS\":true,\"DATA\":[]}", SmtpMessageFormatter.json(m, StandardCharsets.UTF_8));
    }

    @Test
    public void theCharsetDecidesHowBytesBecomeText() {
        SmtpMessage m = new SmtpMessage("1.1.1.1", 1, "2.2.2.2", 2, "", "", Collections.<String>emptyList(), null, false, new byte[] { (byte) 0xE9, '\r', '\n' });

        assertEquals("\u00e9\r\n", SmtpMessageFormatter.rfc822(m, StandardCharsets.ISO_8859_1));
        assertEquals("\ufffd\r\n", SmtpMessageFormatter.rfc822(m, StandardCharsets.UTF_8));
    }

    @Test
    public void dataLinesOfADataWithoutTrailingLineEnding() {
        SmtpMessage m = message("one\r\ntwo", null);

        assertEquals(Arrays.asList("one", "two"), m.dataLines(StandardCharsets.UTF_8));
    }
}
