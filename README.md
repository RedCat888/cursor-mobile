# Cursor Mobile

A native Android remote control for [Cursor](https://cursor.com) on macOS. Drive agents, browse files, run terminals, manage MCP servers — all from your phone, anywhere, end-to-end encrypted.

```
┌─────────────────┐      WSS       ┌───────────────────────┐      WSS       ┌─────────────────┐
│  Android (S24)  │ ─────────────► │  Cloudflare Worker +  │ ◄───────────── │  Mac daemon     │
│  Kotlin/Compose │ ◄───────────── │  Durable Object relay │ ─────────────► │  Node + @cursor │
│  Material 3     │                │  (E2E pass-through,   │                │  /sdk, node-pty │
│  Room offline   │                │   per-pair queue)     │                │  fs, mcp        │
└─────────────────┘                └───────────────────────┘                └─────────────────┘
```

Three components, one wire protocol. Pick them apart in:

- [`docs/PROTOCOL.md`](docs/PROTOCOL.md) — wire frames, pairing, crypto
- [`relay/`](relay/README.md) — Cloudflare Worker that just routes/queues
- [`mac-daemon/`](mac-daemon/README.md) — the Mac-side service that wraps Cursor SDK
- [`android/`](android/README.md) — the Android app

## What it can do (v1)

- **Agents** — list local + cloud agents, create new with any model, stream output in real time, cancel, follow-up, switch models, view tool calls
- **Files** — browse any directory the daemon is allowed in, view/edit text files, one-tap "open in Cursor on Mac"
- **Terminal** — multiple PTY sessions, persists across phone reconnects, soft keys for `Tab`, `Esc`, `Ctrl-C/D/L`, arrow keys
- **MCP** — list/add/toggle/delete MCP servers in `~/.cursor/mcp.json`
- **Offline** — local Room cache, persistent outbox; nothing dropped when phone or Mac is offline
- **Push** — optional FCM notification when an agent finishes / errors while the app is closed
- **Connection** — works on Wi-Fi, cellular, captive portals; no port forwarding; auto-reconnect with exponential backoff and resume tokens

## Setup, top-to-bottom

### 1. Deploy the relay (one-time, free)

```bash
cd relay
npm install
npx wrangler login            # one-time
npx wrangler deploy           # prints https://cursor-mobile-relay.<sub>.workers.dev
```

That URL is the only address both the daemon and the phone need.

### 2. Get a Cursor API key

1. Open https://cursor.com/dashboard/integrations
2. Click **Create new API key**
3. Copy the `cursor_…` string

This key never leaves your Mac.

### 3. Install the Mac daemon

```bash
cd ../mac-daemon
npm install && npm run build
node dist/cli.js config set-key   cursor_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
node dist/cli.js config set-relay https://cursor-mobile-relay.<sub>.workers.dev

# Auto-start at login & restart on crash
./scripts/install.sh
```

Make sure the `cursor` shell command is installed (Cursor → Settings → "Shell Command: Install 'cursor' command"). That's what powers "open in Cursor" from the phone.

### 4. Build & install the Android app

Easiest path: open `android/` in **Android Studio (Koala or newer)** and hit Run on your S24.

Command line:

```bash
cd ../android
gradle wrapper
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

For a signed release APK, see [`android/README.md`](android/README.md).

### 5. Pair

On your Mac:

```bash
cursor-mobile-daemon pair
# (or: node /path/to/mac-daemon/dist/cli.js pair)
```

It prints a QR + fingerprint. Open Cursor Mobile on your S24 → it lands on the pair screen → scan. Done.

### 6. (Optional) Push notifications

To get notifications when an agent finishes while the app is killed, set up Firebase:

1. Create a free Firebase project, add an Android app with applicationId `com.cursormobile`
2. Drop the downloaded `google-services.json` into `android/app/`
3. Rebuild the APK and reinstall
4. Generate a Firebase Admin SDK service account JSON, paste it into the daemon config (`fcm.serverKey`)

Without this, you still get foreground notifications when the app is open — only background ones need FCM.

## Security model

| Concern | Mitigation |
| --- | --- |
| Wire encryption | X25519 ECDH → HKDF-SHA256 → ChaCha20-Poly1305 (IETF, 12-byte nonces). Relay sees ciphertext for every application body. |
| Pairing | One-time 6-char code + QR via the relay. Replaces after 5 min. Both ends save the peer's pubkey to disk. |
| Mac auth | Keys in `~/.cursor-mobile/config.json`, mode 0600. Daemon runs as your user, not root. |
| Phone auth | Identity keypair in Android Keystore, wrapped by EncryptedSharedPreferences. |
| Filesystem | Daemon defaults to allowing `$HOME` only; explicit deny for `/System`, `/usr`, `/etc`, etc. Override with `fsAllowList`. |
| Cursor API key | Stored locally only; never sent over the wire (used by the daemon to call Cursor cloud APIs). |
| Relay trust | Relay doesn't need to be trusted with content. Replace the deployment with your own at any time. |

## Repo layout

```
cursor-mobile/
├── docs/PROTOCOL.md         # wire protocol spec (frames, pairing, crypto)
├── relay/                   # Cloudflare Worker + Durable Object
│   ├── src/index.ts         # routing + pair/start endpoint
│   └── src/PairDO.ts        # per-pair Durable Object with sqlite-backed queue
├── mac-daemon/              # Node 20+ daemon
│   ├── src/cli.ts           # CLI: run, pair, unpair, config
│   ├── src/daemon.ts        # wires services + transport
│   ├── src/transport.ts     # relay WS + LAN WS + outbox
│   ├── src/router.ts        # envelope dispatcher
│   └── src/services/        # agents, files, terminals, mcp, notifications
└── android/                 # Kotlin + Jetpack Compose
    └── app/src/main/kotlin/com/cursormobile/
        ├── data/            # net, crypto, db, repo, prefs
        ├── ui/              # pairing, agents, files, terminal, mcp, settings
        ├── work/            # foreground service that keeps WS alive
        └── push/            # FCM handler
```

## Caveats / known sharp edges

- The agent stream events are forwarded raw from the Cursor SDK; the Android UI knows about `assistant` text deltas and `tool_use` / `tool_call` blocks. Less common event types render only in the tool-call ribbon, not as bubbles.
- `node-pty` builds a native module on `npm install`; if your Mac is on Apple Silicon you may need Xcode CLT (`xcode-select --install`).
- Self-hosted Cloudflare relay assumes a free Workers account; usage stays well under the free tier for a single user.
- Currently single-Mac per phone pairing is the focus; multi-Mac switching is wired in the data layer (see `AuthStore.savePair`) but not surfaced in the UI yet.

## Status

Actively developed, pre-release. Latest known-good validation:

- `mac-daemon`: `tsc --noEmit` passes
- `relay`: `tsc --noEmit` passes
- `cursor-extension`: build passes

## Hacking

Each component has its own `README.md`. Quick dev loop:

```bash
# Terminal 1 — relay
cd relay && npm run dev               # wrangler dev on :8787

# Terminal 2 — daemon
cd mac-daemon
node dist/cli.js config set-relay http://localhost:8787
npm run dev                           # tsx watch

# Terminal 3 — Android Studio (or ./gradlew installDebug)
```

PRs welcome.
