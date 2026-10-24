import { secp256k1 } from "@noble/curves/secp256k1";
import { sha256 } from "@noble/hashes/sha256";
import { keccak_256 } from "@noble/hashes/sha3";
import { bytesToHex, hexToBytes } from "@noble/hashes/utils";

const MAX_CLOCK_SKEW_SECONDS = 300;

export interface AuthedRequest {
  address: string;
  pub: string;
  ts: number;
}

export interface AuthFailure {
  error: string;
  message: string;
  status: number;
}

/**
 * Ethereum-style address derived from an uncompressed secp256k1 public key.
 * The client sends its public key with every request; the address is always derived
 * here, never accepted from the caller.
 */
export function addressFromPublicKey(publicKeyHex: string): string {
  const clean = publicKeyHex.toLowerCase().replace(/^0x/, "");
  const bytes = hexToBytes(clean);
  const body = bytes.length === 65 && bytes[0] === 4 ? bytes.subarray(1) : bytes;
  const digest = keccak_256(body);
  return "0x" + bytesToHex(digest.subarray(12));
}

function safeHexToBytes(value: string): Uint8Array | null {
  try {
    return hexToBytes(value.toLowerCase().replace(/^0x/, ""));
  } catch {
    return null;
  }
}

/**
 * Verifies `X-Apna-Pub`, `X-Apna-Ts` and `X-Apna-Sig` over the canonical string
 * `METHOD:path:sha256(body):ts`.
 */
export function verifyRequest(
  request: Request,
  rawBody: string,
): AuthedRequest | AuthFailure {
  const pub = request.headers.get("X-Apna-Pub") ?? "";
  const tsRaw = request.headers.get("X-Apna-Ts") ?? "";
  const signature = request.headers.get("X-Apna-Sig") ?? "";

  if (!pub || !tsRaw || !signature) {
    return { error: "unauthorized", message: "Missing signature headers", status: 401 };
  }

  const ts = Number(tsRaw);
  if (!Number.isFinite(ts) || Math.abs(Date.now() / 1000 - ts) > MAX_CLOCK_SKEW_SECONDS) {
    return {
      error: "stale_request",
      message: "The request timestamp is too far from the relay's clock. Check the device clock.",
      status: 401,
    };
  }

  const pubBytes = safeHexToBytes(pub);
  if (!pubBytes || pubBytes.length !== 65 || pubBytes[0] !== 4) {
    return { error: "bad_public_key", message: "Expected an uncompressed secp256k1 key", status: 401 };
  }

  const sigBytes = safeHexToBytes(signature);
  if (!sigBytes || sigBytes.length !== 64) {
    return { error: "bad_signature", message: "Signature must be 64 bytes (r || s)", status: 401 };
  }

  const url = new URL(request.url);
  const bodyHash = bytesToHex(sha256(new TextEncoder().encode(rawBody)));
  const message = `${request.method}:${url.pathname}:${bodyHash}:${ts}`;
  const digest = sha256(new TextEncoder().encode(message));

  try {
    const valid = secp256k1.verify(sigBytes, digest, pubBytes, { lowS: true });
    if (!valid) {
      return { error: "bad_signature", message: "Signature verification failed", status: 401 };
    }
  } catch {
    return { error: "bad_signature", message: "Malformed signature", status: 401 };
  }

  return { address: addressFromPublicKey(pub), pub: pub.toLowerCase(), ts };
}

export function isFailure(result: AuthedRequest | AuthFailure): result is AuthFailure {
  return (result as AuthFailure).error !== undefined;
}
