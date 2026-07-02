# Wire Protocol

Single bidirectional WebSocket between **Mac daemon** ↔ **Cloudflare DO relay** ↔ **Android app**.
All frames are JSON envelopes. Binary payloads (PTY bytes, file blobs) are base64-encoded inside the JSON.

The relay is **transport only** — it never inspects, modifies, or persists message bodies
beyond a queue when an endpoint is offline. End-to-end content is encrypted between the two
endpoints once paired (see `crypto.md`), so the relay sees ciphertext for everything except
control frames (`HELLO`, `ACK`, `PING`).

## Envelope

```jsonc
{
  "v": 1,              // protocol version
  "id": "01HX…",       // ULID, unique per message (for ACK/dedupe)
  "seq": 42,           // monotonically increasing per-sender; relay uses this for ordering
  "ack": "01HX…",      // optional: id being acknowledged
  "type": "AGENT.SEND",
  "ts": 1718600000000,
  "body": { … }        // encrypted blob when type is not a control frame
}
```

## Control frames (cleartext)

| Type           | Direction       | Body                                                                 |
| -------------- | --------------- | -------------------------------------------------------------------- |
| `HELLO`        | client → relay  | `{ role, pairId, pubKey, deviceLabel, resumeFromSeq }`               |
| `HELLO.OK`     | relay → client  | `{ peerOnline, peerPubKey?, queuedCount }`                           |
| `PAIR.REQUEST` | mac → relay     | `{ pairCodeHash }` (one-time, returns pairId + nonce)                |
| `PAIR.CLAIM`   | phone → relay   | `{ pairId, pairCodeHash, pubKey, deviceLabel }`                      |
| `ACK`          | both            | `{ ids: [...] }`                                                     |
| `PING`         | both            | `{}` every 25s; relay/peer replies `PONG`                            |
| `PONG`         | both            | `{ ts }`                                                             |
| `ERROR`        | relay → client  | `{ code, message }`                                                  |

## Application frames (encrypted body)

### Agents

| Type                  | Direction      | Body                                                                  |
| --------------------- | -------------- | --------------------------------------------------------------------- |
| `AGENT.LIST`          | phone → mac    | `{ runtime: "local"\|"cloud"\|"all", cwd? }`                         |
| `AGENT.LIST.RESULT`   | mac → phone    | `{ agents: AgentSummary[] }`                                          |
| `AGENT.CREATE`        | phone → mac    | `{ cwd, model, runtime, mcpOverrides?, initialPrompt? }`              |
| `AGENT.CREATE.RESULT` | mac → phone    | `{ agentId, runtime, model, createdAt }`                              |
| `AGENT.SEND`          | phone → mac    | `{ agentId, prompt, runId(client-gen) }`                              |
| `AGENT.STREAM`        | mac → phone    | `{ agentId, runId, event: SdkStreamEvent }`                           |
| `AGENT.RUN.RESULT`    | mac → phone    | `{ agentId, runId, status, summary }`                                 |
| `AGENT.CANCEL`        | phone → mac    | `{ agentId, runId }`                                                  |
| `AGENT.RESUME`        | phone → mac    | `{ agentId }`                                                         |
| `AGENT.GET`           | phone → mac    | `{ agentId }`                                                         |
| `AGENT.GET.RESULT`    | mac → phone    | `{ agent: AgentDetail, recentRuns: RunSummary[] }`                    |
| `AGENT.MODELS`        | phone → mac    | `{}`                                                                  |
| `AGENT.MODELS.RESULT` | mac → phone    | `{ models: ModelDescriptor[] }`                                       |

### Files

| Type            | Direction      | Body                                                       |
| --------------- | -------------- | ---------------------------------------------------------- |
| `FS.LIST`       | phone → mac    | `{ path }`                                                 |
| `FS.LIST.OK`    | mac → phone    | `{ path, entries: { name, kind, size, mtime }[] }`         |
| `FS.READ`       | phone → mac    | `{ path, maxBytes? }`                                      |
| `FS.READ.OK`    | mac → phone    | `{ path, contentB64, encoding, truncated }`                |
| `FS.WRITE`      | phone → mac    | `{ path, contentB64 }`                                     |
| `FS.WRITE.OK`   | mac → phone    | `{ path, bytes }`                                          |
| `FS.OPEN_IN_IDE`| phone → mac    | `{ path }`                                                 |
| `FS.WORKSPACES` | phone → mac    | `{}` returns known git roots + recent Cursor workspaces    |

