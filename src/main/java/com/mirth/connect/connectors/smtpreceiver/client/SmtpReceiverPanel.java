/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.mirth.connect.connectors.smtpreceiver.client;

import java.nio.charset.Charset;

import javax.swing.DefaultComboBoxModel;
import javax.swing.JLabel;
import javax.swing.JScrollPane;

import net.miginfocom.swing.MigLayout;

import com.mirth.connect.client.ui.UIConstants;
import com.mirth.connect.client.ui.components.MirthCheckBox;
import com.mirth.connect.client.ui.components.MirthComboBox;
import com.mirth.connect.client.ui.components.MirthPasswordField;
import com.mirth.connect.client.ui.components.MirthTextArea;
import com.mirth.connect.client.ui.components.MirthTextField;
import com.mirth.connect.client.ui.panels.connectors.ConnectorSettingsPanel;
import com.mirth.connect.connectors.smtpreceiver.shared.SmtpReceiverProperties;
import com.mirth.connect.donkey.model.channel.ConnectorProperties;

/** Swing settings panel of the "SMTP Receiver" source connector. */
public class SmtpReceiverPanel extends ConnectorSettingsPanel {
    /**
     * MirthPasswordField overrides isVisible(), so setVisible(false) does not hide it from the layout.
     * This one can be hidden like any other component.
     */
    private static class HideablePasswordField extends MirthPasswordField {
        private boolean shown = true;

        @Override
        public boolean isVisible() {
            return shown && super.isVisible();
        }

        @Override
        public void setVisible(boolean visible) {
            shown = visible;
            super.setVisible(visible);
        }
    }

    private static final String[] TLS_LABELS = { "None", "STARTTLS", "Implicit TLS (SMTPS)" };
    private static final String[] TLS_VALUES = { SmtpReceiverProperties.TLS_NONE, SmtpReceiverProperties.TLS_STARTTLS, SmtpReceiverProperties.TLS_IMPLICIT };
    private static final String[] FORMAT_LABELS = { "RFC 822 message", "JSON" };
    private static final String[] FORMAT_VALUES = { SmtpReceiverProperties.FORMAT_RFC822, SmtpReceiverProperties.FORMAT_JSON };

    public SmtpReceiverPanel() {
        initComponents();
        initLayout();
        updateVisibility();
    }

    @Override
    public String getConnectorName() {
        return new SmtpReceiverProperties().getName();
    }

    @Override
    public ConnectorProperties getProperties() {
        SmtpReceiverProperties properties = new SmtpReceiverProperties();

        // Local Address and Local Port are not here: the client shows its own Listener Settings and fills them in.
        properties.setHostname(hostnameField.getText().trim());
        properties.setRequireAuthentication(requireAuthCheckBox.isSelected());
        properties.setUsers(usersArea.getText());

        properties.setTlsMode(TLS_VALUES[Math.max(0, tlsComboBox.getSelectedIndex())]);
        properties.setKeystorePath(keystorePathField.getText().trim());
        properties.setKeystorePassword(new String(keystorePasswordField.getPassword()));
        properties.setKeyPassword(new String(keyPasswordField.getPassword()));
        properties.setKeystoreType((String) keystoreTypeComboBox.getSelectedItem());

        properties.setMaxMessageSize(maxSizeField.getText().trim());
        properties.setMaxConnections(maxConnectionsField.getText().trim());
        properties.setMaxRecipients(maxRecipientsField.getText().trim());
        properties.setTimeout(timeoutField.getText().trim());

        properties.setMessageFormat(FORMAT_VALUES[Math.max(0, formatComboBox.getSelectedIndex())]);
        properties.setCharset(charsetField.getText().trim());

        return properties;
    }

    @Override
    public void setProperties(ConnectorProperties properties) {
        SmtpReceiverProperties props = (SmtpReceiverProperties) properties;

        hostnameField.setText(props.getHostname());
        requireAuthCheckBox.setSelected(props.isRequireAuthentication());
        usersArea.setText(props.getUsers());

        tlsComboBox.setSelectedIndex(indexOf(TLS_VALUES, props.getTlsMode()));
        keystorePathField.setText(props.getKeystorePath());
        keystorePasswordField.setText(props.getKeystorePassword());
        keyPasswordField.setText(props.getKeyPassword());
        keystoreTypeComboBox.setSelectedItem(props.getKeystoreType());

        maxSizeField.setText(props.getMaxMessageSize());
        maxConnectionsField.setText(props.getMaxConnections());
        maxRecipientsField.setText(props.getMaxRecipients());
        timeoutField.setText(props.getTimeout());

        formatComboBox.setSelectedIndex(indexOf(FORMAT_VALUES, props.getMessageFormat()));
        charsetField.setText(props.getCharset());

        updateVisibility();
    }

