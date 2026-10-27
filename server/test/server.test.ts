import assert from 'node:assert/strict';
import { after, before, describe, test } from 'node:test';
import { MAX_MEMBERS } from '../../shared/protocol';
import { startServer, type RunningServer } from '../src/server';
import { TAB, TestClient, readyStatus } from './helpers';

let server: RunningServer;
const clients: TestClient[] = [];

async function connect(opts = {}): Promise<TestClient> {
  const c = await TestClient.connect(server.port, opts);
  clients.push(c);
  return c;
}

before(async () => {
  server = await startServer({ port: 0, host: '127.0.0.1' });
});

after(async () => {
  for (const c of clients) c.close();
  await server.close();
});

/** A host and one guest in a room, both with a ready player on TAB. */
async function readyRoom() {
  const host = await connect();
  const guest = await connect();
  const room = await host.create('Host');
  await guest.join(room.code, 'Guest');
  host.send({ t: 'setTab', url: TAB });
  host.send({ t: 'status', status: readyStatus(TAB) });
  guest.send({ t: 'status', status: readyStatus(TAB) });
  const allReady = (r: { tabUrl: string | null; members: { status: { playerReady: boolean } }[] }) =>
    r.tabUrl === TAB && r.members.every((m) => m.status.playerReady);
  await Promise.all([host.waitRoom(allReady), guest.waitRoom(allReady)]);
  // Older snapshots (guest joined, nobody ready yet) are still queued; tests must only see new ones.
  host.drain();
  guest.drain();
  return { host, guest, code: room.code };
}

describe('joining', () => {
  test('create returns a readable 4-character code and makes the creator host', async () => {
    const host = await connect();
    const room = await host.create('Alex');
    assert.match(room.code, /^[A-HJ-KM-NP-Z2-9]{4}$/);
    assert.equal(room.hostId, host.id);
    assert.equal(room.members[0]?.name, 'Alex');
    assert.equal(room.members[0]?.isHost, true);
  });

  test('join accepts a sloppy code and notifies everyone', async () => {
    const host = await connect();
    const guest = await connect();
    const { code } = await host.create('Alex');
    const room = await guest.join(` ${code.toLowerCase()} `, 'Sam');
    assert.equal(room.members.length, 2);
    const seen = await host.waitRoom((r) => r.members.length === 2);
    assert.deepEqual(seen.members.map((m) => m.name), ['Alex', 'Sam']);
  });

  test('unknown code is rejected', async () => {
    const c = await connect();
    await assert.rejects(c.join('ZZZZ', 'Sam'), /not_found/);
  });

  test('a session holds at most MAX_MEMBERS players', async () => {
    const host = await connect();
    const { code } = await host.create('Host');
    for (let i = 1; i < MAX_MEMBERS; i++) await (await connect()).join(code, `P${i}`);
    await assert.rejects((await connect()).join(code, 'Extra'), /room_full/);
  });

  test('names are trimmed, stripped of control characters and capped', async () => {
    const c = await connect();
    const room = await c.create('  \u0007Bob' + 'x'.repeat(100));
    const name = room.members[0]?.name ?? '';
    assert.ok(name.startsWith('Bob'));
    assert.equal(name.length, 24);
  });

  test('host passes to the longest-present member, and empty rooms are removed', async () => {
    const before = server.roomCount();
    const host = await connect();
    const guest = await connect();
    const { code } = await host.create('A');
    await guest.join(code, 'B');
    assert.equal(server.roomCount(), before + 1);
    host.close();
    const room = await guest.waitRoom((r) => r.members.length === 1);
    assert.equal(room.hostId, guest.id);
    guest.close();
    await new Promise((r) => setTimeout(r, 100));
    assert.equal(server.roomCount(), before);
  });
});

