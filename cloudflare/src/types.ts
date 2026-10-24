export interface Env {
  DIRECTORY: DurableObjectNamespace;
  ROOM: DurableObjectNamespace;
}

export interface DeviceRecord {
  address: string;
  pub: string;
  name: string;
  platform: string;
  createdAt: number;
  lastSeen: number;
  /** Number of relay sockets currently open for this device. */
  connections: number;
}

export interface PairRecord {
  id: string;
  a: string;
  b: string;
  createdAt: number;
}

export interface PairRequestRecord {
  id: string;
  from: string;
  to: string;
  ts: number;
}

export interface InviteRecord {
  address: string;
  expiresAt: number;
}

export interface PeerView {
  address: string;
  name: string;
  pairId: string;
  online: boolean;
  lastSeen: number;
}

export interface RequestView {
  id: string;
  from: string;
  to: string;
  fromName: string;
  toName: string;
  ts: number;
}

export interface SessionView {
  address: string;
  name: string;
  peers: PeerView[];
  incoming: RequestView[];
  outgoing: RequestView[];
}

export function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
    },
  });
}

export function error(status: number, code: string, message: string): Response {
  return json({ error: code, message }, status);
}

export function randomHex(bytes: number): string {
  const buffer = new Uint8Array(bytes);
  crypto.getRandomValues(buffer);
  return Array.from(buffer)
    .map((byte) => byte.toString(16).padStart(2, "0"))
    .join("");
}

export function nowSeconds(): number {
  return Math.floor(Date.now() / 1000);
}

export function isAddress(value: unknown): value is string {
  return typeof value === "string" && /^0x[0-9a-f]{40}$/.test(value.toLowerCase());
}