    @Override
    public ConnectorProperties getDefaults() {
        return new SmtpReceiverProperties();
    }

    @Override
    public boolean checkProperties(ConnectorProperties properties, boolean highlight) {
        SmtpReceiverProperties props = (SmtpReceiverProperties) properties;

        boolean valid = true;

        // The client checks that Local Address and Local Port are filled in; a number out of range is refused here.
        String port = props.getListenerConnectorProperties().getPort();
        if (!isNumber(port, 1, 65535) && !port.contains("${")) {
            valid = false;
        }

        valid &= check(maxSizeField, props.getMaxMessageSize(), 0, 2000, highlight);
        valid &= check(maxConnectionsField, props.getMaxConnections(), 1, 10000, highlight);
        valid &= check(maxRecipientsField, props.getMaxRecipients(), 1, 100000, highlight);
        valid &= check(timeoutField, props.getTimeout(), 1, 86400, highlight);

        boolean usersValid = true;
        try {
            usersValid = !props.parseUsers().isEmpty() || !props.isRequireAuthentication();
        } catch (IllegalArgumentException e) {
            usersValid = false;
        }
        if (!usersValid) {
            valid = false;
            if (highlight) {
                usersArea.setBackground(UIConstants.INVALID_COLOR);
            }
        }

        if (!SmtpReceiverProperties.TLS_NONE.equals(props.getTlsMode()) && props.getKeystorePath().trim().isEmpty()) {
            valid = false;
            if (highlight) {
                keystorePathField.setBackground(UIConstants.INVALID_COLOR);
            }
        }

        if (!isCharset(props.getCharset())) {
            valid = false;
            if (highlight) {
                charsetField.setBackground(UIConstants.INVALID_COLOR);
            }
        }

        return valid;
    }

    @Override
    public void resetInvalidProperties() {
        usersArea.setBackground(null);
        keystorePathField.setBackground(null);
        maxSizeField.setBackground(null);
        maxConnectionsField.setBackground(null);
        maxRecipientsField.setBackground(null);
        timeoutField.setBackground(null);
        charsetField.setBackground(null);
    }

    private boolean check(MirthTextField field, String value, int min, int max, boolean highlight) {
        if (isNumber(value, min, max)) {
            return true;
        }
        if (highlight) {
            field.setBackground(UIConstants.INVALID_COLOR);
        }
        return false;
    }

