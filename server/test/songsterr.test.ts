import assert from 'node:assert/strict';
import { test } from 'node:test';
import { isSongsterrUrl, normalizeTabUrl, songKey } from '../../shared/songsterr';

test('normalizeTabUrl strips query/hash and unifies the host', () => {
  assert.equal(
    normalizeTabUrl('https://songsterr.com/a/wsa/metallica-enter-sandman-tab-s19t3?inst=1#x'),
    'https://www.songsterr.com/a/wsa/metallica-enter-sandman-tab-s19t3',
  );
  assert.equal(normalizeTabUrl('  https://www.songsterr.com  '), 'https://www.songsterr.com/');
});

test('only https songsterr.com pages are accepted', () => {
  const bad = [
    'http://www.songsterr.com/a/wsa/x-tab-s1',
    'https://evil.com/a/wsa/x-tab-s1',
    'https://www.songsterr.com.evil.com/a/wsa/x-tab-s1',
    'https://www.songsterr.com@evil.com/a/wsa/x-tab-s1',
    'https://evil.com/?https://www.songsterr.com/',
    'https://www.songsterr.com:8443/a/wsa/x-tab-s1',
    'javascript:alert(1)',
    '',
  ];
  for (const url of bad) {
    assert.equal(isSongsterrUrl(url), false, url);
    assert.equal(normalizeTabUrl(url), null, url);
  }
});

test('songKey ignores track, display mode and query so different instruments match', () => {
  const guitar = songKey('https://www.songsterr.com/a/wsa/metallica-enter-sandman-tab-s19');
  assert.equal(guitar, 's19');
  assert.equal(songKey('https://www.songsterr.com/a/wsa/metallica-enter-sandman-tab-s19t3?inst=2'), 's19');
  assert.equal(songKey('https://www.songsterr.com/a/wsa/metallica-enter-sandman-bass-tab-s19'), 's19');
  assert.equal(songKey('https://www.songsterr.com/a/wsa/metallica-enter-sandman-drum-tab-s19'), 's19');
  assert.notEqual(songKey('https://www.songsterr.com/a/wsa/metallica-one-tab-s20'), guitar);
});

test('songKey is null for pages that are not a song', () => {
  assert.equal(songKey('https://www.songsterr.com/'), null);
  assert.equal(songKey('https://www.songsterr.com/a/wa/bestMatch?pattern=metallica'), null);
  assert.equal(songKey(null), null);
  assert.equal(songKey('https://evil.com/a/wsa/x-tab-s19'), null);
});
