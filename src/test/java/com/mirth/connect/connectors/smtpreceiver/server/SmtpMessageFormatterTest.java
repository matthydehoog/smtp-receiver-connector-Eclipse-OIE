/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.mirth.connect.connectors.smtpreceiver.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class SmtpMessageFormatterTest {

    private static final String SIMPLE = "From: Jan Jansen <jan@bedrijf.nl>\r\n"
            + "To: a@x.nl, \"Bakker, Piet\" <b@x.nl>\r\n"
            + "Cc: c@x.nl\r\n"
            + "Subject: Hello\r\n"
            + "Date: Tue, 05 May 2026 10:00:00 +0200\r\n"
            + "Message-ID: <abc@bedrijf.nl>\r\n"
            + "Content-Type: text/plain; charset=UTF-8\r\n"
            + "\r\n"
            + "Line one\r\nLine two\r\n";

    private static final String MULTIPART = "From: jan@bedrijf.nl\r\n"
            + "To: a@x.nl\r\n"
            + "Subject: =?UTF-8?Q?Caf=C3=A9_menu?=\r\n"
            + "MIME-Version: 1.0\r\n"
            + "Content-Type: multipart/mixed; boundary=\"outer\"\r\n"
            + "\r\n"
            + "--outer\r\n"
            + "Content-Type: multipart/alternative; boundary=\"inner\"\r\n"
            + "\r\n"
            + "--inner\r\n"
            + "Content-Type: text/plain; charset=UTF-8\r\n"
            + "\r\n"
            + "Plain body\r\n"
            + "--inner\r\n"
            + "Content-Type: text/html; charset=UTF-8\r\n"
            + "\r\n"
            + "<p>HTML <b>body</b></p>\r\n"
            + "--inner--\r\n"
            + "--outer\r\n"
            + "Content-Type: application/pdf; name=\"rapport.pdf\"\r\n"
            + "Content-Disposition: attachment; filename=\"rapport.pdf\"\r\n"
            + "Content-Transfer-Encoding: base64\r\n"
            + "\r\n"
            + Base64.getMimeEncoder().encodeToString("PDF-BYTES".getBytes(StandardCharsets.US_ASCII)) + "\r\n"
            + "--outer--\r\n";

    private static SmtpMessage message(String data, String user) {
        return new SmtpMessage("10.0.0.5", 4711, "10.0.0.1", 2525, "client.example", "Jan@Bedrijf.NL", Arrays.asList("a@x.nl", "B@X.nl"), user, false, data.getBytes(StandardCharsets.UTF_8));
    }

    private static JsonObject json(String data, String user, boolean content) {
        String text = SmtpMessageFormatter.json(message(data, user), StandardCharsets.UTF_8, content);
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private static Element xml(String data, String user, boolean content) throws Exception {
        String text = SmtpMessageFormatter.xml(message(data, user), StandardCharsets.UTF_8, content);
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
        return doc.getDocumentElement();
    }

    private static Element child(Element parent, String name) {
        NodeList list = parent.getElementsByTagName(name);
        return list.getLength() == 0 ? null : (Element) list.item(0);
    }

    @Test
    public void rfc822IsTheDataAsItWasSent() {
        String data = "Subject: Hi\r\n\r\nBody\r\n";

        assertEquals(data, SmtpMessageFormatter.rfc822(message(data, null), StandardCharsets.UTF_8));
        assertEquals(data, SmtpMessageFormatter.format(message(data, null), SmtpMessageFormatter.FORMAT_RFC822, StandardCharsets.UTF_8, true));
    }

    @Test
    public void unknownFormatFallsBackToPlainText() {
        String data = "Subject: Hi\r\n\r\nBody\r\n";

        assertEquals(data, SmtpMessageFormatter.format(message(data, null), "nonsense", StandardCharsets.UTF_8, true));
    }

    @Test
    public void jsonHasTheEnvelope() {
        JsonObject o = json(SIMPLE, null, true);
        JsonObject envelope = o.getAsJsonObject("envelope");

        assertEquals("Jan@Bedrijf.NL", envelope.get("mailFrom").getAsString());
        assertEquals("a@x.nl", envelope.getAsJsonArray("rcptTo").get(0).getAsString());
        assertEquals("B@X.nl", envelope.getAsJsonArray("rcptTo").get(1).getAsString());
        assertEquals("NA", envelope.get("authentication").getAsString());
        assertEquals("", envelope.get("user").getAsString());
        assertEquals("10.0.0.5", envelope.get("clientIp").getAsString());
        assertEquals("client.example", envelope.get("helo").getAsString());
        assertFalse(envelope.get("tls").getAsBoolean());
    }

    @Test
    public void jsonShowsWhoLoggedIn() {
        JsonObject envelope = json(SIMPLE, "mirth", true).getAsJsonObject("envelope");

        assertEquals("mirth", envelope.get("user").getAsString());
        assertEquals("SUCCESS", envelope.get("authentication").getAsString());
    }

    @Test
    public void jsonHasTheParsedMail() {
        JsonObject o = json(SIMPLE, null, true);

        assertEquals("Hello", o.get("subject").getAsString());
        assertEquals("<abc@bedrijf.nl>", o.get("messageId").getAsString());
        assertEquals("2026-05-05T08:00:00Z", o.get("date").getAsString());
        assertEquals("Jan Jansen", o.getAsJsonArray("from").get(0).getAsJsonObject().get("name").getAsString());
        assertEquals("jan@bedrijf.nl", o.getAsJsonArray("from").get(0).getAsJsonObject().get("address").getAsString());
        JsonArray to = o.getAsJsonArray("to");
        assertEquals(2, to.size());
        assertEquals("Bakker, Piet", to.get(1).getAsJsonObject().get("name").getAsString());
        assertEquals("b@x.nl", to.get(1).getAsJsonObject().get("address").getAsString());
        assertEquals("c@x.nl", o.getAsJsonArray("cc").get(0).getAsJsonObject().get("address").getAsString());
        assertEquals("Line one\nLine two\n", o.get("text").getAsString());
        assertEquals(0, o.getAsJsonArray("attachments").size());
        assertFalse(o.has("parseError"));
    }

    @Test
    public void jsonListsTheHeadersInOrder() {
        JsonArray headers = json(SIMPLE, null, true).getAsJsonArray("headers");

        assertEquals("From", headers.get(0).getAsJsonObject().get("name").getAsString());
        assertEquals("Hello", headers.get(3).getAsJsonObject().get("value").getAsString());
    }

    @Test
    public void multipartGivesTextHtmlAndAttachment() {
        JsonObject o = json(MULTIPART, null, true);

        assertEquals("Café menu", o.get("subject").getAsString());
        assertEquals("Plain body", o.get("text").getAsString().trim());
        assertTrue(o.get("html").getAsString().contains("<b>body</b>"));
        JsonArray attachments = o.getAsJsonArray("attachments");
        assertEquals(1, attachments.size());
        JsonObject a = attachments.get(0).getAsJsonObject();
        assertEquals("rapport.pdf", a.get("filename").getAsString());
        assertEquals("application/pdf", a.get("contentType").getAsString().toLowerCase().split(";")[0]);
        assertEquals(9, a.get("size").getAsInt());
        assertEquals("attachment", a.get("disposition").getAsString());
        assertEquals("PDF-BYTES", new String(Base64.getDecoder().decode(a.get("content").getAsString()), StandardCharsets.US_ASCII));
    }

    @Test
    public void attachmentContentCanBeLeftOut() {
        JsonObject a = json(MULTIPART, null, false).getAsJsonArray("attachments").get(0).getAsJsonObject();

        assertEquals("rapport.pdf", a.get("filename").getAsString());
        assertEquals(9, a.get("size").getAsInt());
        assertFalse(a.has("content"));
    }

    @Test
    public void htmlOnlyMailAlsoGetsPlainText() {
        String data = "From: a@x.nl\r\nSubject: h\r\nContent-Type: text/html; charset=UTF-8\r\n\r\n<html><body><p>Hello &amp; welcome</p></body></html>\r\n";
        JsonObject o = json(data, null, true);

        assertTrue(o.get("html").getAsString().contains("<p>"));
        assertTrue(o.get("text").getAsString(), o.get("text").getAsString().contains("Hello & welcome"));
        assertFalse(o.get("text").getAsString().contains("<p>"));
    }

    @Test
    public void inlineImageIsAnAttachmentWithContentId() {
        String data = "From: a@x.nl\r\nSubject: i\r\nMIME-Version: 1.0\r\nContent-Type: multipart/related; boundary=\"b\"\r\n\r\n"
                + "--b\r\nContent-Type: text/html; charset=UTF-8\r\n\r\n<img src=\"cid:logo\">\r\n"
                + "--b\r\nContent-Type: image/png\r\nContent-ID: <logo>\r\nContent-Transfer-Encoding: base64\r\n\r\nAAEC\r\n--b--\r\n";
        JsonObject a = json(data, null, true).getAsJsonArray("attachments").get(0).getAsJsonObject();

        assertEquals("logo", a.get("contentId").getAsString());
        assertEquals("inline", a.get("disposition").getAsString());
        assertEquals(3, a.get("size").getAsInt());
    }

    @Test
    public void encodedFilenameIsDecoded() {
        String data = "From: a@x.nl\r\nSubject: f\r\nMIME-Version: 1.0\r\nContent-Type: multipart/mixed; boundary=\"b\"\r\n\r\n"
                + "--b\r\nContent-Type: text/plain\r\n\r\nhi\r\n"
                + "--b\r\nContent-Type: application/octet-stream; name=\"=?UTF-8?B?Y2Fmw6kudHh0?=\"\r\nContent-Disposition: attachment; filename=\"=?UTF-8?B?Y2Fmw6kudHh0?=\"\r\n\r\nxyz\r\n--b--\r\n";
        JsonObject a = json(data, null, true).getAsJsonArray("attachments").get(0).getAsJsonObject();

        assertEquals("café.txt", a.get("filename").getAsString());
    }

    @Test
    public void attachedMailIsAnAttachment() {
        String data = "From: a@x.nl\r\nSubject: f\r\nMIME-Version: 1.0\r\nContent-Type: multipart/mixed; boundary=\"b\"\r\n\r\n"
                + "--b\r\nContent-Type: text/plain\r\n\r\nSee attached\r\n"
                + "--b\r\nContent-Type: message/rfc822\r\nContent-Disposition: attachment; filename=\"orig.eml\"\r\n\r\nFrom: z@x.nl\r\nSubject: original\r\n\r\ninner\r\n--b--\r\n";
        JsonObject o = json(data, null, true);

        assertEquals("See attached", o.get("text").getAsString().trim());
        assertEquals(1, o.getAsJsonArray("attachments").size());
        assertEquals("orig.eml", o.getAsJsonArray("attachments").get(0).getAsJsonObject().get("filename").getAsString());
    }

    @Test
    public void badAddressIsKeptAsWritten() {
        String data = "From: not an address <<\r\nTo: a@x.nl\r\nSubject: s\r\n\r\nbody\r\n";
        JsonObject o = json(data, null, true);

        assertEquals("s", o.get("subject").getAsString());
        assertEquals(1, o.getAsJsonArray("from").size());
        assertTrue(o.getAsJsonArray("from").get(0).getAsJsonObject().get("address").getAsString().contains("not an address"));
    }

    @Test
    public void mailWithoutHeadersStillGivesAMessage() {
        JsonObject o = json("\r\njust some text\r\n", null, true);

        assertEquals("", o.get("subject").getAsString());
        assertTrue(o.get("text").getAsString().contains("just some text"));
    }

    @Test
    public void emptyData() {
        SmtpMessage m = new SmtpMessage("1.1.1.1", 1, "2.2.2.2", 2, "", "", Collections.<String>emptyList(), null, true, new byte[0]);
        JsonObject o = JsonParser.parseString(SmtpMessageFormatter.json(m, StandardCharsets.UTF_8, true)).getAsJsonObject();

        assertTrue(o.getAsJsonObject("envelope").get("tls").getAsBoolean());
        assertEquals(0, o.getAsJsonObject("envelope").getAsJsonArray("rcptTo").size());
        assertEquals("", o.get("text").getAsString());
    }

    @Test
    public void bodyWithoutCharsetUsesTheConfiguredOne() {
        byte[] data = "From: a@x.nl\r\nSubject: s\r\n\r\ncafé\r\n".getBytes(StandardCharsets.ISO_8859_1);
        SmtpMessage m = new SmtpMessage("1.1.1.1", 1, "2.2.2.2", 2, "", "", Collections.<String>emptyList(), null, false, data);
        JsonObject o = JsonParser.parseString(SmtpMessageFormatter.json(m, StandardCharsets.ISO_8859_1, true)).getAsJsonObject();

        assertTrue(o.get("text").getAsString(), o.get("text").getAsString().contains("café"));
    }

    @Test
    public void jsonEscapesWhatNeedsEscaping() {
        JsonObject o = json("From: a@x.nl\r\nSubject: say \"hi\" \\ tab\there\r\n\r\nbé\r\n", null, true);

        assertEquals("say \"hi\" \\ tab\there", o.get("subject").getAsString());
    }

    @Test
    public void xmlHasTheEnvelopeAndTheParsedMail() throws Exception {
        Element e = xml(SIMPLE, "mirth", true);

        assertEquals("email", e.getTagName());
        assertEquals("Jan@Bedrijf.NL", child(e, "mailFrom").getTextContent());
        assertEquals("SUCCESS", child(e, "authentication").getTextContent());
        assertEquals("Hello", child(e, "subject").getTextContent());
        assertEquals("<abc@bedrijf.nl>", child(e, "messageId").getTextContent());
        assertEquals("2026-05-05T08:00:00Z", child(e, "date").getTextContent());
        Element from = (Element) child(e, "from").getElementsByTagName("address").item(0);
        assertEquals("Jan Jansen", from.getAttribute("name"));
        assertEquals("jan@bedrijf.nl", from.getTextContent());
        NodeList to = child(e, "to").getElementsByTagName("address");
        assertEquals(2, to.getLength());
        assertEquals("Bakker, Piet", ((Element) to.item(1)).getAttribute("name"));
        assertEquals("Line one\nLine two\n", child(e, "text").getTextContent());
    }

    @Test
    public void xmlHasTheAttachment() throws Exception {
        Element e = xml(MULTIPART, null, true);
        Element a = (Element) child(e, "attachments").getElementsByTagName("attachment").item(0);

        assertEquals("rapport.pdf", a.getAttribute("filename"));
        assertEquals("9", a.getAttribute("size"));
        assertEquals("PDF-BYTES", new String(Base64.getDecoder().decode(a.getTextContent()), StandardCharsets.US_ASCII));
        assertEquals("Café menu", child(e, "subject").getTextContent());
        assertTrue(child(e, "html").getTextContent().contains("<b>body</b>"));
    }

    @Test
    public void xmlWithoutAttachmentContent() throws Exception {
        Element a = (Element) child(xml(MULTIPART, null, false), "attachments").getElementsByTagName("attachment").item(0);

        assertEquals("rapport.pdf", a.getAttribute("filename"));
        assertEquals("", a.getTextContent());
    }

    @Test
    public void xmlEscapesAndDropsIllegalCharacters() throws Exception {
        Element e = xml("From: a@x.nl\r\nSubject: a < b & \"c\"  ok\r\n\r\n<tag> & done\r\n", null, true);

        assertEquals("a < b & \"c\"  ok", child(e, "subject").getTextContent().replace("  ", "  "));
        assertTrue(child(e, "text").getTextContent().contains("<tag> & done"));
    }

    @Test
    public void formatPicksTheOutput() {
        SmtpMessage m = message(SIMPLE, null);

        assertTrue(SmtpMessageFormatter.format(m, SmtpMessageFormatter.FORMAT_JSON, StandardCharsets.UTF_8, true).startsWith("{"));
        assertTrue(SmtpMessageFormatter.format(m, SmtpMessageFormatter.FORMAT_XML, StandardCharsets.UTF_8, true).startsWith("<email>"));
    }

    @Test
    public void theCharsetDecidesHowBytesBecomeText() {
        SmtpMessage m = new SmtpMessage("1.1.1.1", 1, "2.2.2.2", 2, "", "", Collections.<String>emptyList(), null, false, new byte[] { (byte) 0xE9, '\r', '\n' });

        assertEquals("é\r\n", SmtpMessageFormatter.rfc822(m, StandardCharsets.ISO_8859_1));
        assertEquals("�\r\n", SmtpMessageFormatter.rfc822(m, StandardCharsets.UTF_8));
    }
}
