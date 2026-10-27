// Songsterr URL rules shared by the server and the app.
//
// The app only ever loads songsterr.com pages in its WebView, and the server
// only accepts songsterr.com tab links, so a host cannot point everyone's
// phone at an arbitrary site. Regex instead of `new URL()` because React
// Native's URL implementation is incomplete.

const SONGSTERR_URL = /^https:\/\/(?:www\.)?songsterr\.com(\/[^?#\s]*)?(?:[?#].*)?$/i;

// Song pages end in `-s<songId>` with an optional `t<trackId>`, e.g.
// /a/wsa/metallica-enter-sandman-tab-s19 or ...-tab-s19t3.
const SONG_PATH = /-s(\d+)(?:t\d+)?\/?$/i;

export function isSongsterrUrl(raw: string): boolean {
  return SONGSTERR_URL.test(raw.trim());
}

/** https://www.songsterr.com/<path> with query and hash removed, or null. */
export function normalizeTabUrl(raw: string): string | null {
  const m = SONGSTERR_URL.exec(raw.trim());
  if (!m) return null;
  return `https://www.songsterr.com${m[1] ?? '/'}`;
}

/**
 * Identifies the song regardless of which track (guitar, bass, drums...) or
 * display mode (tab, sheet) a player has open. Null when the URL is not a
 * Songsterr song page.
 */
export function songKey(raw: string | null | undefined): string | null {
  if (!raw) return null;
  const url = normalizeTabUrl(raw);
  if (!url) return null;
  const m = SONG_PATH.exec(url);
  return m ? `s${m[1]}` : null;
}
