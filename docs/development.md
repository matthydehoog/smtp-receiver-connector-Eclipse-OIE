# Development guide

This guide is for people who build, change or release the SMTP Receiver. For using the connector in a channel, see the [README](../README.md).

## Contents

- [Repository layout](#repository-layout)
- [Building](#building)
- [How the connector is put together](#how-the-connector-is-put-together)
- [Life cycle of the connector](#life-cycle-of-the-connector)
- [The SMTP server](#the-smtp-server)
- [A session, step by step](#a-session-step-by-step)
- [From the channel's result to the SMTP reply](#from-the-channels-result-to-the-smtp-reply)
- [Message formats](#message-formats)
- [TLS](#tls)
- [Properties and saved channels](#properties-and-saved-channels)
- [The settings panels](#the-settings-panels)
- [Logging and status](#logging-and-status)
- [Testing](#testing)
- [Trying it in OIE](#trying-it-in-oie)
- [GitHub workflows](#github-workflows)
- [Releasing](#releasing)
- [Conventions](#conventions)

## Repository layout

| Path | What it is |
| --- | --- |
| `src/main/java/.../smtpreceiver/shared/` | `SmtpReceiverProperties`: the settings, used by the server and both administrators. `smtpreceiver-shared.jar`. |
| `src/main/java/.../smtpreceiver/server/` | The connector (`SmtpReceiver`), the SMTP server and the message formatting. `smtpreceiver-server.jar`. |
| `src/main/java/.../smtpreceiver/client/` | `SmtpReceiverPanel`, the Swing settings panel. `smtpreceiver-client.jar`. |
| `src/main/resources/source.xml` | The connector descriptor the engine reads. |
| `src/test/` | JUnit 4 tests, including a real SMTP client against the server. |
| `webadmin/` | The settings panel for the OIE web administrator (`plugin.json`, `web/plugin.js`). |
| `examples/Send-TestMail.ps1` | Sends a test mail, with or without login and STARTTLS. |
| `assembly.xml` | How the extension zip is laid out. |
| `oie.json` | The manifest for the OIE Community Store catalog. |
| `.github/` | The catalog and Jira workflows and their scripts. |

`...` is `com/mirth/connect/connectors`.

## Building

### Prerequisites

- **JDK 11 or later.** The engine's own runtime (`<OIE_HOME>/jre`) includes `javac` and `keytool` and works as `JAVA_HOME`. The tests run `keytool` from `java.home` to create a test certificate.
- **Maven 3.9** or later.
- **The OIE engine jars** in your local Maven repository, version `oie.version` from `pom.xml` (now 4.6.0). They are not in Maven Central; install them from an OIE installation:

```bash
OIE_HOME="/opt/openintegrationengine"   # or "C:/Program Files/OpenIntegrationEngine"
install() {
  mvn install:install-file -Dfile="$OIE_HOME/$2" -DgroupId=com.mirth.connect -DartifactId="$1" -Dversion=4.6.0 -Dpackaging=jar
}
install server-api    server-lib/mirth-server.jar
install donkey-server server-lib/donkey/donkey-server.jar
install donkey-model  server-lib/donkey/donkey-model.jar
install client        client-lib/mirth-client.jar
install client-core   client-lib/mirth-client-core.jar
```

### Build

```bash
mvn clean package
```

The result is `target/smtp-receiver-connector-<version>.zip`. `package` runs the tests first, which start the server on free ports and talk to it with a real SMTP client; add `-DskipTests` to skip them, or `-o` to build offline.

### Signing

Some Administrator launchers refuse extension jars that are not signed, so releases sign the shared, server and client jars (`maven-jarsigner-plugin`, just before the zip is made) with the X Connections code-signing key: a self-signed certificate, timestamped by DigiCert so the signature stays valid after the certificate expires.

The Mirth Connect Administrator Launcher (MCAL, NextGen, not open source) refuses self-signed jars unless it runs with `-Dallow-self-signed` in its `launcher.vmoptions`; the OIE and BridgeLink launchers don't check signatures. See *Administrator launchers* in the README.

Signing is off unless a keystore is given:

```bash
mvn clean package -Dsign.keystore=/path/to/codesign.p12 -Dsign.storepass=...
```

or with `sign.skip=false`, `sign.keystore` and `sign.storepass` in a profile of `~/.m2/settings.xml`. The alias is `x-connections` (`-Dsign.alias` to change it). The build verifies the signatures and fails if one is missing. Check a zip by hand with `jarsigner -verify -certs smtpreceiver-client.jar`.

There is no build workflow, so build the release zip on a machine that has the key, and check it with `jarsigner` before you publish it. The keystore itself is never committed.

### What is in the zip

```
smtp-receiver/
├── source.xml
├── smtpreceiver-shared.jar     smtpreceiver/shared/**
├── smtpreceiver-server.jar     smtpreceiver/server/**
├── smtpreceiver-client.jar     smtpreceiver/client/**
├── webadmin/                   the web administrator panel
└── lib/                        jakarta.mail-api, jakarta.activation-api, angus-mail, angus-activation
```

The folder name `smtp-receiver` must equal the `path` attribute in `source.xml`: the engine's client download servlet and the web administrator build URLs from it.

**The `lib/` jar names are in `source.xml`, with their versions** (`lib/angus-mail-2.0.3.jar`). `assembly.xml` copies the runtime dependencies with their versions in the file name, so **when you change a mail library version in `pom.xml`, update the `library` paths in `source.xml`** too, and compare the zip's `lib/` with `source.xml` after the build.

## How the connector is put together

`source.xml` (a `connectorMetaData`):

| Element | Value | Purpose |
| --- | --- | --- |
| `name` | `SMTP Receiver` | The name in the connector type list. Must equal `SmtpReceiverProperties.NAME`. |
| `sharedClassName` | `...smtpreceiver.shared.SmtpReceiverProperties` | The settings class. |
| `serverClassName` | `...smtpreceiver.server.SmtpReceiver` | The source connector. |
| `clientClassName` | `...smtpreceiver.client.SmtpReceiverPanel` | The Swing settings panel. |
| `library` | the three jars and the four `lib/` jars | What the engine puts on the classpath, per side. |
| `type` | `SOURCE` | A source connector only. |
| `mirthVersion` | `4.6.0,4.7.0` | The engine versions it may load on. |

The server classes are split in two layers:

| Layer | Classes | Knows the engine? |
| --- | --- | --- |
| Connector | `SmtpReceiver` | Yes: a `SourceConnector` that turns settings into an `SmtpConfig`, messages into `RawMessage`s and the channel's result into an `SmtpReply`. |
| SMTP | `SmtpServer`, `SmtpSession`, `SmtpConfig`, `SmtpMessage`, `SmtpReply` | No. The server calls a `Handler` for every complete message and reports through an `Events` interface. |
| Formatting | `SmtpMessageFormatter`, `EmailParser`, `ParsedEmail`, `HtmlText` | No. |

That split is what makes the protocol testable on its own, with real clients and without an engine.

**The package and the names are the connector's identity.** The settings class must be in the `com.mirth.connect.connectors.*` family: the engine's XStream allow-list only accepts that family, and a class outside it makes the Swing client unable to read the channel. A channel stores the connector name and the class names, so renaming any of them breaks existing channels.

## Life cycle of the connector

| Engine call | What `SmtpReceiver` does |
| --- | --- |
| `onDeploy` | Reads the settings into an `SmtpConfig`: replaces templates such as `${smtpPort}` in address and port, checks every number against its range, parses the users, builds the `SSLContext` when TLS is on, checks the character set. Any problem throws a `ConnectorTaskException` with a clear message, so the channel does not deploy. |
| `onStart` | Creates the `SmtpServer` and binds the port. A `BindException` is retried up to 10 times, 500 ms apart, because a channel that restarts quickly may find its port still held for a moment. Reports *Listening on address:port (TLS mode)* to the dashboard's connection log. |
| `onStop` | `server.stop(5000)`: stops accepting, tells waiting clients `421`, lets a mail that is being processed finish for up to 5 seconds, then closes the rest. Reports *Stopped listening on port …*. |
| `onHalt` | `server.halt()`: closes everything at once. |
| `handleRecoveredResponse` | Only `finishDispatch`: the client that waited for the answer is long gone. |

## The SMTP server

`SmtpServer` owns the listening socket, an acceptor thread and a thread pool for the sessions:

- The listening socket is an `SSLServerSocket` for implicit TLS, otherwise a plain `ServerSocket`, with a backlog of 256.
- A semaphore with *Max connections* permits limits the sessions. A client beyond the limit gets `421` and is closed (`refuse`).
- Each accepted client becomes an `SmtpSession`, run on the pool; the permit is released when the session ends.
- `stop(grace)` and `halt()` differ in how they treat running sessions (see the life cycle above). `getPort()` returns the real port, which matters for port 0 in the tests.
- Only TLS 1.3 and 1.2 are enabled, as far as the JVM supports them.

## A session, step by step

`SmtpSession` implements the dialogue of RFC 5321, with AUTH PLAIN and LOGIN (RFC 4954) and STARTTLS (RFC 3207):

1. Greeting `220 <hostname> ESMTP ready`.
2. `EHLO` lists the capabilities: `PIPELINING`, `8BITMIME`, `ENHANCEDSTATUSCODES`, `SIZE <bytes>` (when there is a maximum), `STARTTLS` (when configured and not yet active) and `AUTH PLAIN LOGIN` (when users exist and, with TLS on, only once the connection is encrypted).
3. `STARTTLS` upgrades the socket. Anything the client sent behind it in the same packet is refused, because it would otherwise be read as if it came over TLS (CVE-2011-0411).
4. `AUTH`: PLAIN (inline or in a second step) or LOGIN (user and password asked for). Not allowed during a mail transaction. Three wrong logins close the connection. A wrong password and an unknown user get the same answer, so user names cannot be guessed.
5. `MAIL FROM`: with login required, only after a login. The `SIZE` parameter is checked against the maximum right away. Addresses with and without angle brackets are accepted; case is kept; `<>` (a bounce) is an empty sender.
6. `RCPT TO`: any address (this is a receiver, not a relay), up to *Max recipients*.
7. `DATA`: `354`, then lines until `.` on its own line. Dot-unstuffing restores lines that started with a dot; lone LF line endings are accepted and stored as CRLF; 8-bit data is kept as bytes. Beyond the maximum size the rest is read and thrown away, and the client gets `552`; the connection stays usable.
8. The complete message goes to the `Handler`; its `SmtpReply` is what the client gets. Line breaks are removed from reply texts, so a channel cannot break the protocol.
9. The envelope is reset after every message; one connection can send many.

Limits that protect the server: command lines up to 4096 bytes, at most 20 errors per session, and the *Timeout* for a silent client. `VRFY` answers `252` without revealing anything.

`SmtpMessage` carries the envelope (client and local address and port, HELO name, sender, recipients, user, TLS) and the data, dot-unstuffed with CRLF line endings.

## From the channel's result to the SMTP reply

`SmtpReceiver.receive` is the `Handler`:

1. Dashboard status *Message received from …, processing…*.
2. `SmtpMessageFormatter.format` builds the text in the chosen format; the envelope goes into the source map (`remoteAddress`, `remotePort`, `localAddress`, `localPort`, `helo`, `mailFrom`, `rcptTo`, `authUser`, `tls`).
3. `dispatchRawMessage`, then:

| Result | Reply |
| --- | --- |
| Accepted | `250 2.0.0 Ok: queued as <message id>` |
| The dispatch result holds a `ChannelException` (could not store) | `451` try again later |
| The processed message ended with status **ERROR** | `451` try again later |
| `dispatchRawMessage` threw a `ChannelException` (channel stopped) | `451`, and a FAILURE status in the dashboard |

4. `finishDispatch` always, and the connection count is updated.

This makes delivery **at least once**: a `451` after an ERROR means the message is in the channel *and* the sender will send it again. The README tells users to filter on `Message-ID` when that matters; keep that behaviour, because the alternative (accepting a mail the channel failed on) loses mail.

## Message formats

| Format | Built by | Content |
| --- | --- | --- |
| `RFC822` | `SmtpMessageFormatter.rfc822` | The data as it was sent, decoded with the configured character set. |
| `JSON` | `SmtpMessageFormatter.json` | The envelope plus the parsed mail. |
| `XML` | `SmtpMessageFormatter.xml` | The same content as JSON, as XML. |

An unknown format falls back to plain text.

**JSON and XML are written by hand**, without a JSON or XML library: escaping (including characters that are illegal in XML) is done in the formatter. Gson is a test dependency only, used to check the JSON. When you add a field, add it to both `json` and `xml`, to the tests of both, and to the README's field table.

**`EmailParser`** takes the mail apart with the bundled Jakarta Mail and returns a `ParsedEmail`:

- headers in order, decoded (`=?UTF-8?Q?…?=`), and `messageId`, `date` (UTC, ISO 8601), `subject`, `from`, `to`, `cc`, `replyTo`;
- an address that cannot be parsed is kept as written;
- `text` and `html`: several parts of one kind are joined with a newline; an HTML-only mail also gets a `text`, made by `HtmlText`;
- attachments, including inline images (with `contentId`) and attached mails (`message/rfc822`), with their bytes in Base64 unless *Attachment content* is off;
- a part without a charset uses the configured character set.

`EmailParser` **never throws**: a mail it cannot read gets `parseError`, and its raw text goes into `text`, so nothing is lost.

As in the Email Reader, parts are read through their input streams and never with `getContent()`: the engine ships an older mail library whose mailcap handlers clash with the bundled `jakarta.activation`. Keep it that way.

## TLS

- *TLS*: `NONE`, `STARTTLS` (upgrade on request) or `IMPLICIT` (an `SSLServerSocket`, encrypted from the first byte).
- `SmtpReceiver.createSslContext` loads the keystore (`PKCS12`, `JKS` or `JCEKS`) from the file path, with the key password defaulting to the keystore password, and initialises a `KeyManagerFactory`. A missing file, wrong password or wrong type stops the deploy with the reason.
- With TLS on, `AUTH` is only offered and accepted over an encrypted connection (`538` otherwise).
- The tests create a throwaway PKCS12 keystore with `keytool` (`CN=localhost`, SAN `127.0.0.1`).

## Properties and saved channels

`SmtpReceiverProperties` extends `ConnectorProperties` and implements the listener and source connector property interfaces, so the engine adds the standard *Local Address / Local Port* and *Source Settings*. The default port is `2525`.

Channels are stored with XStream, which **does not run constructors**: a field added in a later version is `null` in a channel saved before. The getters fall back to the defaults, and unknown values to the safe choice: `getTlsMode()` returns `NONE` for anything but `STARTTLS` and `IMPLICIT`, the message format falls back to plain text, and `includeAttachmentContent` is a `Boolean` that means *on* when missing (it was added in 2.0.0).

Numbers (size, connections, recipients, timeout) are stored as strings, as the engine's own connectors do, and checked in `onDeploy`.

`parseUsers()` turns the *Users* text into a map: one `username:password` per line, `#` starts a comment, spaces around both are trimmed, the password may contain colons, an empty password is allowed but an empty user name is not.

`getPurgedProperties()` (used for the engine's usage statistics) must never contain secrets: users, passwords and the keystore path stay out; a test checks this.

### Adding a setting

1. Add the field, its default in the constructor and a null-safe getter to `SmtpReceiverProperties`. A setting that defaults to *on* must be a `Boolean` whose getter returns `true` for `null`.
2. If it is a secret, keep it out of `getPurgedProperties()`.
3. Use it in `SmtpReceiver.onDeploy` (validate it there) and pass it on through `SmtpConfig` when the SMTP layer needs it.
4. Add it to the Swing panel (`SmtpReceiverPanel`: field, `getProperties`, `setProperties`, `checkProperties` if it is required).
5. Add it to the web panel (`webadmin/web/plugin.js`: defaults, field, validation).
6. Document it in the README's settings table.
7. Test that a channel saved with the previous version still deploys and behaves as before (`valuesThatAreMissingFromASavedChannelFallBackToDefaults`).

## The settings panels

Both panels show the same settings and follow the same rules; there is no shared code, so keep them in sync by hand.

- The keystore fields are shown only when TLS is on. `MirthPasswordField` overrides `isVisible()`, so `setVisible(false)` does not hide it; the Swing panel uses a small subclass, `HideablePasswordField`.
- *Attachment content* is only shown for JSON and XML, the formats that take the mail apart.
- The *Local Address / Local Port* settings come from the engine's listener panel; the Swing panel does not add its own (1.0.1 removed a duplicate).

The web panel is registered with `platform.registerConnectorPanel("SMTP Receiver", "SOURCE", …)`. Engine-hosted web plugins are loaded as a single module, so `plugin.js` may only import the bare `@oie/web-ui` specifier, and it reads React from `platform.React` at render time. Its defaults must produce the same JSON shape as the Java properties.

## Logging and status

- **Dashboard:** the connection count (*Max connections* is the maximum), *Receiving* while a mail is processed, and in the connection log *Listening on …* and *Stopped listening on …*. The engine's log level is ERROR by default, so the dashboard is where users see that the connector is up.
- **Errors** (a keystore that cannot be loaded, a port in use, an error while processing a mail) are error events: dashboard and `mirth.log`.
- **INFO** (failed logins, refused connections, the start-up line) is hidden by the default `log4j2.properties`; the README shows how to switch it on for `com.mirth.connect.connectors.smtpreceiver`.
- Never log passwords or mail content.

## Testing

```bash
mvn test
```

There are 87 tests (JUnit 4). They need no engine and no network beyond `localhost`.

| Test | Covers |
| --- | --- |
| `SmtpServerTest` (56) | The SMTP server with Jakarta Mail as a real client and with raw sockets: delivery, several messages per connection, dots, empty lines, 8-bit data, LF line endings, handler replies and exceptions, AUTH PLAIN and LOGIN (including failures, the three-strikes rule, colons and non-ASCII in passwords), EHLO capabilities, size limits (also for one enormous line and the `SIZE` parameter), recipients, connection limit, timeout, error count, line length, command order and syntax, pipelining, STARTTLS (including commands behind it), implicit TLS, stop, halt, restart, port 0, port in use, and a mail with an attachment as JSON and XML |
| `SmtpMessageFormatterTest` (23) | Plain text, JSON and XML: envelope, headers in order, multipart, attachments with and without content, HTML-only mails, inline images, encoded file names, attached mails, bad addresses, mails without headers, escaping, character sets |
| `SmtpReceiverPropertiesTest` (8) | Defaults, parsing users, fallbacks for saved channels and unknown values, purged properties without secrets |

Tests that need a port use port 0, so they run in parallel with anything else. When you change the protocol, add a test in `SmtpServerTest` with a raw socket if Jakarta Mail would hide the detail.

## Trying it in OIE

1. `mvn clean package`.
2. Install `target/smtp-receiver-connector-<version>.zip` on a **test** server (*Settings > Extensions > Install Extension*) and restart the service. Restart the Swing Administrator; in the web administrator, refresh with Ctrl+F5.
3. Make a channel with source **SMTP Receiver**, a user, and the data type that matches the format (RAW for plain text, JSON or XML).
4. Send mail with `examples/Send-TestMail.ps1` (login, no login, STARTTLS), or with `swaks`, Python's `smtplib` or another channel's SMTP Sender.
5. Check the message and the source map in the message browser, the dashboard's connection log, and what the sender got back. Stop the channel during a send to see `421` and `451`.

For TLS, make a test keystore with `keytool -genkeypair … -storetype PKCS12`; a client will refuse the self-signed certificate unless told to trust it.

## GitHub workflows

There is no build workflow in this repository; build and test locally before you push.

| Workflow | Does |
| --- | --- |
| `catalog-pr.yml` | On a published release, files a pull request to the [OIE Community Store catalog](https://github.com/gibson9583/oie-community-catalog) from the fork `<owner>/oie-community-catalog`, using `oie.json` at the tag. Needs the secret `CATALOG_TOKEN` (a classic PAT with `public_repo`); skipped without it. |
| `jira-issue.yml` | Creates a Jira issue for every new GitHub issue and comments its key. Needs the variables `JIRA_BASE_URL`, `JIRA_EMAIL`, `JIRA_PROJECT_KEY` and the secret `JIRA_API_TOKEN`. |

`main` is protected against force pushes and deletion (ruleset *Protect main*).

## Releasing

1. **Branch and pull request.** Reference the issue (`Closes #n`).
2. **Version.** Set the new version in all four places; nothing checks this automatically, so compare them:
   - `pom.xml` (`<version>`)
   - `src/main/resources/source.xml` (`<pluginVersion>`)
   - `oie.json` (`"version"`)
   - `webadmin/plugin.json` (`"version"`)
3. **Engine versions.** `<mirthVersion>` in `source.xml` lists every OIE version the extension may load on (now `4.6.0,4.7.0`). OIE only loads an extension whose list contains its **exact** version, so a new OIE release needs a new connector release.
4. **Build and test** with `mvn clean package`, and try the zip in OIE.
5. **Merge** the pull request.
6. **Release** on GitHub with the zip and its SHA-256 in the notes:

   ```bash
   sha256sum target/smtp-receiver-connector-2.0.3.zip
   gh release create v2.0.3 target/smtp-receiver-connector-2.0.3.zip --target main \
     --title "2.0.3: …" --notes-file notes.md
   ```

   Release notes say what changed for the user, how to upgrade (replace the extension and restart; channel settings are kept), and the SHA-256.
7. **Catalog.** Publishing the release runs `catalog-pr.yml`. Check its pull request, and close earlier ones of this connector that are still open.
8. **Jira.** Closing a GitHub issue does not close its Jira issue: set it to *Done* yourself.
9. **Documentation.** Update the README when settings, formats, replies or the source map change.

## Conventions

- Every file in `src/main` starts with the MPL 2.0 header.
- Java 11 language level. Runtime dependencies go into `lib/` and into `source.xml`; anything the engine already ships is `provided`.
- The SMTP layer stays independent of the engine.
- Never answer `250` before the channel has accepted the mail.
- Never rename the connector, its classes or its package: saved channels depend on them.
- Reply texts and log messages never contain passwords, and reply texts never contain line breaks.
- Comments explain *why*; keep them short.
