import assert from 'node:assert/strict';
import { after, before, test } from 'node:test';
import type { RoomSnapshot } from '../../shared/protocol';
import { SessionClient, normalizeServerAddress } from '../../app/src/net/sessionClient';
import { startServer, type RunningServer } from '../src/server';

// These run the app's real SessionClient (the class the phone uses) against the real server.

let server: RunningServer;
before(async () => {
  server = await startServer({ port: 0, host: '127.0.0.1' });
});
after(() => server.close());

function open(first: { t: 'create'; name: string } | { t: 'join'; code: string; name: string }) {
  const rooms: RoomSnapshot[] = [];
  const errors: string[] = [];
  const closes: Array<string | null> = [];
  let joinedAs = '';
  const client: SessionClient = new SessionClient(`ws://127.0.0.1:${server.port}`, {
    onOpen: () => client.send(first),
    onJoined: (you, room) => {
      joinedAs = you;
      rooms.push(room);
    },
    onRoom: (room) => rooms.push(room),
    onError: (_code, message) => errors.push(message),
    onClose: (reason) => closes.push(reason),
  });
  client.connect();
  return { client, rooms, errors, closes, you: () => joinedAs };
}

const until = async (cond: () => boolean, ms = 3000) => {
  const start = Date.now();
  while (!cond()) {
    if (Date.now() - start > ms) throw new Error('condition not met in time');
    await new Promise((r) => setTimeout(r, 20));
  }
};

test('normalizeServerAddress accepts what people actually type', () => {
  assert.equal(normalizeServerAddress('192.168.1.20:8080'), 'ws://192.168.1.20:8080');
  assert.equal(normalizeServerAddress('  jam.example.com  '), 'wss://jam.example.com');
  assert.equal(normalizeServerAddress('wss://jam.example.com/'), 'wss://jam.example.com');
  assert.equal(normalizeServerAddress('https://jam.example.com'), 'wss://jam.example.com');
  assert.equal(normalizeServerAddress('http://10.0.0.5:8080'), 'ws://10.0.0.5:8080');
  assert.equal(normalizeServerAddress(''), null);
  assert.equal(normalizeServerAddress('not a url!'), null);
});

test('host creates, a second phone joins, and the clock converges', async () => {
  const host = open({ t: 'create', name: 'Alex' });
  await until(() => host.rooms.length > 0);
  const code = host.rooms[0]!.code;

  const guest = open({ t: 'join', code, name: 'Sam' });
  await until(() => guest.rooms.length > 0);
  await until(() => (host.rooms.at(-1)?.members.length ?? 0) === 2);
  assert.equal(host.rooms.at(-1)!.hostId, host.you());

  // The initial burst of pings should give a usable estimate; same machine, so offset ~ 0.
  await until(() => host.client.clock.ready && guest.client.clock.ready, 5000);
  assert.ok(Math.abs(host.client.clock.offsetMs) < 15, `offset ${host.client.clock.offsetMs}`);
  assert.ok((host.client.clock.bestRttMs ?? 999) < 50);

  guest.client.send({ t: 'play' });
  await until(() => guest.errors.length > 0);
  assert.match(guest.errors[0]!, /host/i);

  host.client.close();
  guest.client.close();
  assert.deepEqual(host.closes, []); // closing on purpose is not reported as a failure
});

test('joining a code that does not exist reports an error', async () => {
  const c = open({ t: 'join', code: 'ZZZZ', name: 'Sam' });
  await until(() => c.errors.length > 0);
  assert.match(c.errors[0]!, /No session/);
  c.client.close();
});

test('an unreachable server is reported, not left hanging', async () => {
  const closes: Array<string | null> = [];
  const c = new SessionClient('ws://127.0.0.1:1', {
    onOpen: () => {},
    onJoined: () => {},
    onRoom: () => {},
    onError: () => {},
    onClose: (reason) => closes.push(reason),
  });
  c.connect();
  await until(() => closes.length > 0, 10_000);
  assert.ok(closes[0], 'a reason is given');
});
