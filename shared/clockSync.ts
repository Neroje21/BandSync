// Cristian-style clock offset estimation against the session server.
//
// A client sends ping{c0 = local send time}; the server answers with its own
// clock reading `s`; the client notes the local receive time c1. Assuming the
// reply travelled for half the round trip, the server clock read `s` at local
// time c1 - rtt/2, so   offset = s + rtt/2 - c1   and   serverTime = local + offset.
//
// Samples with the smallest round trip are the most trustworthy (queueing delay
// is what makes the one-way assumption wrong), so we keep a sliding window and
// average the best few.

export interface SyncSample {
  rttMs: number;
  offsetMs: number;
}

export interface SyncEstimate {
  offsetMs: number;
  /** Smallest round trip seen in the window; half of it bounds the worst-case error. */
  bestRttMs: number;
}

const WINDOW = 20;
const BEST_N = 3;

export function sampleFromPong(c0: number, s: number, c1: number): SyncSample | null {
  const rttMs = c1 - c0;
  if (!Number.isFinite(rttMs) || rttMs < 0 || rttMs > 5000) return null;
  return { rttMs, offsetMs: s + rttMs / 2 - c1 };
}

export function estimate(samples: SyncSample[]): SyncEstimate | null {
  if (samples.length === 0) return null;
  const best = [...samples].sort((a, b) => a.rttMs - b.rttMs).slice(0, BEST_N);
  const offsets = best.map((x) => x.offsetMs).sort((a, b) => a - b);
  const median = offsets[Math.floor(offsets.length / 2)] as number;
  return { offsetMs: median, bestRttMs: (best[0] as SyncSample).rttMs };
}

export class ClockSync {
  private samples: SyncSample[] = [];
  private est: SyncEstimate | null = null;

  constructor(private readonly now: () => number = Date.now) {}

  nextPing(id: number): { t: 'ping'; id: number; c0: number } {
    return { t: 'ping', id, c0: this.now() };
  }

  /** Feed a pong; `rxLocal` is the local time the reply was received (read it as early as possible). */
  onPong(msg: { c0: number; s: number }, rxLocal: number = this.now()): void {
    const sample = sampleFromPong(msg.c0, msg.s, rxLocal);
    if (!sample) return;
    this.samples.push(sample);
    if (this.samples.length > WINDOW) this.samples.shift();
    this.est = estimate(this.samples);
  }

  get ready(): boolean {
    return this.est !== null && this.samples.length >= 3;
  }
  get offsetMs(): number {
    return this.est?.offsetMs ?? 0;
  }
  get bestRttMs(): number | null {
    return this.est?.bestRttMs ?? null;
  }
  serverNow(): number {
    return this.now() + this.offsetMs;
  }
  toLocal(serverTime: number): number {
    return serverTime - this.offsetMs;
  }
}
