# Apna Files relay

A small Cloudflare Worker that lets two paired phones find each other and stream files
between themselves. It stores pairing state in a Durable Object and forwards file bytes
through a second Durable Object; file contents are never written to storage.

## Deploy

```bash
npm install
npx wrangler login
npx wrangler deploy
```

Wrangler prints the URL to paste into the app (Settings → Relay).

For local development:

```bash
npx wrangler dev            # http://127.0.0.1:8787
```

Point the app at `http://10.0.2.2:8787` from an emulator (cleartext is allowed for
loopback addresses only in the app's network security config).

### Configuration notes

- `wrangler.toml` declares two Durable Object classes and uses
  `new_sqlite_classes`, which is the storage backend available on the free plan.
- No KV, D1 or R2 is needed. If you later want offline file drops, an R2 bucket is the
  natural place.
- There are no secrets to configure. Signing keys live on the devices.
- Renaming the Worker in `wrangler.toml` is fine; redeploy and update the app's URL.

## Authentication

Every request carries:

| Header | Value |
| --- | --- |
| `X-Apna-Pub` | uncompressed secp256k1 public key, 65 bytes hex (`04…`) |
| `X-Apna-Ts` | unix seconds |
| `X-Apna-Sig` | 64-byte ECDSA signature hex (`r ‖ s`, low-S) |

The signed message is `METHOD:path:sha256(rawBody):timestamp`, hashed with SHA-256 and
signed with RFC 6979. The Worker derives the caller's ID itself:

```
address = "0x" + hex(keccak256(publicKey[1..65]))[last 20 bytes]
```

so a client cannot claim an ID that does not belong to its key. Timestamps older or
newer than five minutes are rejected; a malformed or forged signature returns 401.

## HTTP API

| Method | Path | Body | Result |
| --- | --- | --- | --- |
| GET | `/v1/health` | – | `{ "ok": true, "version": "1.0.0" }` (no auth) |
| POST | `/v1/register` | `{ name, platform }` | session view |
| GET | `/v1/peers` | – | session view |
| POST | `/v1/pair/request` | `{ target }` | `{ requestId }` |
| POST | `/v1/pair/respond` | `{ requestId, accept }` | `{ pairId, peer }` |
| POST | `/v1/pair/invite` | `{}` | `{ token, expiresAt }` |
| POST | `/v1/pair/claim` | `{ token }` | `{ pairId, peer }` |
| POST | `/v1/pair/remove` | `{ pairId }` | `{ ok: true }` |
| GET | `/v1/relay/{pairId}` | – | WebSocket upgrade |

The session view is:

```json
{
  "address": "0x…",
  "name": "Pixel 8",
  "peers":   [{ "address": "0x…", "name": "Galaxy", "pairId": "p_…", "online": true, "lastSeen": 0 }],
  "incoming":[{ "id": "r_…", "from": "0x…", "fromName": "…", "ts": 0 }],
  "outgoing":[{ "id": "r_…", "to": "0x…", "toName": "…", "ts": 0 }]
}
```

Errors are `{ "error": "code", "message": "human readable" }` with 4xx/5xx.

## Relay protocol

One WebSocket per pair, JSON text frames in both directions. Both devices act as client
and server for the other.

```jsonc
// List files (viewer → owner)
{ "t": "req", "id": "abc", "action": "list" }
// …owner answers
{ "t": "res", "id": "abc", "ok": true, "code": "", "files": [ { "id": "image:42", "name": "IMG.jpg", "size": 1234, "mime": "image/jpeg", "kind": "image", "folder": "DCIM/Camera", "modified": 1700000000000 } ] }

// Thumbnail
{ "t": "req", "id": "def", "action": "thumb", "fileId": "image:42" }
{ "t": "res", "id": "def", "ok": true, "data": "<base64 jpeg>" }        // empty data = no preview

// File transfer
{ "t": "req", "id": "ghi", "action": "open", "fileId": "image:42" }
{ "t": "res", "id": "ghi", "ok": true, "size": 1234, "mime": "image/jpeg", "name": "IMG.jpg" }
{ "t": "chunk", "id": "ghi", "seq": 0, "data": "<base64 ≤64 KiB>" }
{ "t": "ack", "id": "ghi", "seq": 0 }                                   // every 16 chunks
{ "t": "done", "id": "ghi" }
{ "t": "err", "id": "ghi", "message": "…" }

// Presence, emitted by the Room object
{ "t": "welcome", "peerOnline": false }
{ "t": "presence", "address": "0x…", "peerOnline": true }
```

The receiver only writes a file once the byte count matches the announced size, and
asks for credit every 16 chunks so a slow link applies backpressure to the sender.

## Layout

```
src/index.ts      routing, WebSocket handshake, membership check
src/auth.ts       signature verification, address derivation (@noble/curves)
src/directory.ts  Durable Object: devices, pairs, invites, request inbox, presence
src/room.ts       Durable Object: per-pair frame forwarder (no storage)
src/types.ts      shared types and JSON helpers
```

## Operating notes

- Presence is a counter incremented on socket open and decremented on close, so a phone
  that reconnects does not flicker offline for its peer.
- Invites are swept lazily when a new one is created; pairing requests expire after
  seven days.
- Logs are available with `npx wrangler tail`. Neither file bytes nor names are logged.
- `npx wrangler deploy` is the only deployment step; there is no migration to run beyond
  the one declared in `wrangler.toml`.
