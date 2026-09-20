// SMTP Receiver source connector - settings panel for the web administrator.
// Engine-hosted plugins are loaded as a single blob module, so only bare @oie/*
// imports (resolved through the page's import map) are available. The form
// building blocks come from @oie/web-ui; React comes from the platform object.
import {
  ConnectorForm,
  portsInUseButton,
  listenerAddressField,
  asBool,
  YES_NO,
  requireFields,
  defaultSourceProperties,
  defaultListenerProperties
} from "@oie/web-ui";

const PROPERTIES_CLASS = "com.mirth.connect.connectors.smtpreceiver.shared.SmtpReceiverProperties";
const CONNECTOR_NAME = "SMTP Receiver";

const TLS_OPTIONS = [
  { value: "NONE", label: "None" },
  { value: "STARTTLS", label: "STARTTLS" },
  { value: "IMPLICIT", label: "Implicit TLS (SMTPS)" }
];
const FORMAT_OPTIONS = [
  { value: "RFC822", label: "Plain text (RFC 822)" },
  { value: "JSON", label: "JSON" },
  { value: "XML", label: "XML" }
];
const KEYSTORE_TYPES = ["PKCS12", "JKS", "JCEKS"];

// Only JSON and XML take the mail apart, so only they have attachments to put in.
const isParsed = (p) => p.messageFormat === "JSON" || p.messageFormat === "XML";
const usesTls = (p) => p.tlsMode === "STARTTLS" || p.tlsMode === "IMPLICIT";

const isNumber = (value, min, max) => {
  const text = String(value ?? "").trim();
  if (!/^\d+$/.test(text)) return false;
  const n = Number(text);
  return n >= min && n <= max;
};

// Mirrors SmtpReceiverProperties.parseUsers: one "username:password" per line, # starts a comment.
export function parseUsers(text) {
  const users = [];
  const errors = [];
  String(text ?? "").split(/\r?\n/).forEach((line, i) => {
    const trimmed = line.trim();
    if (trimmed === "" || trimmed.startsWith("#")) return;
    const colon = trimmed.indexOf(":");
    if (colon < 1) {
      errors.push(`line ${i + 1}`);
      return;
    }
    users.push({ user: trimmed.slice(0, colon).trim(), password: trimmed.slice(colon + 1).trim() });
  });
  return { users, errors };
}

function smtpAddress(p) {
  const listener = p.listenerConnectorProperties || {};
  const rawHost = String(listener.host ?? "").trim();
  const host = !rawHost || rawHost === "0.0.0.0" ? window.location.hostname : rawHost;
  return `${host}:${listener.port ?? ""}`;
}

