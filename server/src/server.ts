import { randomInt, randomUUID } from 'node:crypto';
import { createServer as createHttpServer } from 'node:http';
import { WebSocketServer, type WebSocket } from 'ws';
import {
  MAX_NAME_LEN,
  ROOM_CODE_ALPHABET,
  ROOM_CODE_LEN,
  normalizeRoomCode,
  type ClientMsg,
  type ErrorCode,
  type MemberStatus,
  type ServerMsg,
} from '../../shared/protocol';
import { Room, type RoomError } from './room';

const MAX_ROOMS = 500;
const MAX_PAYLOAD_BYTES = 16 * 1024;
const MAX_MSGS_PER_SECOND = 100;
const HEARTBEAT_MS = 15_000;

export interface RunningServer {
  port: number;
  roomCount(): number;
  close(): Promise<void>;
}

export interface ServerOptions {
  port: number;
  host?: string;
  /** Injectable for tests. */
  now?: () => number;
}

function cleanName(v: unknown): string {
  const raw = typeof v === 'string' ? v : '';
  // eslint-disable-next-line no-control-regex
  const name = raw.replace(/[\u0000-\u001f\u007f]/g, '').trim().slice(0, MAX_NAME_LEN);
  return name || 'Player';
}

function optStr(v: unknown, max = 200): string | null {
  return typeof v === 'string' ? v.slice(0, max) : null;
}

function optBool(v: unknown): boolean | null {
  return typeof v === 'boolean' ? v : null;
}

function cleanStatus(v: unknown): MemberStatus | null {
  if (typeof v !== 'object' || v === null) return null;
  const s = v as Record<string, unknown>;
  const p = typeof s.player === 'object' && s.player !== null ? (s.player as Record<string, unknown>) : null;
  const rtt = typeof s.rttMs === 'number' && Number.isFinite(s.rttMs) ? Math.max(0, Math.min(5000, s.rttMs)) : null;
  return {
    url: optStr(s.url, 300),
    playerReady: s.playerReady === true,
    audioUnlocked: s.audioUnlocked === true,
    playing: s.playing === true,
    player: p
      ? { speed: optStr(p.speed), source: optStr(p.source), countIn: optBool(p.countIn), revision: optStr(p.revision) }
      : null,
    rttMs: rtt,
  };
}

/** Returns null for anything that is not a well-formed client message. */
export function parseClientMsg(raw: string): ClientMsg | null {
  let m: unknown;
  try {
    m = JSON.parse(raw);
  } catch {
    return null;
  }
  if (typeof m !== 'object' || m === null) return null;
  const o = m as Record<string, unknown>;
  switch (o.t) {
    case 'ping':
      return typeof o.id === 'number' && typeof o.c0 === 'number' ? { t: 'ping', id: o.id, c0: o.c0 } : null;
    case 'create':
      return { t: 'create', name: cleanName(o.name) };
    case 'join':
      return typeof o.code === 'string' ? { t: 'join', code: o.code, name: cleanName(o.name) } : null;
    case 'setTab':
      return typeof o.url === 'string' && o.url.length <= 500 ? { t: 'setTab', url: o.url } : null;
    case 'status': {
      const status = cleanStatus(o.status);
      return status ? { t: 'status', status } : null;
    }
    case 'play':
      return { t: 'play', force: o.force === true };
    case 'stop':
      return { t: 'stop' };
    case 'leave':
      return { t: 'leave' };
    default:
      return null;
  }
}

function newRoomCode(taken: (code: string) => boolean): string {
  for (;;) {
    let code = '';
    for (let i = 0; i < ROOM_CODE_LEN; i++) code += ROOM_CODE_ALPHABET[randomInt(ROOM_CODE_ALPHABET.length)];
    if (!taken(code)) return code;
  }
}

