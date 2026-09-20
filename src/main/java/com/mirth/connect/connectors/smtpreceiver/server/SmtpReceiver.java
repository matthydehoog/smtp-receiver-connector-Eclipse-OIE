/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.mirth.connect.connectors.smtpreceiver.server;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.BindException;
import java.net.InetAddress;
import java.nio.charset.Charset;
import java.security.KeyStore;
import java.util.HashMap;
import java.util.Map;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.mirth.connect.connectors.smtpreceiver.shared.SmtpReceiverProperties;
import com.mirth.connect.donkey.model.event.ConnectionStatusEventType;
import com.mirth.connect.donkey.model.event.ErrorEventType;
import com.mirth.connect.donkey.model.message.ConnectorMessage;
import com.mirth.connect.donkey.model.message.Message;
import com.mirth.connect.donkey.model.message.RawMessage;
import com.mirth.connect.donkey.model.message.Status;
import com.mirth.connect.donkey.server.ConnectorTaskException;
import com.mirth.connect.donkey.server.channel.ChannelException;
import com.mirth.connect.donkey.server.channel.DispatchResult;
import com.mirth.connect.donkey.server.channel.SourceConnector;
import com.mirth.connect.donkey.server.event.ConnectionStatusEvent;
import com.mirth.connect.donkey.server.event.ConnectorCountEvent;
import com.mirth.connect.donkey.server.event.ErrorEvent;
import com.mirth.connect.server.controllers.ControllerFactory;
import com.mirth.connect.server.controllers.EventController;
import com.mirth.connect.server.util.TemplateValueReplacer;

/**
 * Source connector that runs an SMTP server. Every mail that is sent to it is dispatched into the
 * channel, and the sender only gets its "250 Ok" when the channel has accepted the mail.
 */
public class SmtpReceiver extends SourceConnector {
    private static final int BIND_ATTEMPTS = 10;
    private static final long BIND_RETRY_MILLIS = 500;
    private static final long STOP_GRACE_MILLIS = 5000;

    private final Logger logger = LogManager.getLogger(getClass());
    private final EventController eventController = ControllerFactory.getFactory().createEventController();
    private final TemplateValueReplacer replacer = new TemplateValueReplacer();

    private SmtpReceiverProperties connectorProperties;
    private SmtpConfig config;
    private Charset charset;
    private String format;
    private boolean attachmentContent;
    private SmtpServer server;

    @Override
    public void onDeploy() throws ConnectorTaskException {
        connectorProperties = (SmtpReceiverProperties) getConnectorProperties();
        config = new SmtpConfig();
        config.bindHost = replacer.replaceValues(connectorProperties.getListenerConnectorProperties().getHost(), getChannelId(), getChannel().getName());
        config.port = number("Local Port", replacer.replaceValues(connectorProperties.getListenerConnectorProperties().getPort(), getChannelId(), getChannel().getName()), 1, 65535);
        config.hostname = connectorProperties.getHostname().trim().isEmpty() ? localHostName() : connectorProperties.getHostname().trim();
        config.maxMessageBytes = (long) number("Maximum message size", connectorProperties.getMaxMessageSize(), 0, 2000) * 1024 * 1024;
        config.maxConnections = number("Maximum connections", connectorProperties.getMaxConnections(), 1, 10000);
        config.maxRecipients = number("Maximum recipients", connectorProperties.getMaxRecipients(), 1, 100000);
        config.timeoutMillis = number("Timeout", connectorProperties.getTimeout(), 1, 86400) * 1000;

        config.requireAuthentication = connectorProperties.isRequireAuthentication();
        try {
            config.users = connectorProperties.parseUsers();
        } catch (IllegalArgumentException e) {
            throw new ConnectorTaskException(e.getMessage());
        }
        if (config.requireAuthentication && config.users.isEmpty()) {
            throw new ConnectorTaskException("Authentication is required, but no users are defined (Users: one username:password per line).");
        }

        config.tlsMode = SmtpConfig.TlsMode.valueOf(connectorProperties.getTlsMode());
        if (config.tlsMode != SmtpConfig.TlsMode.NONE) {
            config.sslContext = createSslContext();
        }

        try {
            charset = Charset.forName(connectorProperties.getCharset());
        } catch (IllegalArgumentException e) {
            throw new ConnectorTaskException("Unknown character set \"" + connectorProperties.getCharset() + "\".");
        }
        format = connectorProperties.getMessageFormat();
        attachmentContent = connectorProperties.isIncludeAttachmentContent();

        eventController.dispatchEvent(new ConnectorCountEvent(getChannelId(), getMetaDataId(), getSourceName(), ConnectionStatusEventType.IDLE, null, config.maxConnections));
    }

