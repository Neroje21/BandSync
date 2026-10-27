import { useCallback, useEffect, useRef, useState } from 'react';
import type { ClientMsg, RoomSnapshot } from '../../../shared/protocol';
import type { ClockSync } from '../../../shared/clockSync';
import { SessionClient, normalizeServerAddress } from './sessionClient';

const TOAST_MS = 4500;

export interface Session {
  you: string | null;
  room: RoomSnapshot | null;
  /** The ws(s):// address in use, so it can go into invites. */
  serverUrl: string | null;
  /** Last problem to show the user (connection trouble, "only the host can do that", ...). */
  error: string | null;
  busy: boolean;
  clock: ClockSync | null;
  host(serverAddress: string, name: string): void;
  join(serverAddress: string, code: string, name: string): void;
  send(msg: ClientMsg): void;
  leave(): void;
  clearError(): void;
}

export function useSession(): Session {
  const [you, setYou] = useState<string | null>(null);
  const [room, setRoom] = useState<RoomSnapshot | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [serverUrl, setServerUrl] = useState<string | null>(null);
  const client = useRef<SessionClient | null>(null);
  const toast = useRef<ReturnType<typeof setTimeout> | null>(null);

  const showError = useCallback((message: string) => {
    setError(message);
    if (toast.current) clearTimeout(toast.current);
    toast.current = setTimeout(() => setError(null), TOAST_MS);
  }, []);

  const start = useCallback(
    (address: string, first: ClientMsg) => {
      const url = normalizeServerAddress(address);
      if (!url) return showError('Enter the server address, e.g. 192.168.1.20:8080');
      client.current?.close();
      setError(null);
      setBusy(true);
      setServerUrl(url);

      let joined = false;
      const c: SessionClient = new SessionClient(url, {
        onOpen: () => c.send(first),
        onJoined: (id, snapshot) => {
          joined = true;
          setYou(id);
          setRoom(snapshot);
          setBusy(false);
        },
        onRoom: setRoom,
        onError: (_code, message) => {
          showError(message);
          if (!joined) {
            setBusy(false);
            c.close();
            if (client.current === c) client.current = null;
          }
        },
        onClose: (reason) => {
          if (client.current === c) client.current = null;
          setBusy(false);
          setYou(null);
          setRoom(null);
          if (reason) showError(reason);
        },
      });
      client.current = c;
      c.connect();
    },
    [showError],
  );

  const host = useCallback((address: string, name: string) => start(address, { t: 'create', name }), [start]);
  const join = useCallback(
    (address: string, code: string, name: string) => start(address, { t: 'join', code, name }),
    [start],
  );
  const send = useCallback((msg: ClientMsg) => client.current?.send(msg), []);
  const leave = useCallback(() => {
    client.current?.send({ t: 'leave' });
    client.current?.close();
    client.current = null;
    setYou(null);
    setRoom(null);
    setBusy(false);
  }, []);
  const clearError = useCallback(() => setError(null), []);

  useEffect(
    () => () => {
      if (toast.current) clearTimeout(toast.current);
      client.current?.close();
    },
    [],
  );

  return {
    you,
    room,
    serverUrl,
    error,
    busy,
    clock: client.current?.clock ?? null,
    host,
    join,
    send,
    leave,
    clearError,
  };
}