describe('host-only actions and tab safety', () => {
  test('guests cannot set the tab, play or stop', async () => {
    const { guest } = await readyRoom();
    for (const msg of [{ t: 'setTab', url: TAB }, { t: 'play' }, { t: 'stop' }] as const) {
      guest.drain();
      guest.send(msg);
      assert.equal((await guest.waitError()).code, 'not_host');
    }
  });

  test('only Songsterr tab pages can be shared, and the link is normalized', async () => {
    const host = await connect();
    await host.create('Host');
    for (const url of ['https://evil.example/a/wsa/x-tab-s1', 'https://www.songsterr.com/', 'javascript:alert(1)']) {
      host.drain();
      host.send({ t: 'setTab', url });
      assert.equal((await host.waitError()).code, 'bad_url', url);
    }
    host.send({ t: 'setTab', url: `${TAB}t2?inst=3#bar` });
    const room = await host.waitRoom((r) => r.tabUrl !== null);
    assert.equal(room.tabUrl, `${TAB}t2`);
  });

  test('malformed messages are answered with an error and the connection survives', async () => {
    const c = await connect();
    c.sendRaw('not json');
    assert.equal((await c.waitError()).code, 'bad_message');
    c.sendRaw(JSON.stringify({ t: 'status', status: 'nope' }));
    assert.equal((await c.waitError()).code, 'bad_message');
    c.send({ t: 'play' });
    assert.equal((await c.waitError()).code, 'not_joined');
  });

  test('oversized payloads close the connection', async () => {
    const c = await connect();
    c.sendRaw('x'.repeat(20_000));
    await new Promise((r) => setTimeout(r, 200));
    assert.equal(c.closed, true);
  });
});

describe('readiness checks', () => {
  test('play needs a shared tab', async () => {
    const host = await connect();
    await host.create('Host');
    host.send({ t: 'play' });
    assert.equal((await host.waitError()).code, 'no_tab');
  });

  test('play is blocked while someone is not ready, and the room says why', async () => {
    const { host, guest } = await readyRoom();
    guest.send({ t: 'status', status: readyStatus(TAB, { audioUnlocked: false }) });
    const room = await host.waitRoom((r) => r.issues[guest.id]?.includes('audioLocked') === true);
    assert.deepEqual(room.issues[host.id], []);
    host.drain();
    host.send({ t: 'play' });
    const err = await host.waitError();
    assert.equal(err.code, 'not_ready');
    assert.match(err.message, /Guest/);
  });

  test('speed, audio source, count-in, revision and song mismatches are all detected', async () => {
    const { host, guest } = await readyRoom();
    const base = readyStatus(TAB);
    const cases: Array<[string, ReturnType<typeof readyStatus>]> = [
      ['speed', { ...base, player: { ...base.player!, speed: 'Speed 75%' } }],
      ['source', { ...base, player: { ...base.player!, source: 'Synth' } }],
      ['countIn', { ...base, player: { ...base.player!, countIn: true } }],
      ['revision', { ...base, player: { ...base.player!, revision: 'Revision B' } }],
      ['song', { ...base, url: 'https://www.songsterr.com/a/wsa/metallica-one-tab-s20' }],
      ['notLoaded', { ...base, playerReady: false, player: null }],
    ];
    for (const [issue, status] of cases) {
      guest.send({ t: 'status', status });
      const room = await host.waitRoom((r) => r.issues[guest.id]?.includes(issue as never) === true);
      assert.ok(room.issues[guest.id]?.includes(issue as never), issue);
    }
    host.drain();
    guest.send({ t: 'status', status: base });
    const room = await host.waitRoom((r) => r.issues[guest.id]?.length === 0);
    assert.deepEqual(room.issues[guest.id], []);
  });

  test('a guest on another track of the same song is fine', async () => {
    const { host, guest } = await readyRoom();
    const bassUrl = `${TAB.replace('-tab-', '-bass-tab-')}t2?inst=1`;
    guest.send({ t: 'status', status: readyStatus(bassUrl) });
    const room = await host.waitRoom((r) => r.members.find((m) => m.id === guest.id)?.status.url === bassUrl);
    assert.deepEqual(room.issues[guest.id], []);
  });

  test('force starts anyway', async () => {
    const { host, guest } = await readyRoom();
    guest.send({ t: 'status', status: readyStatus(TAB, { playerReady: false, player: null }) });
    await host.waitRoom((r) => r.issues[guest.id]?.includes('notLoaded') === true);
    host.send({ t: 'play', force: true });
    const room = await host.waitRoom((r) => r.run !== null);
    assert.ok(room.run);
  });
});