    @Override
    public void onUndeploy() throws ConnectorTaskException {}

    @Override
    public void onStart() throws ConnectorTaskException {
        server = new SmtpServer(config, this::receive, new EngineEvents());

        // A channel that is restarted quickly may find its port still in use for a moment.
        IOException failure = null;
        for (int attempt = 1; attempt <= BIND_ATTEMPTS; attempt++) {
            try {
                server.start();
                failure = null;
                break;
            } catch (BindException e) {
                failure = e;
                try {
                    Thread.sleep(BIND_RETRY_MILLIS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            } catch (IOException e) {
                failure = e;
                break;
            }
        }
        if (failure != null) {
            server = null;
            throw new ConnectorTaskException("Could not listen on " + (config.bindHost == null ? "" : config.bindHost + ":") + config.port + ": " + failure.getMessage(), failure);
        }

        // The engine's log level is ERROR by default, so also tell the dashboard: this shows in the connection log of the channel.
        String listening = "Listening on " + (config.bindHost == null || config.bindHost.trim().isEmpty() ? "0.0.0.0" : config.bindHost) + ":" + server.getPort() + (config.tlsMode == SmtpConfig.TlsMode.NONE ? "" : " (" + config.tlsMode + ")");
        eventController.dispatchEvent(new ConnectionStatusEvent(getChannelId(), getMetaDataId(), getSourceName(), ConnectionStatusEventType.INFO, listening));
        logger.info("SMTP Receiver of channel " + getChannelId() + ": " + listening.substring(0, 1).toLowerCase() + listening.substring(1));
    }

    @Override
    public void onStop() throws ConnectorTaskException {
        SmtpServer s = server;
        server = null;
        if (s != null) {
            int port = s.getPort();
            s.stop(STOP_GRACE_MILLIS);
            eventController.dispatchEvent(new ConnectionStatusEvent(getChannelId(), getMetaDataId(), getSourceName(), ConnectionStatusEventType.INFO, "Stopped listening on port " + port));
        }
    }

    @Override
    public void onHalt() throws ConnectorTaskException {
        SmtpServer s = server;
        server = null;
        if (s != null) {
            s.halt();
        }
    }

    @Override
    public void handleRecoveredResponse(DispatchResult dispatchResult) {
        // The client that was waiting for the answer is long gone.
        finishDispatch(dispatchResult);
    }

    /** Called by the SMTP server for every complete message. */
    private SmtpReply receive(SmtpMessage message) {
        eventController.dispatchEvent(new ConnectionStatusEvent(getChannelId(), getMetaDataId(), getSourceName(), ConnectionStatusEventType.RECEIVING, "Message received from " + message.clientAddress + ", processing..."));

        RawMessage rawMessage = new RawMessage(SmtpMessageFormatter.format(message, format, charset, attachmentContent));
        rawMessage.setSourceMap(sourceMap(message));

        DispatchResult dispatchResult = null;
        try {
            dispatchResult = dispatchRawMessage(rawMessage);

            if (dispatchResult.getChannelException() != null) {
                return SmtpReply.tryAgain("The message could not be stored, try again later");
            }
            Message processed = dispatchResult.getProcessedMessage();
            ConnectorMessage merged = processed == null ? null : processed.getMergedConnectorMessage();
            if (merged != null && merged.getStatus() == Status.ERROR) {
                // The channel failed to process it: the sender keeps the mail and tries again.
                return SmtpReply.tryAgain("The message could not be processed, try again later");
            }
            return SmtpReply.ok("Ok: queued as " + dispatchResult.getMessageId());
        } catch (ChannelException e) {
            eventController.dispatchEvent(new ConnectionStatusEvent(getChannelId(), getMetaDataId(), getSourceName(), ConnectionStatusEventType.FAILURE, "Could not dispatch the message from " + message.clientAddress + ": " + e.getMessage()));
            return SmtpReply.tryAgain("The channel cannot take the message now, try again later");
        } finally {
            finishDispatch(dispatchResult);
            eventController.dispatchEvent(new ConnectorCountEvent(getChannelId(), getMetaDataId(), getSourceName(), ConnectionStatusEventType.IDLE, message.clientAddress + ":" + message.clientPort, (Boolean) null));
        }
    }

    private Map<String, Object> sourceMap(SmtpMessage m) {
        Map<String, Object> map = new HashMap<String, Object>();
        map.put("remoteAddress", m.clientAddress);
        map.put("remotePort", m.clientPort);
        map.put("localAddress", m.localAddress);
        map.put("localPort", m.localPort);
        map.put("helo", m.helo);
        map.put("mailFrom", m.mailFrom);
        map.put("rcptTo", String.join(",", m.recipients));
        map.put("authUser", m.authenticatedUser == null ? "" : m.authenticatedUser);
        map.put("tls", m.tls);
        return map;
    }

    private SSLContext createSslContext() throws ConnectorTaskException {
        String path = connectorProperties.getKeystorePath().trim();
        if (path.isEmpty()) {
            throw new ConnectorTaskException("TLS is switched on, but no keystore file is set.");
        }
        try {
            char[] storePassword = connectorProperties.getKeystorePassword().toCharArray();
            char[] keyPassword = connectorProperties.getKeyPassword().isEmpty() ? storePassword : connectorProperties.getKeyPassword().toCharArray();

            KeyStore keyStore = KeyStore.getInstance(connectorProperties.getKeystoreType());
            try (InputStream in = new FileInputStream(path)) {
                keyStore.load(in, storePassword);
            }
            KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            factory.init(keyStore, keyPassword);

            SSLContext context = SSLContext.getInstance("TLS");
            context.init(factory.getKeyManagers(), null, null);
            return context;
        } catch (Exception e) {
            throw new ConnectorTaskException("Could not load the keystore \"" + path + "\": " + e.getMessage(), e);
        }
    }

    private static int number(String name, String value, int min, int max) throws ConnectorTaskException {
        try {
            int n = Integer.parseInt(value == null ? "" : value.trim());
            if (n >= min && n <= max) {
                return n;
            }
        } catch (NumberFormatException e) {
            // reported below
        }
        throw new ConnectorTaskException(name + " must be a number from " + min + " to " + max + ", not \"" + value + "\".");
    }

    private static String localHostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "localhost";
        }
    }

    private void reportError(String message, Throwable t) {
        eventController.dispatchEvent(new ErrorEvent(getChannelId(), getMetaDataId(), null, ErrorEventType.SOURCE_CONNECTOR, getSourceName(), connectorProperties.getName(), message, t));
        logger.error(message + " (channel " + getChannelId() + ")", t);
    }

    /** Sends what the SMTP server reports to the dashboard and the log. */
    private class EngineEvents implements SmtpServer.Events {
        @Override
        public void connected(String remote) {
            eventController.dispatchEvent(new ConnectorCountEvent(getChannelId(), getMetaDataId(), getSourceName(), ConnectionStatusEventType.CONNECTED, remote, true));
        }

        @Override
        public void disconnected(String remote) {
            eventController.dispatchEvent(new ConnectorCountEvent(getChannelId(), getMetaDataId(), getSourceName(), ConnectionStatusEventType.DISCONNECTED, remote, false));
        }

        @Override
        public void info(String text) {
            eventController.dispatchEvent(new ConnectionStatusEvent(getChannelId(), getMetaDataId(), getSourceName(), ConnectionStatusEventType.INFO, text));
            logger.info(text);
        }

        @Override
        public void failure(String text, Throwable t) {
            reportError(text, t);
        }
    }
}
