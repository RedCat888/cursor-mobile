# cursor-mobile-daemon

The Mac-side service that lets the [Cursor Mobile Android app](../android) drive Cursor on your laptop. Exposes agents, files, terminals, and MCP config over an end-to-end-encrypted channel (cloud relay or LAN direct).

## What you need

- **Node 20+** (it ships with one binary spawn that uses `node-pty`; built natively on `npm install`)
- **Cursor for Mac** with the `cursor` shell command installed (Cursor → Settings → "Shell Command: Install 'cursor' command")
- A **Cursor API key** (free): https://cursor.com/dashboard/integrations → "Create new API key". Copy it.
- A deployed **relay** URL (see `../relay/README.md`)

## Install

```bash
cd mac-daemon
npm install
npm run build

# Tell the daemon your key + relay
node dist/cli.js config set-key  cursor_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
node dist/cli.js config set-relay https://cursor-mobile-relay.<your-subdomain>.workers.dev

# Sanity-check the API key + SDK end-to-end (one-shot ping/pong agent run)
node dist/cli.js test

# Optional: install as a LaunchAgent so it boots with your Mac
./scripts/install.sh
```

## Pair your phone

```bash
node dist/cli.js pair
```

You'll see:
```
Code:        7K9MQX
Fingerprint: a4f1d3...
Scan this QR with the Cursor Mobile app:
  [QR code]
```

Open Cursor Mobile on your S24, hit **Add Mac**, scan the QR. After a second you'll see `✓ Paired with <phone label>`.

Pairing exchanges X25519 pubkeys via the relay; both ends derive a shared symmetric key with HKDF-SHA256 and encrypt every application body with XChaCha20-Poly1305. The relay only sees ciphertext.

## Run

If you used `install.sh`, the daemon is already running and auto-restarts. Otherwise:

```bash
npm start          # or `node dist/cli.js run`
```

Logs:
```bash
tail -F ~/.cursor-mobile/daemon.{out,err}.log
```

## Config file

`~/.cursor-mobile/config.json` (mode 0600). Everything CLI-settable lives there; the file is the source of truth.

```jsonc
{
  "relayUrl": "https://...workers.dev",
  "lan": { "enabled": true, "port": 7681, "bindHost": "0.0.0.0" },
  "pairing": { /* set by `pair` */ },
  "fcm": { "serverKey": "<JSON service account>", "deviceToken": "...", "projectId": "..." },
  "fsAllowList": [],     // default: $HOME
  "defaultCwd": "/Users/you/code/some-project"
}
```

## Optional: push notifications

If you want the phone to ring when an agent finishes while the app is closed:

1. Create a Firebase project (free) → "Add app" → Android (the app's `applicationId` is `com.cursormobile`).
2. Download `google-services.json` → drop into `android/app/`.
3. Create a service account in Firebase Console → IAM → Service Accounts with role *Firebase Admin SDK Administrator Service Agent*, generate a JSON key.
4. Stick the JSON's text into the daemon config:
   ```bash
   node dist/cli.js config show              # to find the path
   # then paste the JSON into fcm.serverKey, save the file
   ```
The phone registers its own device token automatically once paired.

If you skip this, notifications still arrive when the app is open (via the live WS); only background notifications need FCM.

## Security

- All traffic between phone ↔ Mac is ChaCha20-Poly1305 (X25519+HKDF derived) with a key the relay never learns.
- Filesystem access is restricted to `fsAllowList` (default `$HOME`), with hard-coded denies for `/System`, `/usr`, `/etc`, etc.
- The launchd plist runs as your user; no root.
- `config.json` is `chmod 600`.
