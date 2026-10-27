import {
  EMPTY_STATUS,
  MAX_MEMBERS,
  chooseLeadMs,
  type ErrorCode,
  type Issue,
  type Member,
  type MemberStatus,
  type RoomSnapshot,
  type Run,
  type ServerMsg,
} from '../../shared/protocol';
import { normalizeTabUrl, songKey } from '../../shared/songsterr';

export type Send = (msg: ServerMsg) => void;
export interface RoomError {
  code: ErrorCode;
  message: string;
}

interface MemberRec {
  id: string;
  name: string;
  joinedAt: number;
  send: Send;
  status: MemberStatus;
}

export class Room {
  hostId = '';
  tabUrl: string | null = null;
  run: Run | null = null;
  private runCounter = 0;
  private members = new Map<string, MemberRec>();

  constructor(
    readonly code: string,
    private readonly now: () => number,
  ) {}

  get size(): number {
    return this.members.size;
  }

  has(id: string): boolean {
    return this.members.has(id);
  }

  join(id: string, name: string, send: Send): RoomError | null {
    if (this.members.size >= MAX_MEMBERS) {
      return { code: 'room_full', message: `This session is full (${MAX_MEMBERS} players).` };
    }
    this.members.set(id, { id, name, joinedAt: this.now(), send, status: { ...EMPTY_STATUS } });
    if (!this.hostId) this.hostId = id;
    return null;
  }

  leave(id: string): void {
    if (!this.members.delete(id)) return;
    if (id === this.hostId) {
      const next = [...this.members.values()].sort((a, b) => a.joinedAt - b.joinedAt)[0];
      this.hostId = next?.id ?? '';
    }
    if (this.members.size === 0) this.run = null;
  }

  updateStatus(id: string, status: MemberStatus): void {
    const m = this.members.get(id);
    if (m) m.status = status;
  }

  setTab(id: string, rawUrl: string): RoomError | null {
    const denied = this.requireHost(id);
    if (denied) return denied;
    const url = normalizeTabUrl(rawUrl);
    if (!url || !songKey(url)) {
      return { code: 'bad_url', message: 'That is not a Songsterr tab page.' };
    }
    this.tabUrl = url;
    this.run = null; // a new song invalidates any scheduled start
    return null;
  }

  play(id: string, force: boolean): RoomError | null {
    const denied = this.requireHost(id);
    if (denied) return denied;
    if (!this.tabUrl) return { code: 'no_tab', message: 'Share a tab with the session first.' };

    const issues = this.computeIssues();
    const blocked = [...this.members.values()].filter((m) => (issues[m.id] ?? []).length > 0);
    if (blocked.length > 0 && !force) {
      const names = blocked.map((m) => m.name).join(', ');
      return { code: 'not_ready', message: `Not everyone is ready: ${names}.` };
    }

    const rtts = [...this.members.values()].map((m) => m.status.rttMs ?? 0);
    const leadMs = chooseLeadMs(Math.max(0, ...rtts));
    this.run = { runId: ++this.runCounter, startAt: this.now() + leadMs, leadMs };
    return null;
  }

  stop(id: string): RoomError | null {
    const denied = this.requireHost(id);
    if (denied) return denied;
    this.run = null;
    return null;
  }

  /**
   * Compares every member with the host's setup. The host is the reference for
   * speed, audio source, count-in and revision; the room's shared tab is the
   * reference for the song. Empty list = ready.
   */
  computeIssues(): Record<string, Issue[]> {
    const host = this.members.get(this.hostId);
    const out: Record<string, Issue[]> = {};
    for (const m of this.members.values()) {
      const s = m.status;
      const issues: Issue[] = [];
      if (!s.playerReady) issues.push('notLoaded');
      if (!s.audioUnlocked) issues.push('audioLocked');
      if (this.tabUrl && songKey(s.url) !== songKey(this.tabUrl)) issues.push('song');
      if (s.playerReady && s.player && host && host !== m && host.status.player) {
        const ref = host.status.player;
        if (s.player.speed !== ref.speed) issues.push('speed');
        if (s.player.source !== ref.source) issues.push('source');
        if (s.player.countIn !== ref.countIn) issues.push('countIn');
        if (s.player.revision !== ref.revision) issues.push('revision');
      }
      out[m.id] = issues;
    }
    return out;
  }

  snapshot(): RoomSnapshot {
    const members: Member[] = [...this.members.values()]
      .sort((a, b) => a.joinedAt - b.joinedAt)
      .map((m) => ({ id: m.id, name: m.name, isHost: m.id === this.hostId, status: m.status }));
    return {
      code: this.code,
      tabUrl: this.tabUrl,
      hostId: this.hostId,
      members,
      issues: this.computeIssues(),
      run: this.run,
    };
  }

  broadcast(): void {
    const msg: ServerMsg = { t: 'room', room: this.snapshot() };
    for (const m of this.members.values()) m.send(msg);
  }

  private requireHost(id: string): RoomError | null {
    return id === this.hostId ? null : { code: 'not_host', message: 'Only the host can do that.' };
  }
}