describe('synchronized start', () => {
  test('everyone receives the same future start time; stop and new tab clear it', async () => {
    const { host, guest } = await readyRoom();
    const sentAt = Date.now();
    host.send({ t: 'play' });
    const [a, b] = await Promise.all([host.waitRoom((r) => r.run !== null), guest.waitRoom((r) => r.run !== null)]);
    assert.deepEqual(a.run, b.run);
    assert.ok(a.run!.leadMs >= 3000);
    assert.ok(a.run!.startAt >= sentAt + 3000, 'start is scheduled in the future');

    // A restart gets a new runId so clients re-arm.
    host.drain();
    host.send({ t: 'play' });
    const again = await host.waitRoom((r) => r.run !== null && r.run.runId !== a.run!.runId);
    assert.equal(again.run!.runId, a.run!.runId + 1);

    host.send({ t: 'stop' });
    assert.equal((await guest.waitRoom((r) => r.run === null)).run, null);

    host.send({ t: 'play' });
    await guest.waitRoom((r) => r.run !== null);
    host.send({ t: 'setTab', url: 'https://www.songsterr.com/a/wsa/metallica-one-tab-s20' });
    assert.equal((await guest.waitRoom((r) => r.run === null && r.tabUrl?.endsWith('s20') === true)).run, null);
  });

  test('lead time grows with the slowest connection in the room', async () => {
    const { host, guest } = await readyRoom();
    guest.send({ t: 'status', status: readyStatus(TAB, { rttMs: 2500 }) });
    await host.waitRoom((r) => r.members.some((m) => m.status.rttMs === 2500));
    host.send({ t: 'play' });
    const room = await host.waitRoom((r) => r.run !== null);
    assert.equal(room.run!.leadMs, 7500);
  });

  // The important one. Three simulated phones whose clocks are wildly wrong and whose
  // connections have different (and uneven) delays each work out when to press play using the
  // same ClockSync code the app uses. In real time they must all press within a few ms of each other.
  test('phones with skewed clocks and uneven networks fire within ~25ms of each other', async () => {
    const host = await connect({ skewMs: 4_321, sendDelayMs: 20, recvDelayMs: 20, jitterMs: 15 });
    const near = await connect({ skewMs: -2_750, sendDelayMs: 8, recvDelayMs: 8, jitterMs: 10 });
    const far = await connect({ skewMs: 0, sendDelayMs: 45, recvDelayMs: 25, jitterMs: 15 }); // asymmetric path

    const { code } = await host.create('Host');
    await near.join(code, 'Near');
    await far.join(code, 'Far');
    await Promise.all([host, near, far].map((c) => c.syncClock(14)));

    host.send({ t: 'setTab', url: TAB });
    for (const c of [host, near, far]) c.send({ t: 'status', status: readyStatus(TAB) });
    await far.waitRoom((r) => r.tabUrl === TAB && r.members.every((m) => m.status.playerReady));

    host.send({ t: 'play' });
    const rooms = await Promise.all([host, near, far].map((c) => c.waitRoom((r) => r.run !== null)));

    // When each phone's own clock reads its target, what is the real wall-clock time?
    const fireAtRealTime = [host, near, far].map((c, i) => {
      const targetOnPhoneClock = c.clock.toLocal(rooms[i]!.run!.startAt);
      return targetOnPhoneClock - (c.now() - Date.now());
    });
    const spread = Math.max(...fireAtRealTime) - Math.min(...fireAtRealTime);
    console.log(`      fire-time spread across phones: ${spread.toFixed(1)}ms`);
    assert.ok(spread < 25, `spread was ${spread}ms`);
    // And a phone with an uncorrected clock would have been seconds off:
    assert.ok(Math.abs(host.clock.offsetMs) > 100 || Math.abs(near.clock.offsetMs) > 100);
  });
});
