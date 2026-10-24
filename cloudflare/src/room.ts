import type { Env } from "./types";

interface Attachment {
  address: string;
  peer: string;
  pairId: string;
}

/**
 * One Room Durable Object per pair. It holds exactly the two WebSockets and forwards
 * text frames between them. Nothing is written to storage: the relay is a pipe, not a
 * mailbox, so a file transfer never persists on Cloudflare.
 *
 * Uses the WebSocket Hibernation API so idle rooms cost (almost) nothing.
 */
export class Room implements DurableObject {
  constructor(
    private readonly state: DurableObjectState,
    private readonly env: Env,
  ) {}

  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);
    if (url.pathname !== "/ws") return new Response("not found", { status: 404 });
    if (request.headers.get("Upgrade") !== "websocket") {
      return new Response("expected websocket", { status: 426 });
    }

    const pairId = url.searchParams.get("pair") ?? "";
    const self = url.searchParams.get("self") ?? "";
    const peer = url.searchParams.get("peer") ?? "";
    if (!pairId || !self || !peer) {
      return new Response("missing parameters", { status: 400 });
    }

    const pair = new WebSocketPair();
    const client = pair[0];
    const server = pair[1];

    this.state.acceptWebSocket(server);
    const attachment: Attachment = { address: self, peer, pairId };
    server.serializeAttachment(attachment);

    const peerSockets = this.socketsFor(peer);
    this.send(server, { t: "welcome", peerOnline: peerSockets.length > 0 });
    for (const socket of peerSockets) {
      this.send(socket, { t: "presence", address: self, peerOnline: true });
    }
    await this.updatePresence(self, 1);

    return new Response(null, { status: 101, webSocket: client });
  }

  async webSocketMessage(ws: WebSocket, message: string | ArrayBuffer): Promise<void> {
    if (typeof message !== "string") return;
    const attachment = this.attachment(ws);
    if (!attachment) return;

    for (const target of this.socketsFor(attachment.peer)) {
      try {
        target.send(message);
      } catch {
        // The peer went away between the lookup and the send.
      }
    }
  }

  async webSocketClose(ws: WebSocket, code: number, reason: string, wasClean: boolean): Promise<void> {
    await this.dropSocket(ws);
  }

  async webSocketError(ws: WebSocket, thrown: unknown): Promise<void> {
    await this.dropSocket(ws);
  }

  private async dropSocket(ws: WebSocket): Promise<void> {
    const attachment = this.attachment(ws);
    try {
      ws.close(1000, "closed");
    } catch {
      // Already closed.
    }
    if (!attachment) return;

    const stillOpen = this.state
      .getWebSockets()
      .filter(
        (socket) =>
          socket !== ws &&
          socket.readyState === 1 &&
          this.attachment(socket)?.address === attachment.address,
      );
    if (stillOpen.length > 0) return;

    for (const socket of this.socketsFor(attachment.peer)) {
      this.send(socket, { t: "presence", address: attachment.address, peerOnline: false });
    }
    await this.updatePresence(attachment.address, -1);
  }

  private socketsFor(address: string): WebSocket[] {
    return this.state
      .getWebSockets()
      .filter((socket) => socket.readyState === 1 && this.attachment(socket)?.address === address);
  }

  private attachment(ws: WebSocket): Attachment | null {
    try {
      const value = ws.deserializeAttachment() as Attachment | null;
      if (!value || typeof value.address !== "string" || typeof value.peer !== "string") {
        return null;
      }
      return value;
    } catch {
      return null;
    }
  }

  private send(ws: WebSocket, payload: unknown): void {
    try {
      ws.send(JSON.stringify(payload));
    } catch {
      // Socket disappeared; presence events will clean up.
    }
  }

  private async updatePresence(address: string, delta: number): Promise<void> {
    try {
      const directory = this.env.DIRECTORY.get(this.env.DIRECTORY.idFromName("directory"));
      await directory.fetch("https://directory/presence", {
        method: "POST",
        body: JSON.stringify({ address, delta }),
      });
    } catch {
      // Presence is cosmetic; never fail a socket because of it.
    }
  }
}