export function startServer(opts: ServerOptions): Promise<RunningServer> {
  const now = opts.now ?? Date.now;
  const rooms = new Map<string, Room>();

  // Plain HTTP answers health checks from hosting platforms; WebSockets upgrade on the same port.
  const http = createHttpServer((_req, res) => {
    res.writeHead(200, { 'content-type': 'text/plain' });
    res.end('songsterr-jam ok\n');
  });
  const wss = new WebSocketServer({ server: http, maxPayload: MAX_PAYLOAD_BYTES });
  const alive = new WeakSet<WebSocket>();

  wss.on('connection', (ws) => {
    alive.add(ws);
    ws.on('pong', () => alive.add(ws));

    let room: Room | null = null;
    let memberId: string | null = null;
    let windowStart = now();
    let windowCount = 0;

    const send = (msg: ServerMsg): void => {
      if (ws.readyState === ws.OPEN) ws.send(JSON.stringify(msg));
    };
    const fail = (err: RoomError | { code: ErrorCode; message: string }): void =>
      send({ t: 'error', code: err.code, message: err.message });

    const leaveRoom = (): void => {
      if (!room || !memberId) return;
      const left = room;
      left.leave(memberId);
      if (left.size === 0) rooms.delete(left.code);
      else left.broadcast();
      room = null;
      memberId = null;
    };

    const enter = (target: Room, name: string): void => {
      const id = randomUUID().slice(0, 8);
      const err = target.join(id, name, send);
      if (err) return fail(err);
      room = target;
      memberId = id;
      send({ t: 'joined', you: id, room: target.snapshot() });
      target.broadcast();
    };

    ws.on('message', (data) => {
      const rx = now(); // read the clock before doing anything else: this is the pong timestamp
      if (rx - windowStart >= 1000) {
        windowStart = rx;
        windowCount = 0;
      }
      if (++windowCount > MAX_MSGS_PER_SECOND) return ws.close(1008, 'rate limit');

      const msg = parseClientMsg(data.toString());
      if (!msg) return fail({ code: 'bad_message', message: 'Malformed message.' });

      if (msg.t === 'ping') return send({ t: 'pong', id: msg.id, c0: msg.c0, s: rx });

      if (msg.t === 'create' || msg.t === 'join') {
        if (room) return fail({ code: 'already_joined', message: 'Already in a session.' });
        if (msg.t === 'create') {
          if (rooms.size >= MAX_ROOMS) return fail({ code: 'server_busy', message: 'Server is busy, try again later.' });
          const created = new Room(newRoomCode((c) => rooms.has(c)), now);
          rooms.set(created.code, created);
          enter(created, msg.name);
          if (created.size === 0) rooms.delete(created.code);
        } else {
          const target = rooms.get(normalizeRoomCode(msg.code));
          if (!target) return fail({ code: 'not_found', message: 'No session with that code.' });
          enter(target, msg.name);
        }
        return;
      }

      if (!room || !memberId) return fail({ code: 'not_joined', message: 'Join a session first.' });

      switch (msg.t) {
        case 'leave':
          return leaveRoom();
        case 'status':
          room.updateStatus(memberId, msg.status);
          return room.broadcast();
        case 'setTab':
        case 'play':
        case 'stop': {
          const err =
            msg.t === 'setTab'
              ? room.setTab(memberId, msg.url)
              : msg.t === 'play'
                ? room.play(memberId, msg.force === true)
                : room.stop(memberId);
          if (err) return fail(err);
          return room.broadcast();
        }
      }
    });

    ws.on('close', leaveRoom);
    ws.on('error', () => ws.terminate());
  });

  // Drop sockets that stopped answering (phone lost signal, app killed).
  const heartbeat = setInterval(() => {
    for (const ws of wss.clients) {
      if (!alive.has(ws)) {
        ws.terminate();
        continue;
      }
      alive.delete(ws);
      ws.ping();
    }
  }, HEARTBEAT_MS);

  return new Promise((resolve, reject) => {
    http.once('error', reject);
    http.listen(opts.port, opts.host, () => {
      const addr = http.address();
      const port = typeof addr === 'object' && addr ? addr.port : opts.port;
      resolve({
        port,
        roomCount: () => rooms.size,
        close: () =>
          new Promise<void>((done) => {
            clearInterval(heartbeat);
            for (const ws of wss.clients) ws.terminate();
            wss.close(() => http.close(() => done()));
          }),
      });
    });
  });
}
