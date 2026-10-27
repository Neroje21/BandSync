// Wire protocol between the mobile app and the session server.
//
// The server is the single source of truth. Clients send intents (play, stop,
// setTab, status) and render whatever room snapshot comes back. A synchronized
// start is just `room.run.startAt`: an absolute time on the SERVER's clock that
// every client converts to its own clock using the offset from clockSync.ts.

export const MAX_MEMBERS = 12;
export const MAX_NAME_LEN = 24;
export const ROOM_CODE_LEN = 4;
// No I, L, O, 0 or 1 so codes can be read out loud.
export const ROOM_CODE_ALPHABET = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';

export const MIN_LEAD_MS = 3000;
export const MAX_LEAD_MS = 10000;

/** How far in the future the server schedules a start, given the worst round trip in the room. */
export function chooseLeadMs(maxRttMs: number): number {
  const lead = Math.round(2 * maxRttMs + 2500);
  return Math.min(MAX_LEAD_MS, Math.max(MIN_LEAD_MS, lead));
}

export function normalizeRoomCode(input: string): string {
  return input
    .toUpperCase()
    .split('')
    .filter((c) => ROOM_CODE_ALPHABET.includes(c))
    .join('')
    .slice(0, ROOM_CODE_LEN);
}

/**
 * What a member's Songsterr player currently shows. Strings are the player's
 * own labels, treated as opaque: two players match when the strings are equal,
 * so the check keeps working if Songsterr rewords them.
 */
export interface PlayerState {
  speed: string | null; // e.g. "Speed 100%"
  source: string | null; // original-audio vs synth toggle label
  countIn: boolean | null;
  revision: string | null; // e.g. "Revision from: 25. 10. 2026"
}

export interface MemberStatus {
  url: string | null; // page the member's WebView is on
  playerReady: boolean; // Songsterr player controls found on the page
  audioUnlocked: boolean; // member has tapped the page, so audio may start
  playing: boolean;
  player: PlayerState | null;
  rttMs: number | null; // member's best measured round trip to the server
}

export const EMPTY_STATUS: MemberStatus = {
  url: null,
  playerReady: false,
  audioUnlocked: false,
  playing: false,
  player: null,
  rttMs: null,
};

/** Why a member is not ready, compared with the host's setup. */
export type Issue = 'notLoaded' | 'audioLocked' | 'song' | 'speed' | 'source' | 'countIn' | 'revision';

export interface Member {
  id: string;
  name: string;
  isHost: boolean;
  status: MemberStatus;
}

/** One synchronized start. `startAt` is in server time (ms since epoch). */
export interface Run {
  runId: number;
  startAt: number;
  leadMs: number;
}

export interface RoomSnapshot {
  code: string;
  tabUrl: string | null;
  hostId: string;
  members: Member[];
  issues: Record<string, Issue[]>; // by member id; empty array = ready
  run: Run | null;
}

export type ErrorCode =
  | 'bad_message'
  | 'not_found'
  | 'room_full'
  | 'not_host'
  | 'not_joined'
  | 'already_joined'
  | 'bad_url'
  | 'no_tab'
  | 'not_ready'
  | 'server_busy';

export type ClientMsg =
  | { t: 'ping'; id: number; c0: number }
  | { t: 'create'; name: string }
  | { t: 'join'; code: string; name: string }
  | { t: 'setTab'; url: string }
  | { t: 'status'; status: MemberStatus }
  | { t: 'play'; force?: boolean }
  | { t: 'stop' }
  | { t: 'leave' };

export type ServerMsg =
  | { t: 'pong'; id: number; c0: number; s: number }
  | { t: 'joined'; you: string; room: RoomSnapshot }
  | { t: 'room'; room: RoomSnapshot }
  | { t: 'error'; code: ErrorCode; message: string };
