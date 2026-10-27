// Everything that touches Songsterr's web player lives in this file.
//
// There is no public Songsterr API, so the app shows songsterr.com in a WebView and
// drives the page's own controls. If Songsterr changes its markup, the selectors in
// BRIDGE_JS are the only thing to update. They use the player's stable element ids
// (not the hashed CSS class names) and read state from aria attributes.
//
// Verified against the live player: #control-play toggles playback and exposes
// aria-pressed; a Backspace key event on the document seeks to bar 1;
// #control-speed / #control-source carry aria-labels; #control-countin has aria-pressed.

import type { MemberStatus } from '../../../shared/protocol';

export type BridgeState = Omit<MemberStatus, 'rttMs'>;

export type BridgeEvent =
  | { type: 'state'; state: BridgeState }
  | { type: 'armed'; runId: number; ok: boolean; reason: string | null }
  | { type: 'fired'; runId: number; lateMs: number; playing: boolean };

export type FixableField = 'source' | 'countIn';

/** Runs inside the WebView before the page loads. Plain ES5 on purpose. */
export const BRIDGE_JS = `
(function () {
  if (window.__jam) { return; }
  var SEL = {
    play: '#control-play',
    speed: '#control-speed',
    source: '#control-source',
    countIn: '#control-countin',
    revision: '#control-revision'
  };
  var unlocked = false;
  var last = '';
  var armSeq = 0;
  var armTimer = null;

  function post(msg) {
    try { window.ReactNativeWebView.postMessage(JSON.stringify(msg)); } catch (e) {}
  }
  function q(sel) { return document.querySelector(sel); }
  function pressed(el) { return !!el && el.getAttribute('aria-pressed') === 'true'; }
  function label(el, attr) { return el ? el.getAttribute(attr) : null; }

  function read() {
    var play = q(SEL.play);
    return {
      url: location.href,
      playerReady: !!play,
      audioUnlocked: unlocked,
      playing: pressed(play),
      player: play ? {
        speed: label(q(SEL.speed), 'aria-label'),
        source: label(q(SEL.source), 'aria-label'),
        countIn: q(SEL.countIn) ? pressed(q(SEL.countIn)) : null,
        revision: label(q(SEL.revision), 'title')
      } : null
    };
  }

  function report(force) {
    var s = JSON.stringify(read());
    if (force || s !== last) {
      last = s;
      post({ type: 'state', state: JSON.parse(s) });
    }
  }

  // Browsers only let a page start audio after a real touch. Scripted events don't count.
  function onGesture(e) {
    if (e && e.isTrusted === false) { return; }
    if (!unlocked) { unlocked = true; report(); }
  }
  ['pointerdown', 'touchstart', 'mousedown', 'keydown'].forEach(function (name) {
    window.addEventListener(name, onGesture, true);
  });

  function pressKey(key, code, keyCode) {
    ['keydown', 'keyup'].forEach(function (type) {
      document.dispatchEvent(new KeyboardEvent(type, {
        key: key, code: code, keyCode: keyCode, which: keyCode, bubbles: true, cancelable: true
      }));
    });
  }

  function cancel() {
    armSeq++;
    if (armTimer) { clearTimeout(armTimer); armTimer = null; }
  }
  function pause() {
    var p = q(SEL.play);
    if (pressed(p)) { p.click(); }
  }

  // Park the player paused at bar 1, then press play exactly when Date.now() reaches fireAt.
  // The wait runs inside the page so the React Native bridge's delay does not add jitter.
  function arm(runId, fireAt) {
    cancel();
    var seq = armSeq;
    if (!q(SEL.play)) {
      post({ type: 'armed', runId: runId, ok: false, reason: 'no-player' });
      return;
    }
    pause();
    pressKey('Backspace', 'Backspace', 8);
    post({ type: 'armed', runId: runId, ok: true, reason: null });

    function tick() {
      if (seq !== armSeq) { return; }
      var left = fireAt - Date.now();
      if (left > 40) {
        armTimer = setTimeout(tick, Math.min(left - 30, 500));
        return;
      }
      while (fireAt - Date.now() > 0) { /* spin the last few ms */ }
      var late = Date.now() - fireAt;
      var p = q(SEL.play);
      if (p && !pressed(p)) { p.click(); }
      setTimeout(function () {
        if (seq !== armSeq) { return; }
        post({ type: 'fired', runId: runId, lateMs: late, playing: pressed(q(SEL.play)) });
        report();
      }, 250);
    }
    tick();
  }

  function stop() {
    cancel();
    pause();
    setTimeout(report, 150);
  }

  function fix(field) {
    var el = field === 'source' ? q(SEL.source) : field === 'countIn' ? q(SEL.countIn) : null;
    if (el) { el.click(); }
    setTimeout(report, 250);
  }

  window.__jam = { arm: arm, stop: stop, fix: fix };
  setInterval(report, 250);
  report(true);
})();
true;
`;

const call = (expr: string): string => `window.__jam && ${expr}; true;`;

/** `fireAtLocal` is device clock time in ms since epoch. */
export const cmdArm = (runId: number, fireAtLocal: number): string =>
  call(`window.__jam.arm(${Math.trunc(runId)}, ${Math.round(fireAtLocal)})`);
export const cmdStop = (): string => call('window.__jam.stop()');
export const cmdFix = (field: FixableField): string => call(`window.__jam.fix(${JSON.stringify(field)})`);
export const cmdNavigate = (url: string): string => `location.href = ${JSON.stringify(url)}; true;`;

// Anything on the Songsterr page (ads, trackers) can call postMessage, so treat it as untrusted input.
const str = (v: unknown, max = 300): string | null => (typeof v === 'string' ? v.slice(0, max) : null);

export function parseBridgeEvent(data: string): BridgeEvent | null {
  let m: unknown;
  try {
    m = JSON.parse(data);
  } catch {
    return null;
  }
  if (typeof m !== 'object' || m === null) return null;
  const o = m as Record<string, unknown>;
  if (o.type === 'state' && typeof o.state === 'object' && o.state !== null) {
    const s = o.state as Record<string, unknown>;
    const p = typeof s.player === 'object' && s.player !== null ? (s.player as Record<string, unknown>) : null;
    return {
      type: 'state',
      state: {
        url: str(s.url),
        playerReady: s.playerReady === true,
        audioUnlocked: s.audioUnlocked === true,
        playing: s.playing === true,
        player: p
          ? {
              speed: str(p.speed, 200),
              source: str(p.source, 200),
              countIn: typeof p.countIn === 'boolean' ? p.countIn : null,
              revision: str(p.revision, 200),
            }
          : null,
      },
    };
  }
  if (o.type === 'armed' && typeof o.runId === 'number') {
    return { type: 'armed', runId: o.runId, ok: o.ok === true, reason: str(o.reason, 60) };
  }
  if (o.type === 'fired' && typeof o.runId === 'number' && typeof o.lateMs === 'number') {
    return { type: 'fired', runId: o.runId, lateMs: o.lateMs, playing: o.playing === true };
  }
  return null;
}
