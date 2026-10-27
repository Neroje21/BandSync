import { normalizeRoomCode, ROOM_CODE_LEN } from '../../shared/protocol';

// An invite carries the session code and the server address, so a friend with the installed app
// does not have to type either. It travels as a link (songsterrjam://join?...) or as the plain
// text message the Share button produces; pasting that message into the join box works too,
// because chat apps do not always make custom links tappable.

export const APP_SCHEME = 'songsterrjam';

export interface Invite {
  code: string | null;
  server: string | null;
}

const LINK = new RegExp(`${APP_SCHEME}://join\\?([^\\s]+)`, 'i');
const SERVER = /\bwss?:\/\/[^\s"'<>]+/i;
const LABELLED_CODE = /\bcode\s*[:=]\s*([A-Za-z0-9]{4})\b/i;

export function buildInviteLink(code: string, server: string): string {
  return `${APP_SCHEME}://join?code=${encodeURIComponent(code)}&server=${encodeURIComponent(server)}`;
}

export function buildInviteMessage(code: string, server: string): string {
  return [
    'Join my Songsterr Jam session!',
    `Code: ${code}`,
    `Server: ${server}`,
    '',
    `Tap to open in the app: ${buildInviteLink(code, server)}`,
    'Link not tappable? Open the app and paste this whole message into the join box.',
  ].join('\n');
}

function queryParam(query: string, key: string): string | null {
  for (const part of query.split('&')) {
    const [k, v = ''] = part.split('=');
    if (k !== key) continue;
    try {
      return decodeURIComponent(v.replace(/\+/g, ' '));
    } catch {
      return null;
    }
  }
  return null;
}

const asCode = (raw: string | null): string | null => {
  if (!raw) return null;
  const code = normalizeRoomCode(raw);
  return code.length === ROOM_CODE_LEN ? code : null;
};

const asServer = (raw: string | null): string | null => {
  if (!raw) return null;
  const m = SERVER.exec(raw);
  return m ? m[0].replace(/[.,;:)\]]+$/, '') : null;
};

/** Pulls a session code and/or server address out of a link, an invite message, or a bare code. */
export function parseInvite(text: string): Invite {
  let code: string | null = null;
  let server: string | null = null;

  const link = LINK.exec(text);
  if (link?.[1]) {
    code = asCode(queryParam(link[1], 'code'));
    server = asServer(queryParam(link[1], 'server'));
  }
  if (!server) server = asServer(text);
  if (!code) code = asCode(LABELLED_CODE.exec(text)?.[1] ?? null);
  if (!code && text.trim().length === ROOM_CODE_LEN) code = asCode(text.trim());

  return { code, server };
}
