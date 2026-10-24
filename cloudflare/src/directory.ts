import {
  error,
  isAddress,
  json,
  nowSeconds,
  randomHex,
  type DeviceRecord,
  type Env,
  type InviteRecord,
  type PairRecord,
  type PairRequestRecord,
  type PeerView,
  type RequestView,
  type SessionView,
} from "./types";

const DEVICE = (address: string) => `device:${address}`;
const PAIRS = (address: string) => `pairs:${address}`;
const PAIR = (id: string) => `pair:${id}`;
const PAIR_KEY = (a: string, b: string) => `pairkey:${[a, b].sort().join(":")}`;
const REQUEST = (id: string) => `req:${id}`;
const INBOX = (address: string) => `inbox:${address}`;
const OUTBOX = (address: string) => `outbox:${address}`;
const INVITE = (token: string) => `invite:${token}`;

const REQUEST_TTL_SECONDS = 7 * 24 * 3600;
const INVITE_TTL_SECONDS = 300;

/**
 * A single Durable Object instance (`idFromName("directory")`) holding the device
 * registry, pairing state and presence counters. Strongly consistent, which keeps
 * pairing simple: one place decides who is paired with whom.
 */
export class Directory implements DurableObject {
  constructor(
    private readonly state: DurableObjectState,
    private readonly env: Env,
  ) {}

  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);
    try {
      switch (url.pathname) {
        case "/register":
          return await this.register(request);
        case "/session":
          return await this.sessionResponse(url.searchParams.get("address") ?? "");
        case "/pair/request":
          return await this.requestPair(request);
        case "/pair/respond":
          return await this.respondPair(request);
        case "/pair/invite":
          return await this.createInvite(request);
        case "/pair/claim":
          return await this.claimInvite(request);
        case "/pair/remove":
          return await this.removePair(request);
        case "/pair":
          return await this.getPair(url.searchParams.get("id") ?? "");
        case "/presence":
          return await this.presence(request);
        default:
          return error(404, "not_found", "Unknown directory operation");
      }
    } catch (thrown) {
      return error(500, "internal", thrown instanceof Error ? thrown.message : "Directory failure");
    }
  }

  // ---------------------------------------------------------------- devices

  private async register(request: Request): Promise<Response> {
    const body = await readJson<{ address: string; pub: string; name?: string; platform?: string }>(request);
    if (!body || !isAddress(body.address) || typeof body.pub !== "string") {
      return error(400, "bad_request", "address and pub are required");
    }
    const address = body.address.toLowerCase();
    const now = nowSeconds();
    const existing = await this.getDevice(address);
    const device: DeviceRecord = {
      address,
      pub: body.pub.toLowerCase(),
      name: clamp(body.name ?? existing?.name ?? "Android device", 60),
      platform: clamp(body.platform ?? existing?.platform ?? "android", 20),
      createdAt: existing?.createdAt ?? now,
      lastSeen: now,
      connections: existing?.connections ?? 0,
    };
    await this.state.storage.put(DEVICE(address), device);
    return this.sessionResponse(address);
  }

  private async presence(request: Request): Promise<Response> {
    const body = await readJson<{ address: string; delta: number }>(request);
    if (!body || !isAddress(body.address) || !Number.isFinite(body.delta)) {
      return error(400, "bad_request", "address and delta are required");
    }
    const address = body.address.toLowerCase();
    const device = await this.getDevice(address);
    if (!device) return json({ ok: true, ignored: true });

    device.connections = Math.max(0, (device.connections ?? 0) + Math.sign(body.delta));
    device.lastSeen = nowSeconds();
    await this.state.storage.put(DEVICE(address), device);
    return json({ ok: true });
  }

  private async sessionResponse(addressRaw: string): Promise<Response> {
    const address = addressRaw.toLowerCase();
    const device = await this.getDevice(address);
    if (!device) return error(404, "unknown_device", "This device is not registered");

    const now = nowSeconds();
    const pairIds = await this.getArray(PAIRS(address));
    const peers: PeerView[] = [];

    for (const pairId of pairIds) {
      const pair = await this.state.storage.get<PairRecord>(PAIR(pairId));
      if (!pair) continue;
      const other = pair.a === address ? pair.b : pair.a;
      const otherDevice = await this.getDevice(other);
      peers.push({
        address: other,
        name: otherDevice?.name ?? "",
        pairId,
        online: (otherDevice?.connections ?? 0) > 0,
        lastSeen: otherDevice?.lastSeen ?? 0,
      });
    }

    const incoming: RequestView[] = [];
    for (const requestId of await this.getArray(INBOX(address))) {
      const record = await this.state.storage.get<PairRequestRecord>(REQUEST(requestId));
      if (!record || record.to !== address) continue;
      if (now - record.ts > REQUEST_TTL_SECONDS) continue;
      const from = await this.getDevice(record.from);
      incoming.push({
        id: record.id,
        from: record.from,
        to: record.to,
        fromName: from?.name ?? "",
        toName: device.name,
        ts: record.ts,
      });
    }

    const outgoing: RequestView[] = [];
    for (const requestId of await this.getArray(OUTBOX(address))) {
      const record = await this.state.storage.get<PairRequestRecord>(REQUEST(requestId));
      if (!record || record.from !== address) continue;
      if (now - record.ts > REQUEST_TTL_SECONDS) continue;
      const to = await this.getDevice(record.to);
      outgoing.push({
        id: record.id,
        from: record.from,
        to: record.to,
        fromName: device.name,
        toName: to?.name ?? "",
        ts: record.ts,
      });
    }

    const view: SessionView = {
      address,
      name: device.name,
      peers,
      incoming,
      outgoing,
    };
    return json(view);
  }

  // ---------------------------------------------------------------- pairing

  private async requestPair(request: Request): Promise<Response> {
    const body = await readJson<{ from: string; to: string }>(request);
    if (!body || !isAddress(body.from) || !isAddress(body.to)) {
      return error(400, "bad_request", "from and to are required");
    }
    const from = body.from.toLowerCase();
    const to = body.to.toLowerCase();

    if (from === to) return error(400, "self_pair", "A device cannot pair with itself");
    if (!(await this.getDevice(from))) return error(404, "unknown_device", "Sender is not registered");
    if (!(await this.getDevice(to))) {
      return error(404, "unknown_device", "No device with that ID has connected to this relay yet");
    }
    if (await this.storageGet<string>(PAIR_KEY(from, to))) {
      return error(409, "already_paired", "These devices are already paired");
    }

    for (const requestId of await this.getArray(OUTBOX(from))) {
      const record = await this.state.storage.get<PairRequestRecord>(REQUEST(requestId));
      if (record && record.to === to) return json({ requestId: record.id });
    }

    const record: PairRequestRecord = {
      id: "r_" + randomHex(8),
      from,
      to,
      ts: nowSeconds(),
    };
    await this.state.storage.put(REQUEST(record.id), record);
    await this.pushUnique(INBOX(to), record.id);
    await this.pushUnique(OUTBOX(from), record.id);
    return json({ requestId: record.id });
  }

  private async respondPair(request: Request): Promise<Response> {
    const body = await readJson<{ address: string; requestId: string; accept: boolean }>(request);
    if (!body || !isAddress(body.address) || typeof body.requestId !== "string") {
      return error(400, "bad_request", "address and requestId are required");
    }
    const address = body.address.toLowerCase();
    const record = await this.state.storage.get<PairRequestRecord>(REQUEST(body.requestId));
    if (!record || record.to !== address) {
      return error(404, "request_not_found", "That pairing request no longer exists");
    }

    await this.removeValue(INBOX(address), record.id);
    await this.removeValue(OUTBOX(record.from), record.id);
    await this.state.storage.delete(REQUEST(record.id));

    if (!body.accept) return json({ pairId: "", peer: null });

    const pair = await this.createPair(record.from, record.to);
    const peerDevice = await this.getDevice(record.from);
    return json({
      pairId: pair.id,
      peer: {
        address: record.from,
        name: peerDevice?.name ?? "",
        pairId: pair.id,
        online: (peerDevice?.connections ?? 0) > 0,
        lastSeen: peerDevice?.lastSeen ?? 0,
      },
    });
  }

  private async createInvite(request: Request): Promise<Response> {
    const body = await readJson<{ address: string }>(request);
    if (!body || !isAddress(body.address)) return error(400, "bad_request", "address is required");
    const address = body.address.toLowerCase();
    if (!(await this.getDevice(address))) return error(404, "unknown_device", "Register first");

    const token = randomHex(24);
    const invite: InviteRecord = { address, expiresAt: nowSeconds() + INVITE_TTL_SECONDS };
    await this.state.storage.put(INVITE(token), invite);
    await this.sweepInvites();
    return json({ token, expiresAt: invite.expiresAt });
  }

  private async claimInvite(request: Request): Promise<Response> {
    const body = await readJson<{ address: string; token: string }>(request);
    if (!body || !isAddress(body.address) || typeof body.token !== "string") {
      return error(400, "bad_request", "address and token are required");
    }
    const address = body.address.toLowerCase();
    const invite = await this.state.storage.get<InviteRecord>(INVITE(body.token));
    if (!invite) return error(404, "invite_not_found", "That code is no longer valid");
    if (invite.expiresAt < nowSeconds()) {
      await this.state.storage.delete(INVITE(body.token));
      return error(410, "invite_expired", "That code has expired. Show a fresh one and scan again.");
    }
    if (invite.address === address) {
      return error(400, "self_pair", "That is this device's own code");
    }

    const pair = await this.createPair(invite.address, address);
    await this.state.storage.delete(INVITE(body.token));
    const peerDevice = await this.getDevice(invite.address);
    return json({
      pairId: pair.id,
      peer: {
        address: invite.address,
        name: peerDevice?.name ?? "",
        pairId: pair.id,
        online: (peerDevice?.connections ?? 0) > 0,
        lastSeen: peerDevice?.lastSeen ?? 0,
      },
    });
  }

  private async removePair(request: Request): Promise<Response> {
    const body = await readJson<{ address: string; pairId: string }>(request);
    if (!body || !isAddress(body.address) || typeof body.pairId !== "string") {
      return error(400, "bad_request", "address and pairId are required");
    }
    const address = body.address.toLowerCase();
    const pair = await this.state.storage.get<PairRecord>(PAIR(body.pairId));
    if (!pair) return json({ ok: true });
    if (pair.a !== address && pair.b !== address) {
      return error(403, "forbidden", "This pair does not belong to that device");
    }

    await this.removeValue(PAIRS(pair.a), pair.id);
    await this.removeValue(PAIRS(pair.b), pair.id);
    await this.state.storage.delete(PAIR_KEY(pair.a, pair.b));
    await this.state.storage.delete(PAIR(pair.id));
    return json({ ok: true });
  }

  private async getPair(pairId: string): Promise<Response> {
    const pair = await this.state.storage.get<PairRecord>(PAIR(pairId));
    if (!pair) return error(404, "pair_not_found", "Unknown pair");
    return json(pair);
  }

  private async createPair(a: string, b: string): Promise<PairRecord> {
    const key = PAIR_KEY(a, b);
    const existingId = await this.storageGet<string>(key);
    if (existingId) {
      const existing = await this.state.storage.get<PairRecord>(PAIR(existingId));
      if (existing) return existing;
    }
    const pair: PairRecord = {
      id: "p_" + randomHex(8),
      a,
      b,
      createdAt: nowSeconds(),
    };
    await this.state.storage.put(PAIR(pair.id), pair);
    await this.state.storage.put(key, pair.id);
    await this.pushUnique(PAIRS(a), pair.id);
    await this.pushUnique(PAIRS(b), pair.id);
    return pair;
  }

  private async sweepInvites(): Promise<void> {
    const all = await this.state.storage.list<InviteRecord>({ prefix: "invite:" });
    const now = nowSeconds();
    const expired: string[] = [];
    all.forEach((invite, key) => {
      if (!invite || invite.expiresAt < now) expired.push(key);
    });
    for (const key of expired) await this.state.storage.delete(key);
  }

  // ---------------------------------------------------------------- helpers

  private async getDevice(address: string): Promise<DeviceRecord | null> {
    return (await this.state.storage.get<DeviceRecord>(DEVICE(address))) ?? null;
  }

  private async storageGet<T>(key: string): Promise<T | null> {
    return (await this.state.storage.get<T>(key)) ?? null;
  }

  private async getArray(key: string): Promise<string[]> {
    const value = await this.state.storage.get<string[]>(key);
    return Array.isArray(value) ? value : [];
  }

  private async pushUnique(key: string, value: string): Promise<void> {
    const current = await this.getArray(key);
    if (!current.includes(value)) {
      current.push(value);
      await this.state.storage.put(key, current);
    }
  }

  private async removeValue(key: string, value: string): Promise<void> {
    const current = await this.getArray(key);
    const next = current.filter((entry) => entry !== value);
    if (next.length !== current.length) {
      await this.state.storage.put(key, next);
    }
  }
}

async function readJson<T>(request: Request): Promise<T | null> {
  try {
    return await request.json<T>();
  } catch {
    return null;
  }
}

function clamp(value: string, max: number): string {
  const trimmed = value.trim();
  return (trimmed.length > 0 ? trimmed : "device").slice(0, max);
}
