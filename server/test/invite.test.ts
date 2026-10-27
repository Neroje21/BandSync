import assert from 'node:assert/strict';
import { test } from 'node:test';
import { buildInviteLink, buildInviteMessage, parseInvite } from '../../app/src/invite';
import { normalizeServerAddress } from '../../app/src/net/sessionClient';

const SERVER = 'wss://margaret-roommates-rat-fixes.trycloudflare.com';

test('the invite message round-trips: pasting what Share produced gives back code and server', () => {
  const parsed = parseInvite(buildInviteMessage('7KQ2', SERVER));
  assert.deepEqual(parsed, { code: '7KQ2', server: SERVER });
});

test('the link alone works (what tapping it hands to the app)', () => {
  const link = buildInviteLink('7KQ2', SERVER);
  assert.match(link, /^songsterrjam:\/\/join\?code=7KQ2&server=wss%3A%2F%2F/);
  assert.deepEqual(parseInvite(link), { code: '7KQ2', server: SERVER });
});

test('a message mangled by a chat app (link dropped, text reflowed) still parses', () => {
  const text = `hey!!\nJoin my Songsterr Jam session!\nCode: 7kq2\nServer: ${SERVER}.\nsee you there`;
  assert.deepEqual(parseInvite(text), { code: '7KQ2', server: SERVER });
});

test('a bare 4-letter code is a code', () => {
  assert.deepEqual(parseInvite(' abcd '), { code: 'ABCD', server: null });
});

test('ordinary text is not mistaken for a code', () => {
  assert.deepEqual(parseInvite('the code here is great'), { code: null, server: null });
  assert.deepEqual(parseInvite('hello'), { code: null, server: null });
  // letters outside the code alphabet (I, L, O, 0, 1) are never valid
  assert.deepEqual(parseInvite('Code: 1OIL'), { code: null, server: null });
});

test('server addresses: domains default to TLS, local addresses to plain ws', () => {
  assert.equal(normalizeServerAddress('jam.example.com'), 'wss://jam.example.com');
  assert.equal(normalizeServerAddress('x-y-z.trycloudflare.com'), 'wss://x-y-z.trycloudflare.com');
  assert.equal(normalizeServerAddress('192.168.1.20:8080'), 'ws://192.168.1.20:8080');
  assert.equal(normalizeServerAddress('localhost:8080'), 'ws://localhost:8080');
  assert.equal(normalizeServerAddress('mypc.local:8080'), 'ws://mypc.local:8080');
  assert.equal(normalizeServerAddress('wss://jam.example.com/'), 'wss://jam.example.com');
});
