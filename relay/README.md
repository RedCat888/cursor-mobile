# cursor-mobile relay

A tiny Cloudflare Worker + Durable Object that routes WebSocket frames between your Mac daemon and Android app.

## Deploy

```bash
cd relay
npm install
npx wrangler login          # first time only
npx wrangler deploy
```

That gives you a URL like `https://cursor-mobile-relay.<your-subdomain>.workers.dev`. Note it — both the daemon and the Android app need it.

## Local dev

```bash
npm run dev
```

Then point the daemon and app at `ws://localhost:8787`.

## Routes

- `GET  /health` — liveness
- `POST /pair/start` — daemon kicks off pairing, gets `{ pairId, code, ttlSeconds }`
- `GET  /pair/:pairId/ws?role=mac|phone` — WebSocket upgrade

The DO holds per-pair state, queues frames when a peer is offline (up to 1000 frames / 7 days), and forgets the symmetric key — only the daemon and the phone see decrypted bodies.
