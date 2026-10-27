import assert from 'node:assert/strict';
import { test } from 'node:test';
import { ClockSync, estimate, sampleFromPong } from '../../shared/clockSync';

test('sampleFromPong recovers the offset when the path is symmetric', () => {
  // local clock is 5000ms behind the server; 60ms round trip, 30ms each way.
  const c0 = 1_000;
  const serverAtArrival = c0 + 5000 + 30;
  const c1 = c0 + 60;
  const sample = sampleFromPong(c0, serverAtArrival, c1);
  assert.ok(sample);
  assert.equal(sample.rttMs, 60);
  assert.equal(sample.offsetMs, 5000);
});

test('sampleFromPong rejects nonsense round trips', () => {
  assert.equal(sampleFromPong(100, 0, 50), null); // reply "before" request
  assert.equal(sampleFromPong(0, 0, 60_000), null); // a minute-long round trip
  assert.equal(sampleFromPong(NaN, 0, 5), null);
});

test('estimate trusts the low-latency samples and ignores slow outliers', () => {
  const trueOffset = -2500;
  const good = [40, 42, 45].map((rttMs) => ({ rttMs, offsetMs: trueOffset + (Math.random() - 0.5) * 2 }));
  // A congested exchange: the reply sat in a queue, so the half-RTT assumption is badly wrong.
  const congested = [{ rttMs: 900, offsetMs: trueOffset + 400 }];
  const est = estimate([...congested, ...good]);
  assert.ok(est);
  assert.ok(Math.abs(est.offsetMs - trueOffset) < 2, `offset ${est.offsetMs}`);
  assert.equal(est.bestRttMs, 40);
});

test('estimate returns null with no samples', () => {
  assert.equal(estimate([]), null);
});

test('ClockSync converts between server and local time', () => {
  let local = 10_000;
  const sync = new ClockSync(() => local);
  assert.equal(sync.ready, false);
  // server clock = local + 3000, 20ms each way
  for (let i = 0; i < 3; i++) {
    const ping = sync.nextPing(i);
    local += 20;
    const s = local + 3000;
    local += 20;
    sync.onPong({ c0: ping.c0, s }, local);
  }
  assert.equal(sync.ready, true);
  assert.equal(sync.offsetMs, 3000);
  assert.equal(sync.toLocal(sync.serverNow()), local);
  assert.equal(sync.bestRttMs, 40);
});
