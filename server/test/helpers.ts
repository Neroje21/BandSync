import WebSocket from 'ws';
import {
  EMPTY_STATUS,
  type ClientMsg,
  type MemberStatus,
  type RoomSnapshot,
  type ServerMsg,
} from '../../shared/protocol';
import { ClockSync } from '../../shared/clockSync';

export interface SimOptions {
  /** This fake phone's clock runs `skewMs` ahead of the real one. */
  skewMs?: number;
  /** Artificial one-way delay before a message reaches the server / reaches this client. */
  sendDelayMs?: number;
  recvDelayMs?: number;
  /** Random extra delay (0..jitterMs) applied to ping/pong only, like a noisy network. */
  jitterMs?: number;
}

type Waiter = { match: (m: ServerMsg) => boolean; resolve: (m: ServerMsg) => void; reject: (e: Error) => void };

export class TestClient {
  readonly clock: ClockSync;
  id = '';
  room: RoomSnapshot | null = null;
  closed = false;
  private queue: ServerMsg[] = [];
  private waiters: Waiter[] = [];
  private pingId = 0;

  private constructor(
    readonly ws: WebSocket,
    private readonly opts: SimOptions,
  ) {
    this.clock = new ClockSync(() => this.now());
    ws.on('message', (data) => {
      const msg = JSON.parse(data.toString()) as ServerMsg;
      const jitter = msg.t === 'pong' ? Math.random() * (opts.jitterMs ?? 0) : 0;
      setTimeout(() => this.deliver(msg), (opts.recvDelayMs ?? 0) + jitter);
    });
    ws.on('close', () => {
      this.closed = true;
    });
  }

  static connect(port: number, opts: SimOptions = {}): Promise<TestClient> {
    return new Promise((resolve, reject) => {
      const ws = new WebSocket(`ws://127.0.0.1:${port}`);
      ws.once('open', () => resolve(new TestClient(ws, opts)));
      ws.once('error', reject);
    });
  }

  /** This simulated phone's local clock. */
  now(): number {
    return Date.now() + (this.opts.skewMs ?? 0);
  }

  send(msg: ClientMsg): void {
    const jitter = msg.t === 'ping' ? Math.random() * (this.opts.jitterMs ?? 0) : 0;
    setTimeout(() => this.ws.send(JSON.stringify(msg)), (this.opts.sendDelayMs ?? 0) + jitter);
  }

  sendRaw(text: string): void {
    this.ws.send(text);
  }

  /** Run `count` ping/pong exchanges through the real ClockSync, spaced `gapMs` apart. */
  async syncClock(count: number, gapMs = 30): Promise<void> {
    for (let i = 0; i < count; i++) {
      const ping = this.clock.nextPing(++this.pingId);
      this.send(ping);
      const pong = (await this.wait((m) => m.t === 'pong' && m.id === ping.id)) as Extract<ServerMsg, { t: 'pong' }>;
      this.clock.onPong(pong, this.now());
      await new Promise((r) => setTimeout(r, gapMs));
    }
  }

  async create(name: string): Promise<RoomSnapshot> {
    this.send({ t: 'create', name });
    return this.afterJoin();
  }

  async join(code: string, name: string): Promise<RoomSnapshot> {
    this.send({ t: 'join', code, name });
    return this.afterJoin();
  }

  private async afterJoin(): Promise<RoomSnapshot> {
    const joined = (await this.wait((m) => m.t === 'joined' || m.t === 'error')) as ServerMsg;
    if (joined.t === 'error') throw new Error(`${joined.code}: ${joined.message}`);
    if (joined.t !== 'joined') throw new Error('unexpected message');
    this.id = joined.you;
    return joined.room;
  }

  /** Wait for the next message satisfying `match` (checking already-received ones first). */
  wait(match: (m: ServerMsg) => boolean, timeoutMs = 3000): Promise<ServerMsg> {
    const idx = this.queue.findIndex(match);
    if (idx >= 0) return Promise.resolve(this.queue.splice(idx, 1)[0] as ServerMsg);
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('timed out waiting for a server message')), timeoutMs);
      this.waiters.push({
        match,
        resolve: (m) => {
          clearTimeout(timer);
          resolve(m);
        },
        reject,
      });
    });
  }

  async waitRoom(pred: (room: RoomSnapshot) => boolean = () => true): Promise<RoomSnapshot> {
    const m = (await this.wait((x) => x.t === 'room' && pred(x.room))) as Extract<ServerMsg, { t: 'room' }>;
    return m.room;
  }

  async waitError(): Promise<Extract<ServerMsg, { t: 'error' }>> {
    return (await this.wait((m) => m.t === 'error')) as Extract<ServerMsg, { t: 'error' }>;
  }

  /** Forget everything received so far, so the next wait only sees new messages. */
  drain(): void {
    this.queue = [];
  }

  close(): void {
    this.ws.close();
  }

  private deliver(msg: ServerMsg): void {
    if (msg.t === 'room' || msg.t === 'joined') this.room = msg.room;
    const i = this.waiters.findIndex((w) => w.match(msg));
    if (i >= 0) this.waiters.splice(i, 1)[0]?.resolve(msg);
    else this.queue.push(msg);
  }
}

/** A member whose Songsterr player is loaded, unlocked and on the given song. */
export function readyStatus(url: string, over: Partial<MemberStatus> = {}): MemberStatus {
  return {
    ...EMPTY_STATUS,
    url,
    playerReady: true,
    audioUnlocked: true,
    player: { speed: 'Speed 100%', source: 'Orig', countIn: false, revision: 'Revision A' },
    rttMs: 40,
    ...over,
  };
}

export const TAB = 'https://www.songsterr.com/a/wsa/metallica-enter-sandman-tab-s19';
