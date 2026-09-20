/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.mirth.connect.connectors.smtpreceiver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Map;

import org.junit.Test;

import com.mirth.connect.connectors.smtpreceiver.shared.SmtpReceiverProperties;

public class SmtpReceiverPropertiesTest {

    @Test
    public void defaults() {
        SmtpReceiverProperties p = new SmtpReceiverProperties();

        assertEquals("SMTP Receiver", p.getName());
        assertEquals("0.0.0.0", p.getListenerConnectorProperties().getHost());
        assertEquals("2525", p.getListenerConnectorProperties().getPort());
        assertTrue("authentication is required unless switched off", p.isRequireAuthentication());
        assertEquals("NONE", p.getTlsMode());
        assertEquals("RFC822", p.getMessageFormat());
        assertEquals("UTF-8", p.getCharset());
        assertEquals("25", p.getMaxMessageSize());
        assertEquals("10", p.getMaxConnections());
        assertEquals("100", p.getMaxRecipients());
        assertEquals("120", p.getTimeout());
        assertFalse(p.canBatch());
    }

    @Test
    public void usersAreOneUsernameAndPasswordPerLine() {
        SmtpReceiverProperties p = new SmtpReceiverProperties();
        p.setUsers("mirth:123\r\n\r\n# a comment\n  test : 456 \nodd:pa:ss:word\n");

        Map<String, String> users = p.parseUsers();

        assertEquals(3, users.size());
        assertEquals("123", users.get("mirth"));
        assertEquals("456", users.get("test"));
        assertEquals("pa:ss:word", users.get("odd"));
    }

    @Test
    public void emptyPasswordsAreAllowedButNotEmptyUsernames() {
        SmtpReceiverProperties p = new SmtpReceiverProperties();
        p.setUsers("guest:");
        assertEquals("", p.parseUsers().get("guest"));

        for (String bad : new String[] { "nocolon", ":password", "ok:1\nbad line" }) {
            p.setUsers(bad);
            try {
                p.parseUsers();
                fail("expected an error for " + bad);
            } catch (IllegalArgumentException e) {
                assertTrue(e.getMessage(), e.getMessage().startsWith("Users, line "));
            }
        }
    }

    @Test
    public void noUsersIsAnEmptyMap() {
        assertTrue(new SmtpReceiverProperties().parseUsers().isEmpty());
    }

    @Test
    public void valuesThatAreMissingFromASavedChannelFallBackToDefaults() throws Exception {
        // XStream does not run constructors: every field is null/false/0 until it is read.
        SmtpReceiverProperties p = allocateWithoutConstructor();

        assertEquals("", p.getHostname());
        assertEquals("", p.getUsers());
        assertTrue(p.parseUsers().isEmpty());
        assertEquals("NONE", p.getTlsMode());
        assertEquals("PKCS12", p.getKeystoreType());
        assertEquals("25", p.getMaxMessageSize());
        assertEquals("10", p.getMaxConnections());
        assertEquals("100", p.getMaxRecipients());
        assertEquals("120", p.getTimeout());
        assertEquals("RFC822", p.getMessageFormat());
        assertEquals("UTF-8", p.getCharset());
        assertEquals("", p.getKeystorePassword());
    }

    @Test
    public void xmlIsAFormatAndAttachmentContentDefaultsToOn() throws Exception {
        SmtpReceiverProperties p = new SmtpReceiverProperties();
        assertTrue(p.isIncludeAttachmentContent());
        p.setMessageFormat("XML");
        assertEquals("XML", p.getMessageFormat());
        p.setIncludeAttachmentContent(false);
        assertFalse(p.isIncludeAttachmentContent());
    }

    @Test
    public void unknownModesFallBackToTheSafeChoice() {
        SmtpReceiverProperties p = new SmtpReceiverProperties();
        p.setTlsMode("SOMETHING");
        p.setMessageFormat("YAML");

        assertEquals("NONE", p.getTlsMode());
        assertEquals("RFC822", p.getMessageFormat());

        p.setTlsMode("STARTTLS");
        p.setMessageFormat("JSON");
        assertEquals("STARTTLS", p.getTlsMode());
        assertEquals("JSON", p.getMessageFormat());
    }

    @Test
    public void purgedPropertiesDoNotContainSecrets() {
        SmtpReceiverProperties p = new SmtpReceiverProperties();
        p.setPluginProperties(new java.util.HashSet<com.mirth.connect.donkey.model.channel.ConnectorPluginProperties>());
        p.setUsers("mirth:secret");
        p.setKeystorePassword("keystore-secret");
        p.setKeyPassword("key-secret");

        String purged = p.getPurgedProperties().toString();

        assertFalse(purged, purged.contains("secret"));
    }

    private static SmtpReceiverProperties allocateWithoutConstructor() throws Exception {
        java.lang.reflect.Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (SmtpReceiverProperties) ((sun.misc.Unsafe) f.get(null)).allocateInstance(SmtpReceiverProperties.class);
    }
}
