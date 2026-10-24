# Apna Files

Private, temporary file access between your own devices.

Two phones pair once. After that, while both apps are open, each phone can browse the
other's photos, videos, audio and imported documents, pull low-quality previews while
scrolling, open a file on demand, or save a permanent copy — with no accounts and
nothing stored on the server.

This repository contains both halves:

| Folder | What it is |
| --- | --- |
| `android/` | The Android app — Kotlin, Jetpack Compose, Material 3 (`com.ayush.apnafiles`) |
| `cloudflare/` | The relay you deploy to your own Cloudflare account — TypeScript Worker + Durable Objects |

---

## What it does

- **Device identity without accounts.** Each install generates a secp256k1 key pair and
  its ID is the Ethereum-style address of its public key: `0x` + the last 20 bytes of
  `keccak256(uncompressed pubkey)`. Every request is ECDSA-signed, so an ID can't be
  spoofed and there is nothing to sign up for.
- **Two pairing methods.** A five-minute QR invite, or exchanging device IDs with an
  explicit approval on the other phone.
- **Live browsing.** Pick a paired device and see its file list; previews are fetched
  lazily as tiles scroll into view, into a local thumbnail cache.
- **Open on demand.** Tapping a file streams it into a cache directory and hands it to
  the system viewer through a `FileProvider`.
- **Permanent saves.** Long-press a file (or *Save all shown files* in the menu) to put
  a lasting copy in `Downloads/Apna Files` via MediaStore.
- **Temporary by design.** The transfer session ends shortly after the app leaves the
  foreground; previews are purged immediately and opened files after a short grace.

## How it works

```
  Phone A  ── WSS ──┐
                    ├── Cloudflare Worker
  Phone B  ── WSS ──┘        │
                             ├── Directory Durable Object   devices, pairs, invites, presence
                             └── Room Durable Object        one per pair, forwards frames only
```

- Both phones keep one WebSocket per pair open while the app is in use.
- File operations are small JSON envelopes (`list`, `thumb`, `open`) sent over that
  socket. Each device answers the other's requests from its own local media.
- Files move as 64 KiB base64 frames with a credit window of 48 chunks, so a 1 GB video
  never sits in memory on either side.
- The Room object has no storage: it is a pipe. Nothing is written to R2, D1 or disk.

Identity, signing and address derivation live in
[`android/app/src/main/java/com/ayush/apnafiles/core`](android/app/src/main/java/com/ayush/apnafiles/core)
and are verified against the same `@noble/curves` primitives the Worker uses
(`android/app/src/test/.../CryptoTest.kt`).

## 1. Deploy the relay

Requires Node 18+ and a Cloudflare account (the free plan is enough).

```bash
cd cloudflare
npm install
npx wrangler login
npx wrangler deploy
```

Wrangler prints something like:

```
https://apnafiles-relay.<your-subdomain>.workers.dev
```

That URL is your relay. See [`cloudflare/README.md`](cloudflare/README.md) for the API,
protocol and operational notes.

## 2. Build the app

Requires JDK 17 and either Android Studio or a configured Android SDK (platform 34).

```bash
cd android
./gradlew assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug      # install on a connected device
```

Or open the `android/` folder in Android Studio and press Run. Toolchain: AGP 8.5.2,
Gradle 8.7, Kotlin 2.0.20, compileSdk 34, minSdk 29 (Android 10+).

A debug build from this workspace is at
`android/app/build/outputs/apk/debug/app-debug.apk` if you just want to sideload it.

## 3. First run

1. **Settings → Relay** → paste your Worker URL → **Save** → **Test connection**.
   It should report `Reachable · relay v1.0.0`.
2. Grant media access when asked (otherwise only imported files can be shared).
3. **Pair a device**:
   - *QR code*: on phone A open **Pair a device → QR code**, on phone B tap
     **Scan instead** (or any camera that opens links... it scans the QR text) and point
     it at A's code. The pair is created immediately because A already authorised it by
     showing the code.
   - *Device ID*: copy A's ID from the home card, paste it on B under **Pair a device →
     Device ID**, send, then accept on A.
4. Back on the home screen the paired device appears. Tap it to browse.

Both phones need the app open: if one is closed, the other shows *Not reachable*.

## What is shared

| Source | How it gets there | Permission |
| --- | --- | --- |
| Photos, videos, audio | MediaStore | `READ_MEDIA_*` (Android 13+) / `READ_EXTERNAL_STORAGE` |
| Anything else (PDF, zip, …) | `Settings → Import files into the shared folder` | none (SAF picker) |

The shared folder is `Android/data/com.ayush.apnafiles/files/Shared` on the phone.
Each category can be switched off in Settings.

## Cache lifecycle

| Data | Location | Removed |
| --- | --- | --- |
| Previews | `cache/apna/thumbs` | 15 s after the app leaves the foreground, on every cold start, and via *Clear now* |
| Opened files | `cache/apna/open` | Files older than 10 minutes when the session ends; everything on the next cold start |
| Saved files | `Downloads/Apna Files` | never (that's the point) |

The 15-second delay lets an external viewer pick up the file it was just handed; the
10-minute grace lets it finish reading. Nothing transient survives a cold start —
`Application.onCreate` purges both cache directories.

## Security model

- Private keys never leave the phone. They are stored AES-GCM encrypted with a key held
  in the Android Keystore, and excluded from backup and device transfer.
- Requests are signed over `METHOD:path:sha256(body):timestamp`; the relay verifies the
  signature and derives the device ID itself, rejecting timestamps more than five
  minutes out of step with its clock.
- Room membership is checked against the pairing record before a WebSocket is accepted,
  and pairing always requires an action by both devices.
- No file bytes are persisted by the relay; they exist only while the sockets are open.

Known limits, stated plainly:

- TLS protects transport, and the relay is yours, but file bytes are **not**
  end-to-end encrypted in v1. Someone with full access to your Cloudflare account could
  observe traffic. The natural next step is X25519 + AES-GCM using the paired keys.
- A captured signed request can be replayed inside its five-minute window. Requests are
  idempotent today, so this only matters for pairing noise, but a nonce cache in the
  Directory object would close it.
- Files larger than 2 GB are refused, and one transfer runs at a time.

## Troubleshooting

| Symptom | Likely cause |
| --- | --- |
| `stale_request` | Phone clock is off — enable automatic time |
| `No device with that ID has connected to this relay yet` | The other phone is pointed at a different Worker URL |
| Peer shows as *Not reachable* | The app is closed/backgrounded on that phone, or the relay URL differs |
| Previews are blank | That phone hasn't granted media access (it shows a hint), or the file type has no thumbnail |
| Nothing seems to arrive through the QR code | The invite expired (5 minutes) — tap **New code** |
| Notification missing on Android 13+ | Notification permission was denied; the session still works while the app is open |

## Repository map

```
android/
  app/src/main/java/com/ayush/apnafiles/
    core/            secp256k1, Keccak-256, identity, Keystore vault
    net/             signed REST client, WebSocket relay client, wire models
    files/           MediaStore listing, thumbnails, cache, permanent saves
    session/         one relay client per paired device
    service/         foreground service that owns the transfer session
    ui/              Compose screens, view models, theme
  app/src/test/      crypto vectors shared with the Worker's primitives

cloudflare/
  src/index.ts       routing and WebSocket handshake
  src/auth.ts        signature verification + address derivation
  src/directory.ts   devices, pairing, invites, presence (Durable Object)
  src/room.ts        per-pair message relay (Durable Object)
```
# aynafiles
