/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.mirth.connect.connectors.smtpreceiver.shared;

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.commons.lang3.builder.EqualsBuilder;

import com.mirth.connect.donkey.model.channel.ConnectorProperties;
import com.mirth.connect.donkey.model.channel.ListenerConnectorProperties;
import com.mirth.connect.donkey.model.channel.ListenerConnectorPropertiesInterface;
import com.mirth.connect.donkey.model.channel.SourceConnectorProperties;
import com.mirth.connect.donkey.model.channel.SourceConnectorPropertiesInterface;
import com.mirth.connect.donkey.util.DonkeyElement;

/**
 * Settings of the "SMTP Receiver" source connector. Shared between the server, the Swing
 * administrator and (as JSON) the web administrator.
 *
 * The engine's XStream deserializer does not run the constructor, so a value that is missing from a
 * saved channel is null or zero here; the getters that matter fall back to the default.
 */
public class SmtpReceiverProperties extends ConnectorProperties implements ListenerConnectorPropertiesInterface, SourceConnectorPropertiesInterface {
    public static final String NAME = "SMTP Receiver";

    public static final String TLS_NONE = "NONE";
    public static final String TLS_STARTTLS = "STARTTLS";
    public static final String TLS_IMPLICIT = "IMPLICIT";

    public static final String FORMAT_RFC822 = "RFC822";
    public static final String FORMAT_JSON = "JSON";
    public static final String FORMAT_XML = "XML";

    public static final String DEFAULT_PORT = "2525";

    private ListenerConnectorProperties listenerConnectorProperties;
    private SourceConnectorProperties sourceConnectorProperties;

    private String hostname;
    private boolean requireAuthentication;
    private String users;

    private String tlsMode;
    private String keystorePath;
    private String keystorePassword;
    private String keyPassword;
    private String keystoreType;

    private String maxMessageSize;
    private String maxConnections;
    private String maxRecipients;
    private String timeout;

    private String messageFormat;
    // Boolean, not boolean: a channel saved before this setting existed has null here, which must mean the default (on).
    private Boolean includeAttachmentContent;
    private String charset;

    public SmtpReceiverProperties() {
        listenerConnectorProperties = new ListenerConnectorProperties(DEFAULT_PORT);
        sourceConnectorProperties = new SourceConnectorProperties();

        hostname = "";
        requireAuthentication = true;
        users = "";

        tlsMode = TLS_NONE;
        keystorePath = "";
        keystorePassword = "";
        keyPassword = "";
        keystoreType = "PKCS12";

        maxMessageSize = "25";
        maxConnections = "10";
        maxRecipients = "100";
        timeout = "120";

        messageFormat = FORMAT_RFC822;
        includeAttachmentContent = Boolean.TRUE;
        charset = "UTF-8";
    }

