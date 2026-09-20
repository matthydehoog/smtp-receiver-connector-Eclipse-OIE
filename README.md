# SMTP Receiver for Eclipse OIE

A **source connector type** for [Eclipse Open Integration Engine](https://openintegrationengine.org/) (tested against **4.6.0**) that runs an **SMTP server** in a channel. Mail that is sent to it is handed to the channel as an RFC 822 message (or as JSON). Choose "SMTP Receiver" as the Source of a channel in the Swing client or the web administrator.

> Community extension. It is not part of, or endorsed by, the Eclipse OIE project.

It is the counterpart of the built-in *SMTP Sender*, and a replacement for the do-it-yourself version with a JavaScript Reader and a `ServerSocket` (see [Migrating](#migrating-from-a-javascript-reader-channel)).

## What you get

- "SMTP Receiver" in the Source connector type list of the **Swing client** and the **web administrator**, with the usual *Local Address / Local Port* settings and the *Ports in Use* check.
- **Authentication** with `AUTH PLAIN` and `AUTH LOGIN`, with a list of users. A login is required by default.
- **STARTTLS** and **implicit TLS** (SMTPS), with a keystore of your choice. When TLS is on, a login is only accepted over the encrypted connection.
- **Several clients at once**, several mails per connection, pipelining, `8BITMIME`, `SIZE`, enhanced status codes.
- The **answer to the sender follows the channel**: `250` only after the channel has accepted the mail, `451` (try again later) when it could not be processed, so the sender keeps the mail.
- Limits: message size, connections, recipients per mail, idle timeout.
- The mail arrives **unchanged**: headers, the empty line, the body, 8-bit data and lines that start with a dot are all intact. The envelope (who sent it, to whom, from where, logged in as whom) is in the source map.

## Install

1. Download `smtp-receiver-connector-<version>.zip` from the [Releases](../../releases) page (or build it, see below).
2. Settings -> Extensions -> **Install Extension**, choose the zip, restart the engine.
3. Restart the Swing client. In the web administrator do a hard refresh (Ctrl+F5).
4. Create a channel and choose **SMTP Receiver** as the source connector type.

If the installer refuses because the extension already exists, uninstall the old version first and restart.

## Settings

| Setting | Meaning | Default |
|---|---|---|
| Local Address / Local Port | Where to listen. Templates such as `${smtpPort}` work | all interfaces, `2525` |
| Hostname | Name in the greeting. Empty: the name of this machine | *(empty)* |
| Require login | Only accept mail from clients that log in. **Switch this off only on a network you trust** | on |
| Users | One `username:password` per line. `#` starts a comment. Spaces around both are ignored; the password may contain colons | *(empty)* |
| TLS | `None`, `STARTTLS` (the client upgrades the connection, usually port 587 or 25) or `Implicit TLS` (encrypted from the first byte, usually port 465) | None |
| Keystore file / password / key password / type | The server certificate and its private key. Key password empty: same as the keystore password. Types `PKCS12`, `JKS`, `JCEKS` | PKCS12 |
| Max message size (MB) | Larger mails are refused with `552`. 0 = no limit | 25 |
| Max connections | More clients get `421` and are asked to try again | 10 |
| Max recipients | Per mail. More get `452` | 100 |
| Timeout (seconds) | A client that stays silent this long is disconnected | 120 |
| Message format | `RFC 822 message` or `JSON` (see below) | RFC 822 |
| Character set | How the bytes of the mail become text in the channel | UTF-8 |

With login required, at least one user must be defined, and with TLS a keystore file: otherwise the channel does not deploy and says why.

Beyond these settings the channel's usual *Source Settings* apply (queue, response, processing threads).

## What the channel receives

### Message format "RFC 822 message" (default)

The mail exactly as it was sent, with CRLF line endings:

```
MIME-Version: 1.0
From: sender@example.org
To: intake@example.org
Subject: Lab result 42
Content-Type: text/plain; charset=UTF-8

Hello,

...
```

Use the **RAW** data type for the source. To read it in a transformer, the engine's own mail library does the work:

```javascript
// msg is the mail as it was sent
var session = javax.mail.Session.getDefaultInstance(new java.util.Properties());
var bytes = new java.lang.String(msg).getBytes('UTF-8');      // the character set of the connector
var mime = new javax.mail.internet.MimeMessage(session, new java.io.ByteArrayInputStream(bytes));

var subject = String(mime.getSubject());
var from = String(mime.getFrom()[0].toString());
var body = String(mime.getContent());                          // a String for a plain text mail
```

For mails with attachments `getContent()` is a `Multipart`; go through its parts (`getBodyPart(i)`).

### Message format "JSON"

The envelope and the data as a list of lines. Empty lines are kept, so the boundary between headers and body is still there.

```json
{"MAILFROM":"sender@example.org","RCPTTO":["intake@example.org"],"USER":"mirth","AUTHENTICATION":"SUCCESS",
 "CLIENTIP":"10.0.0.5","HELO":"client.example","TLS":true,
 "DATA":["Subject: Lab result 42","","Hello,",""]}
```

`AUTHENTICATION` is `SUCCESS` when the client logged in and `NA` when no login was needed. A client that fails to log in never reaches the channel, so there is no `FAILED`.

### Source map

Available in every format, for example `sourceMap.get('mailFrom')`:

| Key | Value |
|---|---|
| `remoteAddress`, `remotePort` | the client |
| `localAddress`, `localPort` | the address that was connected to |
| `helo` | the name the client gave in HELO/EHLO |
| `mailFrom` | envelope sender, as the client wrote it (upper and lower case are kept; empty for a bounce) |
| `rcptTo` | envelope recipients, separated by commas |
| `authUser` | the user who logged in, or empty |
| `tls` | `true` when the connection is encrypted |

The envelope is what SMTP delivers to; it can differ from the `From:` and `To:` headers, and that is exactly what a mailing list, a BCC or a forward does.

## What the sender gets back

| Reply | When |
|---|---|
| `250 2.0.0 Ok: queued as <id>` | The channel accepted the message (`<id>` is the message id in the engine) |
| `451 4.3.0` | The channel is stopped, could not store the message, or the message ended with status ERROR. The sender keeps the mail and tries again later |
| `530` / `535` / `538` | Login required / wrong credentials / login only over TLS. Three wrong logins close the connection |
| `552` | Larger than the maximum size |
| `452` | Too many recipients |
| `421` | Too many connections, silence for too long, or the channel is stopping |
| `503`, `501`, `500` | Commands in the wrong order, wrong syntax, unknown command |

**Good to know:** a `451` for a message that ended with status ERROR means the message *is* in the channel (with the error) **and** the sender will deliver it again. That is at-least-once delivery: a mail can arrive twice, never zero times. Filter on `Message-ID` in the channel if that matters.

When a channel is stopped or redeployed, clients that are only waiting for a command are told `421` at once; a mail that is being processed is finished first (up to 5 seconds).

## Try it

[`examples/Send-TestMail.ps1`](examples/Send-TestMail.ps1) sends one mail through the receiver with .NET's `SmtpClient`:

```powershell
.\examples\Send-TestMail.ps1 -Port 2525 -Credential (Get-Credential mirth)
.\examples\Send-TestMail.ps1 -Port 2525 -NoLogin          # when login is off
.\examples\Send-TestMail.ps1 -Port 587 -Tls -Credential (Get-Credential mirth)   # STARTTLS
```

Any mail client or library works, for example Python's `smtplib`, `swaks`, or the SMTP Sender of another OIE channel.

## Logging and status

- The **dashboard** shows what the connector does: the connection count, the mail that is being received, and in the channel's connection log `Listening on 0.0.0.0:2587 (STARTTLS)` when the channel starts and `Stopped listening on port 2587` when it stops.
- Problems that need attention (a keystore that cannot be loaded, a port that is in use, an error while processing a mail) are errors: they show in the dashboard and in `mirth.log` at the default log level.
- Everything else is logged at INFO level, and the engine's default `log4j2.properties` uses `rootLogger = ERROR`, which hides it. To see failed logins, refused connections and the start-up line in `mirth.log`, add to `<OIE_HOME>/conf/log4j2.properties` and restart the engine:

```
logger.smtpreceiver.name = com.mirth.connect.connectors.smtpreceiver
logger.smtpreceiver.level = INFO
```

- To check that the port is open without any log: `Get-NetTCPConnection -State Listen -LocalPort 2587` in PowerShell.

## Security notes

- Passwords (users, keystore) are stored in the channel, like the password of any other connector. Anyone who can export the channel can read them.
- This is a **receiver**, not a relay: it hands mail to the channel and never forwards it. It accepts any recipient address, because it is the channel's job to decide what to do with a mail.
- Do not put it directly on the internet. Bind it to an internal address (*Specific interface*), firewall it, and keep the login and TLS on.
- Without TLS, a login travels in clear text. With TLS on, the connector refuses a login over an unencrypted connection.
- The server refuses `AUTH` during a mail transaction, ignores commands sent behind `STARTTLS` in the same packet (CVE-2011-0411) and does not reveal whether a user exists.
- Only TLS 1.2 and 1.3 are enabled.

## Not included

- No `SMTPUTF8`, no `DSN`, no `CHUNKING`/`BDAT`, no `AUTH CRAM-MD5`/`XOAUTH2`.
- No rejection of individual recipients or senders (`550`); use a filter in the channel.
- No allow-list of client addresses; use the *Local Address* setting and a firewall.
- No greylisting, rate limiting or spam filtering. Only the maximum number of connections.
- The mail is held in memory while it is received (up to the maximum size).

## Migrating from a JavaScript Reader channel

The do-it-yourself version (a JavaScript Reader that opens a `ServerSocket`, and a second channel that processes the JSON) can be replaced as follows:

1. Create a channel with **SMTP Receiver** as source. Copy the users of the `logins` array into *Users* (`mirth:123`, one per line) and the port into *Local Port*.
2. Choose message format **JSON** if the processing channel expects `MAILFROM`, `RCPTTO`, `USER`, `AUTHENTICATION`, `CLIENTIP` and `DATA`, and handle the mail in this channel or send it on with a Channel Writer. Or choose **RFC 822** and parse the mail as shown above.
3. Remove the deploy and undeploy scripts: the connector opens and closes the port with the channel.

What changes, all for the better:

| The JavaScript version | SMTP Receiver |
|---|---|
| Empty lines were dropped from `DATA`, so headers and body could not be told apart | Kept, in both formats |
| `MAILFROM` and `RCPTTO` in capitals (`JAN@BEDRIJF.NL`) | As the client wrote them |
| One client at a time, one mail per connection | Many clients, many mails per connection |
| A failed login was recorded as `FAILED` and the mail was accepted anyway | A failed login stops the mail; no login, no mail (when login is required) |
| `PASSWORD` in the JSON | Not passed on |
| `250 OK` before the channel had the message | `250` after the channel accepted it, `451` otherwise |
| No `AUTH PLAIN`, no TLS, no limits | `AUTH PLAIN` and `LOGIN`, STARTTLS and SMTPS, limits and time-outs |
| Lines that start with a dot were sent doubled by the client and not restored | Restored (RFC 5321 dot-unstuffing) |

## Build

Requirements: a JDK 11+ (the runtime bundled with the engine, `<OIE_HOME>/jre`, includes `javac` and `keytool` and works as `JAVA_HOME`) and Maven 3.9+.

1. Install the engine jars into your local Maven repository under the coordinates `pom.xml` expects:

   ```bash
   mvn install:install-file -Dfile="<OIE_HOME>/server-lib/mirth-server.jar"        -DgroupId=com.mirth.connect -DartifactId=server-api    -Dversion=4.6.0 -Dpackaging=jar
   mvn install:install-file -Dfile="<OIE_HOME>/server-lib/donkey/donkey-server.jar" -DgroupId=com.mirth.connect -DartifactId=donkey-server -Dversion=4.6.0 -Dpackaging=jar
   mvn install:install-file -Dfile="<OIE_HOME>/server-lib/donkey/donkey-model.jar"  -DgroupId=com.mirth.connect -DartifactId=donkey-model  -Dversion=4.6.0 -Dpackaging=jar
   mvn install:install-file -Dfile="<OIE_HOME>/client-lib/mirth-client.jar"         -DgroupId=com.mirth.connect -DartifactId=client        -Dversion=4.6.0 -Dpackaging=jar
   mvn install:install-file -Dfile="<OIE_HOME>/client-lib/mirth-client-core.jar"    -DgroupId=com.mirth.connect -DartifactId=client-core   -Dversion=4.6.0 -Dpackaging=jar
   ```

2. Build (this also runs the tests, which start the server on a free port and use a real SMTP client):

   ```bash
   mvn clean package
   ```

   This produces `target/smtp-receiver-connector-<version>.zip`:

   ```
   smtp-receiver/
   ├── source.xml
   ├── smtpreceiver-shared.jar     (settings class, used by server and clients)
   ├── smtpreceiver-server.jar     (SMTP server + receiver)
   ├── smtpreceiver-client.jar     (Swing settings panel)
   └── webadmin/                   (web administrator panel: plugin.json + web/plugin.js)
   ```

## Design notes (for developers)

- **The SMTP server knows nothing about the engine.** `SmtpServer` and `SmtpSession` only call a `Handler` for each complete message; `SmtpReceiver` is the thin part that turns a message into a `RawMessage` and the channel's result into an SMTP reply. That is what makes the protocol testable on its own, with real clients.
- **Package name.** The classes live in `com.mirth.connect.connectors.smtpreceiver`. The engine's XStream allow-list only accepts classes from the `com.mirth.connect.*` family when it reads a channel; a package of your own makes the channel unreadable in the Swing client.
- **Folder name = `path`.** The `path` attribute in `source.xml` (`smtp-receiver`) must equal the extension folder name; uninstalling and the web administrator both build paths from it.
- **Null-safe properties.** The engine does not run constructors when it reads a saved channel, so a setting that is added later is null there. The getters of `SmtpReceiverProperties` fall back to the defaults.
- **`MirthPasswordField` cannot be hidden** (it overrides `isVisible()`), so the Swing panel uses a small subclass to show the keystore fields only when TLS is on.
- **Web administrator.** `webadmin/web/plugin.js` registers the panel with `platform.registerConnectorPanel("SMTP Receiver", "SOURCE", ...)`. Engine-hosted web plugins are loaded as a single module, so it imports only the bare `@oie/web-ui` specifier and reads `platform.React` at render time.

## License

[Mozilla Public License 2.0](LICENSE).