    private static boolean isNumber(String value, int min, int max) {
        try {
            int n = Integer.parseInt(value.trim());
            return n >= min && n <= max;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean isCharset(String name) {
        try {
            return Charset.isSupported(name);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static int indexOf(String[] values, String value) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(value)) {
                return i;
            }
        }
        return 0;
    }

    /** The keystore fields only matter when TLS is on. */
    private void updateVisibility() {
        boolean tls = tlsComboBox.getSelectedIndex() > 0;
        keystorePathLabel.setVisible(tls);
        keystorePathField.setVisible(tls);
        keystorePasswordLabel.setVisible(tls);
        keystorePasswordField.setVisible(tls);
        keyPasswordLabel.setVisible(tls);
        keyPasswordField.setVisible(tls);
        keystoreTypeLabel.setVisible(tls);
        keystoreTypeComboBox.setVisible(tls);
        revalidate();
        repaint();
    }

    private void initComponents() {
        setBackground(UIConstants.BACKGROUND_COLOR);

        hostnameLabel = new JLabel("Hostname:");
        hostnameField = new MirthTextField();
        hostnameField.setToolTipText("Name in the greeting of the server. Empty: the name of this machine.");

        requireAuthLabel = new JLabel("Authentication:");
        requireAuthCheckBox = new MirthCheckBox("Require a login (AUTH PLAIN or LOGIN)");
        requireAuthCheckBox.setBackground(UIConstants.BACKGROUND_COLOR);
        requireAuthCheckBox.setToolTipText("Without a login, mail is only accepted from clients that authenticate. Switch this off only on a network you trust.");

        usersLabel = new JLabel("Users:");
        usersArea = new MirthTextArea();
        usersArea.setToolTipText("One username:password per line. Lines starting with # are ignored. The password may contain colons.");
        usersScroll = new JScrollPane(usersArea);

        tlsLabel = new JLabel("TLS:");
        tlsComboBox = new MirthComboBox<String>();
        tlsComboBox.setModel(new DefaultComboBoxModel<String>(TLS_LABELS));
        tlsComboBox.setToolTipText("STARTTLS: the client upgrades the connection (usually port 587 or 25). Implicit TLS: encrypted from the first byte (usually port 465). With TLS, logins are only accepted over the encrypted connection.");
        tlsComboBox.addActionListener(evt -> updateVisibility());

        keystorePathLabel = new JLabel("Keystore file:");
        keystorePathField = new MirthTextField();
        keystorePathField.setToolTipText("Path of the keystore with the server certificate and its private key");

        keystorePasswordLabel = new JLabel("Keystore password:");
        keystorePasswordField = new HideablePasswordField();

        keyPasswordLabel = new JLabel("Key password:");
        keyPasswordField = new HideablePasswordField();
        keyPasswordField.setToolTipText("Password of the private key. Empty: same as the keystore password.");

        keystoreTypeLabel = new JLabel("Keystore type:");
        keystoreTypeComboBox = new MirthComboBox<String>();
        keystoreTypeComboBox.setModel(new DefaultComboBoxModel<String>(new String[] { "PKCS12", "JKS", "JCEKS" }));

        maxSizeLabel = new JLabel("Max message size (MB):");
        maxSizeField = new MirthTextField();
        maxSizeField.setToolTipText("Larger messages are refused with 552. 0 = no limit.");

        maxConnectionsLabel = new JLabel("Max connections:");
        maxConnectionsField = new MirthTextField();
        maxConnectionsField.setToolTipText("Clients that connect beyond this number get 421 and are asked to try again later");

        maxRecipientsLabel = new JLabel("Max recipients:");
        maxRecipientsField = new MirthTextField();
        maxRecipientsField.setToolTipText("Per message");

        timeoutLabel = new JLabel("Timeout (seconds):");
        timeoutField = new MirthTextField();
        timeoutField.setToolTipText("A client that stays silent this long is disconnected");

        formatLabel = new JLabel("Message format:");
        formatComboBox = new MirthComboBox<String>();
        formatComboBox.setModel(new DefaultComboBoxModel<String>(FORMAT_LABELS));
        formatComboBox.setToolTipText("RFC 822: the mail as it was sent (headers, empty line, body), also for the message in the channel. JSON: the envelope and the data as a list of lines.");

        charsetLabel = new JLabel("Character set:");
        charsetField = new MirthTextField();
        charsetField.setToolTipText("Used to turn the bytes of the mail into text, for example UTF-8 or ISO-8859-1");
    }

    private void initLayout() {
        // No "fill": with it, MigLayout stretches every row and column over the free space.
        setLayout(new MigLayout("insets 0, novisualpadding, hidemode 3, gap 6 6", "6[]13[]"));

        add(hostnameLabel, "right");
        add(hostnameField, "w 200!, wrap");
        add(requireAuthLabel, "right");
        add(requireAuthCheckBox, "wrap");
        add(usersLabel, "right, top");
        add(usersScroll, "w 300!, h 70!, wrap");
        add(tlsLabel, "right");
        add(tlsComboBox, "w 170!, wrap");
        add(keystorePathLabel, "right");
        add(keystorePathField, "w 300!, wrap");
        add(keystorePasswordLabel, "right");
        add(keystorePasswordField, "w 200!, wrap");
        add(keyPasswordLabel, "right");
        add(keyPasswordField, "w 200!, wrap");
        add(keystoreTypeLabel, "right");
        add(keystoreTypeComboBox, "w 100!, wrap");
        add(maxSizeLabel, "right");
        add(maxSizeField, "w 75!, wrap");
        add(maxConnectionsLabel, "right");
        add(maxConnectionsField, "w 75!, wrap");
        add(maxRecipientsLabel, "right");
        add(maxRecipientsField, "w 75!, wrap");
        add(timeoutLabel, "right");
        add(timeoutField, "w 75!, wrap");
        add(formatLabel, "right");
        add(formatComboBox, "w 170!, wrap");
        add(charsetLabel, "right");
        add(charsetField, "w 100!, wrap");
    }

    private JLabel hostnameLabel;
    private MirthTextField hostnameField;
    private JLabel requireAuthLabel;
    private MirthCheckBox requireAuthCheckBox;
    private JLabel usersLabel;
    private MirthTextArea usersArea;
    private JScrollPane usersScroll;
    private JLabel tlsLabel;
    private MirthComboBox<String> tlsComboBox;
    private JLabel keystorePathLabel;
    private MirthTextField keystorePathField;
    private JLabel keystorePasswordLabel;
    private HideablePasswordField keystorePasswordField;
    private JLabel keyPasswordLabel;
    private HideablePasswordField keyPasswordField;
    private JLabel keystoreTypeLabel;
    private MirthComboBox<String> keystoreTypeComboBox;
    private JLabel maxSizeLabel;
    private MirthTextField maxSizeField;
    private JLabel maxConnectionsLabel;
    private MirthTextField maxConnectionsField;
    private JLabel maxRecipientsLabel;
    private MirthTextField maxRecipientsField;
    private JLabel timeoutLabel;
    private MirthTextField timeoutField;
    private JLabel formatLabel;
    private MirthComboBox<String> formatComboBox;
    private JLabel charsetLabel;
    private MirthTextField charsetField;
}
