import { isFailure, verifyRequest } from "./auth";
import { Directory } from "./directory";
import { Room } from "./room";
import { error, isAddress, json, type Env } from "./types";

export { Directory, Room };

const VERSION = "1.0.0";

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    const path = url.pathname;
    const method = request.method;

    if (path === "/v1/health" || path === "/health") {
      return json({ ok: true, version: VERSION });
    }

    if (path.startsWith("/v1/relay/")) {
      return handleRelay(request, env, url);
    }

    if (!path.startsWith("/v1/")) {
      return error(404, "not_found", "Not found");
    }
    if (method !== "GET" && method !== "POST") {
      return error(405, "method_not_allowed", "Use GET or POST");
    }

    const rawBody = method === "POST" ? await request.text() : "";
    const auth = verifyRequest(request, rawBody);
    if (isFailure(auth)) {
      return error(auth.status, auth.error, auth.message);
    }

    const address = auth.address;
    const directory = env.DIRECTORY.get(env.DIRECTORY.idFromName("directory"));

    try {
      switch (`${method} ${path}`) {
        case "POST /v1/register": {
          const body = parseBody<{ name?: string; platform?: string }>(rawBody);
          return forward(
            await directory.fetch("https://directory/register", {
              method: "POST",
              body: JSON.stringify({
                address,
                pub: auth.pub,
                name: body?.name,
                platform: body?.platform,
              }),
            }),
          );
        }

        case "GET /v1/peers":
          return forward(
            await directory.fetch(`https://directory/session?address=${encodeURIComponent(address)}`),
          );

        case "POST /v1/pair/request": {
          const body = parseBody<{ target: string }>(rawBody);
          if (!body || !isAddress(body.target)) {
            return error(400, "bad_request", "target must be a 0x-prefixed device ID");
          }
          return forward(
            await directory.fetch("https://directory/pair/request", {
              method: "POST",
              body: JSON.stringify({ from: address, to: body.target.toLowerCase() }),
            }),
          );
        }

        case "POST /v1/pair/respond": {
          const body = parseBody<{ requestId: string; accept: boolean }>(rawBody);
          if (!body || typeof body.requestId !== "string") {
            return error(400, "bad_request", "requestId is required");
          }
          return forward(
            await directory.fetch("https://directory/pair/respond", {
              method: "POST",
              body: JSON.stringify({
                address,
                requestId: body.requestId,
                accept: body.accept === true,
              }),
            }),
          );
        }

        case "POST /v1/pair/invite":
          return forward(
            await directory.fetch("https://directory/pair/invite", {
              method: "POST",
              body: JSON.stringify({ address }),
            }),
          );

        case "POST /v1/pair/claim": {
          const body = parseBody<{ token: string }>(rawBody);
          if (!body || typeof body.token !== "string") {
            return error(400, "bad_request", "token is required");
          }
          return forward(
            await directory.fetch("https://directory/pair/claim", {
              method: "POST",
              body: JSON.stringify({ address, token: body.token }),
            }),
          );
        }

        case "POST /v1/pair/remove": {
          const body = parseBody<{ pairId: string }>(rawBody);
          if (!body || typeof body.pairId !== "string") {
            return error(400, "bad_request", "pairId is required");
          }
          return forward(
            await directory.fetch("https://directory/pair/remove", {
              method: "POST",
              body: JSON.stringify({ address, pairId: body.pairId }),
            }),
          );
        }

        default:
          return error(404, "not_found", `No route for ${method} ${path}`);
      }
    } catch (thrown) {
      return error(500, "internal", thrown instanceof Error ? thrown.message : "Relay failure");
    }
  },
} satisfies ExportedHandler<Env>;

/**
 * WebSocket handshake for a pair room. The signature is verified here (headers survive
 * the handshake) and membership is checked against the Directory before the request is
 * handed to the Room object.
 */
async function handleRelay(request: Request, env: Env, url: URL): Promise<Response> {
  if (request.headers.get("Upgrade") !== "websocket") {
    return error(426, "upgrade_required", "Expected a websocket upgrade");
  }

  const pairId = url.pathname.split("/")[3] ?? "";
  if (!pairId) return error(400, "bad_request", "pairId is missing");

  const auth = verifyRequest(request, "");
  if (isFailure(auth)) return error(auth.status, auth.error, auth.message);

  const directory = env.DIRECTORY.get(env.DIRECTORY.idFromName("directory"));
  const pairResponse = await directory.fetch(
    `https://directory/pair?id=${encodeURIComponent(pairId)}`,
  );
  if (!pairResponse.ok) {
    return error(403, "not_paired", "This device is not paired for that room");
  }

  const pair = await pairResponse.json<{ a: string; b: string }>();
  const self = auth.address.toLowerCase();
  if (pair.a !== self && pair.b !== self) {
    return error(403, "forbidden", "Not a member of this pair");
  }
  const peer = pair.a === self ? pair.b : pair.a;

  const roomUrl = new URL("https://room/ws");
  roomUrl.searchParams.set("pair", pairId);
  roomUrl.searchParams.set("self", self);
  roomUrl.searchParams.set("peer", peer);

  const forwarded = new Request(roomUrl.toString(), request);
  return env.ROOM.get(env.ROOM.idFromName(pairId)).fetch(forwarded);
}

async function forward(response: Response): Promise<Response> {
  const text = await response.text();
  return new Response(text, {
    status: response.status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
    },
  });
}

function parseBody<T>(raw: string): T | null {
  if (!raw) return null;
  try {
    return JSON.parse(raw) as T;
  } catch {
    return null;
  }
}