export function createSmtpReceiver(platform) {
  return {
    defaults(version) {
      return {
        "@class": PROPERTIES_CLASS,
        "@version": version,
        pluginProperties: null,
        listenerConnectorProperties: defaultListenerProperties(version, "2525"),
        sourceConnectorProperties: defaultSourceProperties(version),
        hostname: "",
        requireAuthentication: true,
        users: "",
        tlsMode: "NONE",
        keystorePath: "",
        keystorePassword: "",
        keyPassword: "",
        keystoreType: "PKCS12",
        maxMessageSize: "25",
        maxConnections: "10",
        maxRecipients: "100",
        timeout: "120",
        messageFormat: "RFC822",
        includeAttachmentContent: true,
        charset: "UTF-8"
      };
    },

    component({ properties, onChange }) {
      // platform.React is set by the shell at boot, so read it at render time.
      const React = platform.React;
      return React.createElement(ConnectorForm, {
        properties,
        onChange,
        fields: [
          { section: "Listener Settings" },
          listenerAddressField("listenerConnectorProperties.host", "Local Address"),
          { key: "listenerConnectorProperties.port", label: "Local Port", type: "number", width: "90px", append: () => portsInUseButton() },
          { type: "display", label: "SMTP Address", compute: smtpAddress },

          { section: "SMTP Server" },
          { key: "hostname", label: "Hostname", type: "text", width: "220px", tooltip: "Name in the greeting of the server. Empty: the name of this machine." },
          {
            key: "requireAuthentication",
            label: "Require Login",
            type: "radio",
            options: YES_NO,
            tooltip: "Without a login, mail is only accepted from clients that authenticate (AUTH PLAIN or LOGIN). Switch this off only on a network you trust."
          },
          {
            key: "users",
            label: "Users",
            type: "textarea",
            rows: 4,
            tooltip: "One username:password per line. Lines starting with # are ignored. The password may contain colons."
          },
          {
            key: "tlsMode",
            label: "TLS",
            type: "select",
            width: "220px",
            options: TLS_OPTIONS,
            refresh: true,
            tooltip: "STARTTLS: the client upgrades the connection (usually port 587 or 25). Implicit TLS: encrypted from the first byte (usually port 465). With TLS, logins are only accepted over the encrypted connection."
          },
          { key: "keystorePath", label: "Keystore File", type: "text", width: "320px", visible: usesTls, tooltip: "Path of the keystore with the server certificate and its private key" },
          { key: "keystorePassword", label: "Keystore Password", type: "password", width: "220px", visible: usesTls },
          { key: "keyPassword", label: "Key Password", type: "password", width: "220px", visible: usesTls, tooltip: "Password of the private key. Empty: same as the keystore password." },
          { key: "keystoreType", label: "Keystore Type", type: "select", width: "120px", options: KEYSTORE_TYPES, visible: usesTls },

          { section: "Limits" },
          { key: "maxMessageSize", label: "Max Message Size (MB)", type: "number", width: "90px", tooltip: "Larger messages are refused with 552. 0 = no limit." },
          { key: "maxConnections", label: "Max Connections", type: "number", width: "90px", tooltip: "Clients that connect beyond this number get 421 and are asked to try again later" },
          { key: "maxRecipients", label: "Max Recipients", type: "number", width: "90px", tooltip: "Per message" },
          { key: "timeout", label: "Timeout (seconds)", type: "number", width: "90px", tooltip: "A client that stays silent this long is disconnected" },

          { section: "Message" },
          {
            key: "messageFormat",
            label: "Message Format",
            type: "select",
            width: "220px",
            options: FORMAT_OPTIONS,
            refresh: true,
            tooltip: "Plain text: the mail exactly as it was sent (headers, empty line, body). JSON or XML: the mail taken apart into subject, from, to, date, headers, text, html and attachments, with the envelope."
          },
          {
            key: "includeAttachmentContent",
            label: "Attachment Content",
            type: "radio",
            options: YES_NO,
            visible: isParsed,
            tooltip: "Put the attachments themselves in the message (Base64). With No only their name, type and size are listed."
          },
          { key: "charset", label: "Character Set", type: "text", width: "120px", tooltip: "Plain text: how the bytes of the mail become text, for example UTF-8 or ISO-8859-1. JSON and XML: used for parts of the mail that do not name their own character set." }
        ]
      });
    },

    // Mirrors the Swing panel (SmtpReceiverPanel.checkProperties).
    validate(properties) {
      const errors = requireFields(properties, [
        { key: "listenerConnectorProperties.host", label: "Local Address" },
        { key: "listenerConnectorProperties.port", label: "Local Port" },
        { key: "charset", label: "Character Set" },
        { key: "keystorePath", label: "Keystore File", when: usesTls }
      ]);

      const numbers = [
        { key: "listenerConnectorProperties.port", label: "Local Port (1-65535)", value: properties.listenerConnectorProperties?.port, min: 1, max: 65535, skipTemplates: true },
        { key: "maxMessageSize", label: "Max Message Size (0-2000)", value: properties.maxMessageSize, min: 0, max: 2000 },
        { key: "maxConnections", label: "Max Connections (1-10000)", value: properties.maxConnections, min: 1, max: 10000 },
        { key: "maxRecipients", label: "Max Recipients (1-100000)", value: properties.maxRecipients, min: 1, max: 100000 },
        { key: "timeout", label: "Timeout (1-86400)", value: properties.timeout, min: 1, max: 86400 }
      ];
      for (const n of numbers) {
        if (errors.some((e) => e.key === n.key)) continue;
        if (n.skipTemplates && String(n.value ?? "").includes("${")) continue;
        if (!isNumber(n.value, n.min, n.max)) errors.push({ key: n.key, label: n.label });
      }

      const { users, errors: badLines } = parseUsers(properties.users);
      if (badLines.length > 0) {
        errors.push({ key: "users", label: `Users (${badLines.join(", ")}: expected username:password)` });
      } else if (asBool(properties.requireAuthentication) && users.length === 0) {
        errors.push({ key: "users", label: "Users (at least one is needed when a login is required)" });
      }
      return errors;
    }
  };
}

export function register(platform) {
  platform.registerConnectorPanel(CONNECTOR_NAME, "SOURCE", createSmtpReceiver(platform));
}