    @Override
    public String getProtocol() {
        return null;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String toFormattedString() {
        return null;
    }

    @Override
    public ListenerConnectorProperties getListenerConnectorProperties() {
        return listenerConnectorProperties;
    }

    @Override
    public SourceConnectorProperties getSourceConnectorProperties() {
        return sourceConnectorProperties;
    }

    @Override
    public boolean canBatch() {
        return false;
    }

    /** Name in the greeting; empty means "use the name of this machine". */
    public String getHostname() {
        return hostname == null ? "" : hostname;
    }

    public void setHostname(String hostname) {
        this.hostname = hostname;
    }

    public boolean isRequireAuthentication() {
        return requireAuthentication;
    }

    public void setRequireAuthentication(boolean requireAuthentication) {
        this.requireAuthentication = requireAuthentication;
    }

    /** One "username:password" per line. The password may contain colons; the username may not. Spaces around both are ignored. */
    public String getUsers() {
        return users == null ? "" : users;
    }

    public void setUsers(String users) {
        this.users = users;
    }

    /**
     * Parses the users list. Blank lines and lines starting with # are skipped.
     *
     * @throws IllegalArgumentException for a line without a colon or with an empty username
     */
    public Map<String, String> parseUsers() {
        Map<String, String> result = new LinkedHashMap<String, String>();
        int lineNumber = 0;
        for (String line : getUsers().split("\\r?\\n")) {
            lineNumber++;
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int colon = trimmed.indexOf(':');
            if (colon < 1) {
                throw new IllegalArgumentException("Users, line " + lineNumber + ": expected username:password");
            }
            // Spaces around the username and the password come from typing, not from the password.
            result.put(trimmed.substring(0, colon).trim(), trimmed.substring(colon + 1).trim());
        }
        return result;
    }

    public String getTlsMode() {
        return TLS_STARTTLS.equals(tlsMode) || TLS_IMPLICIT.equals(tlsMode) ? tlsMode : TLS_NONE;
    }

    public void setTlsMode(String tlsMode) {
        this.tlsMode = tlsMode;
    }

    public String getKeystorePath() {
        return keystorePath == null ? "" : keystorePath;
    }

    public void setKeystorePath(String keystorePath) {
        this.keystorePath = keystorePath;
    }

    public String getKeystorePassword() {
        return keystorePassword == null ? "" : keystorePassword;
    }

    public void setKeystorePassword(String keystorePassword) {
        this.keystorePassword = keystorePassword;
    }

    /** Password of the key itself; empty means the same as the keystore password. */
    public String getKeyPassword() {
        return keyPassword == null ? "" : keyPassword;
    }

    public void setKeyPassword(String keyPassword) {
        this.keyPassword = keyPassword;
    }

    public String getKeystoreType() {
        return keystoreType == null || keystoreType.trim().isEmpty() ? "PKCS12" : keystoreType;
    }

    public void setKeystoreType(String keystoreType) {
        this.keystoreType = keystoreType;
    }

    /** Megabytes. */
    public String getMaxMessageSize() {
        return maxMessageSize == null ? "25" : maxMessageSize;
    }

    public void setMaxMessageSize(String maxMessageSize) {
        this.maxMessageSize = maxMessageSize;
    }

    public String getMaxConnections() {
        return maxConnections == null ? "10" : maxConnections;
    }

    public void setMaxConnections(String maxConnections) {
        this.maxConnections = maxConnections;
    }

    public String getMaxRecipients() {
        return maxRecipients == null ? "100" : maxRecipients;
    }

    public void setMaxRecipients(String maxRecipients) {
        this.maxRecipients = maxRecipients;
    }

    /** Seconds a client may stay silent before the connection is closed. */
    public String getTimeout() {
        return timeout == null ? "120" : timeout;
    }

    public void setTimeout(String timeout) {
        this.timeout = timeout;
    }

    /** RFC822 (plain text: the mail as it was sent), JSON or XML (the mail taken apart). */
    public String getMessageFormat() {
        if (FORMAT_JSON.equals(messageFormat) || FORMAT_XML.equals(messageFormat)) {
            return messageFormat;
        }
        return FORMAT_RFC822;
    }

    public void setMessageFormat(String messageFormat) {
        this.messageFormat = messageFormat;
    }

    /** JSON and XML only: put the attachments themselves (Base64) in the message, not just their name, type and size. */
    public boolean isIncludeAttachmentContent() {
        return includeAttachmentContent == null || includeAttachmentContent;
    }

    public void setIncludeAttachmentContent(boolean includeAttachmentContent) {
        this.includeAttachmentContent = includeAttachmentContent;
    }

    public String getCharset() {
        return charset == null || charset.trim().isEmpty() ? "UTF-8" : charset;
    }

    public void setCharset(String charset) {
        this.charset = charset;
    }

    @Override
    public boolean equals(Object obj) {
        return EqualsBuilder.reflectionEquals(this, obj);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(hostname, requireAuthentication, tlsMode, maxMessageSize, maxConnections, messageFormat, includeAttachmentContent);
    }

    // @formatter:off
    @Override public void migrate3_0_1(DonkeyElement element) {}
    @Override public void migrate3_0_2(DonkeyElement element) {}
    @Override public void migrate3_1_0(DonkeyElement element) { super.migrate3_1_0(element); }
    @Override public void migrate3_2_0(DonkeyElement element) {}
    @Override public void migrate3_3_0(DonkeyElement element) {}
    @Override public void migrate3_4_0(DonkeyElement element) {}
    @Override public void migrate3_5_0(DonkeyElement element) {}
    @Override public void migrate3_6_0(DonkeyElement element) {}
    @Override public void migrate3_7_0(DonkeyElement element) {}
    @Override public void migrate3_9_0(DonkeyElement element) {}
    @Override public void migrate3_11_0(DonkeyElement element) {}
    @Override public void migrate3_11_1(DonkeyElement element) {}
    @Override public void migrate3_12_0(DonkeyElement element) {}
    // @formatter:on

    @Override
    public Map<String, Object> getPurgedProperties() {
        Map<String, Object> purgedProperties = super.getPurgedProperties();
        purgedProperties.put("sourceConnectorProperties", sourceConnectorProperties.getPurgedProperties());
        purgedProperties.put("requireAuthentication", requireAuthentication);
        purgedProperties.put("tlsMode", getTlsMode());
        purgedProperties.put("messageFormat", getMessageFormat());
        purgedProperties.put("includeAttachmentContent", isIncludeAttachmentContent());
        purgedProperties.put("maxMessageSize", getMaxMessageSize());
        purgedProperties.put("maxConnections", getMaxConnections());
        return purgedProperties;
    }
}