### Terminals

| Type            | Direction      | Body                                                                |
| --------------- | -------------- | ------------------------------------------------------------------- |
| `PTY.OPEN`      | phone → mac    | `{ cwd?, cols, rows, shell? }`                                      |
| `PTY.OPENED`    | mac → phone    | `{ ptyId, pid, shell, cwd }`                                        |
| `PTY.STDIN`     | phone → mac    | `{ ptyId, dataB64 }`                                                |
| `PTY.STDOUT`    | mac → phone    | `{ ptyId, dataB64, seq }`                                           |
| `PTY.RESIZE`    | phone → mac    | `{ ptyId, cols, rows }`                                             |
| `PTY.CLOSE`     | phone → mac    | `{ ptyId }`                                                         |
| `PTY.EXIT`      | mac → phone    | `{ ptyId, code }`                                                   |
| `PTY.LIST`      | phone → mac    | `{}` (returns alive sessions phone can re-attach to)                |
| `PTY.LIST.OK`   | mac → phone    | `{ sessions: { ptyId, cwd, shell, lastSeq, bufferPreview }[] }`     |
| `PTY.ATTACH`    | phone → mac    | `{ ptyId, sinceSeq }` (replay scrollback)                           |

### MCP

| Type             | Direction      | Body                                                       |
| ---------------- | -------------- | ---------------------------------------------------------- |
| `MCP.LIST`       | phone → mac    | `{}`                                                       |
| `MCP.LIST.OK`    | mac → phone    | `{ user: McpServer[], project: { [path]: McpServer[] } }`  |
| `MCP.UPSERT`     | phone → mac    | `{ scope: "user"\|"project", projectPath?, server }`       |
| `MCP.DELETE`     | phone → mac    | `{ scope, projectPath?, name }`                            |
| `MCP.TOGGLE`     | phone → mac    | `{ scope, projectPath?, name, enabled }`                   |

### System

| Type            | Direction      | Body                                                       |
| --------------- | -------------- | ---------------------------------------------------------- |
| `SYS.STATUS`    | phone → mac    | `{}`                                                       |
| `SYS.STATUS.OK` | mac → phone    | `{ daemonVersion, cursorVersion?, uptimeMs, agentCount }`  |
| `SYS.FCM`       | phone → mac    | `{ token }` register Firebase token for push               |

## Reliability rules

1. Both endpoints persist their **outbound `seq` counter** and the **highest `seq` received from peer**.
2. On reconnect, send `HELLO { resumeFromSeq }`. The relay replays anything queued above that seq.
3. The relay buffers up to **1 MiB / 1 000 messages** per direction in Durable Object storage with TTL 7 days.
4. Application frames are idempotent by `id`; receiver drops duplicates.
5. Streaming frames (`AGENT.STREAM`, `PTY.STDOUT`) may be coalesced by the sender if many arrive faster than the wire; receiver still gets monotonic `seq`.

## Pairing

1. User runs `cursor-mobile-daemon pair` on Mac. Daemon shows a QR encoding `cm1://pair?relay=<url>&code=<32-char-base32>&fp=<pubkey-fp>`.
2. Phone scans QR → sends `PAIR.CLAIM` to relay with `pairId=hash(code)`, its X25519 pubkey, and device label.
3. Relay matches the in-flight `PAIR.REQUEST` from the daemon (same `pairId`), exchanges the two pubkeys, and emits `PAIR.OK` to both.
4. Both endpoints derive a shared symmetric key via X25519 + HKDF-SHA256. All subsequent application bodies are encrypted with **ChaCha20-Poly1305 (IETF, 12-byte nonce)** so it works directly with both `@noble/ciphers` on Node and `javax.crypto.Cipher` on Android API 28+.
5. The relay only stores the public pairing record (pairId + the two pubkeys). It never learns the shared key.

## Versioning

- Bump `v` on any breaking schema change. Receivers MUST close the connection with `ERROR { code: "VERSION_UNSUPPORTED" }` if `v` is unknown.
- Within a major version, fields are additive. Unknown fields are ignored.
