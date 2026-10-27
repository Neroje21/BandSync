import { ClockSync } from '../../../shared/clockSync';
import type { ClientMsg, RoomSnapshot, ServerMsg } from '../../../shared/protocol';

export interface ClientEvents {
  onOpen(): void;
  onJoined(you: string, room: RoomSnapshot): void;
  onRoom(room: RoomSnapshot): void;
  onError(code: string, message: string): void;
  /** The socket is gone. `reason` is set when it was not the user's choice. */
  onClose(reason: string | null): void;
}

const CONNECT_TIMEOUT_MS = 8000;
const BURST_PINGS = 12; // quick burst on connect so the clock estimate converges fast
const BURST_GAP_MS = 120;
const STEADY_PING_MS = 4000;

const LOCAL_HOST = /^(localhost|\d{1,3}(\.\d{1,3}){3}|[\w-]+\.local)(:\d+)?(\/.*)?$/i;

/**
 * Accepts "192.168.1.20:8080", "jam.example.com", "ws://...", "wss://...", "http(s)://..." and returns
 * a ws(s):// URL. A bare local/IP address means plain ws:// (same Wi-Fi); any other bare host is
 * assumed to be reachable over TLS, which is also what an installed Android app requires.
 */
export function normalizeServerAddress(input: string): string | null {
  const t = input.trim().replace(/\/+$/, '');
  if (!t) return null;
  if (/^wss?:\/\//i.test(t)) return t;
  if (/^https?:\/\//i.test(t)) return t.replace(/^http/i, 'ws');
  if (LOCAL_HOST.test(t)) return `ws://${t}`;
  if (/^[\w.-]+(:\d+)?(\/.*)?$/.test(t)) return `wss://${t}`;
  return null;
}

export class SessionClient {
  readonly clock = new ClockSync();
  private ws: WebSocket | null = null;
  private pingId = 0;
  private pingTimer: ReturnType<typeof setTimeout> | null = null;
  private connectTimer: ReturnType<typeof setTimeout> | null = null;
  private closedByUser = false;

  constructor(
    private readonly url: string,
    private readonly ev: ClientEvents,
  ) {}

  connect(): void {
    let ws: WebSocket;
    try {
      ws = new WebSocket(this.url);
    } catch {
      this.ev.onClose('That server address is not valid.');
      return;
    }
    this.ws = ws;
    this.connectTimer = setTimeout(() => {
      this.ev.onClose(`Could not reach ${this.url}. Is the server running and on the same network?`);
      this.teardown();
    }, CONNECT_TIMEOUT_MS);

    ws.onopen = () => {
      if (this.connectTimer) clearTimeout(this.connectTimer);
      this.connectTimer = null;
      this.startPinging();
      this.ev.onOpen();
    };
    ws.onmessage = (e) => {
      const rx = Date.now(); // stamp the arrival before any parsing work
      let msg: ServerMsg;
      try {
        msg = JSON.parse(String(e.data)) as ServerMsg;
      } catch {
        return;
      }
      switch (msg.t) {
        case 'pong':
          this.clock.onPong(msg, rx);
          break;
        case 'joined':
          this.ev.onJoined(msg.you, msg.room);
          break;
        case 'room':
          this.ev.onRoom(msg.room);
          break;
        case 'error':
          this.ev.onError(msg.code, msg.message);
          break;
      }
    };
    ws.onerror = () => {
      /* onclose follows and carries the outcome */
    };
    ws.onclose = () => {
      const byUser = this.closedByUser;
      this.teardown();
      this.ev.onClose(byUser ? null : 'Lost connection to the server.');
    };
  }

  send(msg: ClientMsg): void {
    if (this.ws && this.ws.readyState === 1) this.ws.send(JSON.stringify(msg));
  }

  close(): void {
    this.closedByUser = true;
    this.teardown();
  }

  private startPinging(): void {
    let sent = 0;
    const step = (): void => {
      this.send(this.clock.nextPing(++this.pingId));
      sent++;
      this.pingTimer = setTimeout(step, sent < BURST_PINGS ? BURST_GAP_MS : STEADY_PING_MS);
    };
    step();
  }

  private teardown(): void {
    if (this.pingTimer) clearTimeout(this.pingTimer);
    if (this.connectTimer) clearTimeout(this.connectTimer);
    this.pingTimer = null;
    this.connectTimer = null;
    const ws = this.ws;
    this.ws = null;
    if (ws) {
      ws.onopen = ws.onmessage = ws.onerror = ws.onclose = null;
      try {
        ws.close();
      } catch {
        /* already closed */
      }
    }
  }
}
