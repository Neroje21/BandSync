import React, { memo, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Pressable, ScrollView, Share, StyleSheet, Text, View } from 'react-native';
import { useKeepAwake } from 'expo-keep-awake';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { WebView, type WebViewMessageEvent, type WebViewProps } from 'react-native-webview';
import type { ClockSync } from '../../../shared/clockSync';
import type { ClientMsg, Issue, Member, RoomSnapshot } from '../../../shared/protocol';
import { isSongsterrUrl, songKey } from '../../../shared/songsterr';
import {
  BRIDGE_JS,
  cmdArm,
  cmdFix,
  cmdNavigate,
  cmdStop,
  parseBridgeEvent,
  type BridgeState,
  type FixableField,
} from '../bridge/songsterrBridge';
import { buildInviteMessage } from '../invite';
import { colors, radius } from '../theme';

interface Props {
  room: RoomSnapshot;
  you: string;
  clock: ClockSync;
  serverUrl: string | null;
  error: string | null;
  latencyMs: number;
  onLatencyChange(ms: number): void;
  send(msg: ClientMsg): void;
  onLeave(): void;
}

type ArmState =
  | { kind: 'idle' }
  | { kind: 'arming'; runId: number }
  | { kind: 'armed'; runId: number }
  | { kind: 'failed' }
  | { kind: 'missed' };

const SONGSTERR_HOME = 'https://www.songsterr.com/';
const MIN_ARM_LEAD_MS = 800; // arriving later than this before the start leaves no time to park the player

const hostOf = (room: RoomSnapshot): Member | undefined => room.members.find((m) => m.isHost);

interface IssueInfo {
  text: string;
  short: string;
  fix?: FixableField | 'goToSong';
}

function describeIssue(issue: Issue, host: Member | undefined): IssueInfo {
  const hostPlayer = host?.status.player;
  const speed = /(\d+)\s*%/.exec(hostPlayer?.speed ?? '')?.[1];
  switch (issue) {
    case 'notLoaded':
      return { text: 'Open the song so the Songsterr player shows up.', short: 'not loaded' };
    case 'audioLocked':
      return { text: 'Tap the tab once so the phone allows sound.', short: 'tap the tab' };
    case 'song':
      return { text: "You are not on the host's song.", short: 'other song', fix: 'goToSong' };
    case 'speed':
      return { text: `Set the speed to ${speed ? speed + '%' : 'the same as the host'}.`, short: 'speed' };
    case 'source':
      return { text: 'Switch the audio (Orig. / Synth) to match the host.', short: 'audio', fix: 'source' };
    case 'countIn':
      return {
        text: `Turn count-in ${hostPlayer?.countIn ? 'on' : 'off'} to match the host.`,
        short: 'count-in',
        fix: 'countIn',
      };
    case 'revision':
      return { text: "Open the same tab revision as the host.", short: 'revision' };
  }
}

// ---------------------------------------------------------------------------------------------
// The Songsterr page. Memoized with stable props so the many room updates never reload it.
// ---------------------------------------------------------------------------------------------

// react-native-webview 14 declares `class WebView<P = undefined> extends Component<WebViewProps & P>`;
// with P = undefined that props type collapses to `never` under TS 6, so JSX rejects every prop.
// At runtime it is the normal component, so re-type it with the props and methods we actually use.
interface WebViewHandle {
  injectJavaScript(script: string): void;
  reload(): void;
}
const SongsterrWebView = WebView as unknown as React.ComponentType<
  WebViewProps & { ref?: React.Ref<WebViewHandle> }
>;

interface TabViewProps {
  webRef: React.Ref<WebViewHandle>;
  initialUri: string;
  onMessage(e: WebViewMessageEvent): void;
}

const TabView = memo(function TabView({ webRef, initialUri, onMessage }: TabViewProps) {
  const source = useMemo(() => ({ uri: initialUri }), [initialUri]);
  return (
    <SongsterrWebView
      ref={webRef}
      source={source}
      injectedJavaScriptBeforeContentLoaded={BRIDGE_JS}
      onMessage={onMessage}
      originWhitelist={['https://songsterr.com', 'https://*.songsterr.com']}
      // Only Songsterr pages may load as the main page; sub-frames (consent, ads) are left alone.
      onShouldStartLoadWithRequest={(req) =>
        req.url === 'about:blank' || isSongsterrUrl(req.url) || req.isTopFrame === false
      }
      mediaPlaybackRequiresUserAction={false}
      allowsInlineMediaPlayback
      setSupportMultipleWindows={false}
      sharedCookiesEnabled
      thirdPartyCookiesEnabled
      domStorageEnabled
      style={styles.web}
    />
  );
});

function Countdown({ startAt, clock }: { startAt: number; clock: ClockSync }) {
  const [, force] = useState(0);
  useEffect(() => {
    const id = setInterval(() => {
      force((n) => n + 1);
      if (startAt - clock.serverNow() < -1500) clearInterval(id);
    }, 80);
    return () => clearInterval(id);
  }, [startAt, clock]);

  const ms = startAt - clock.serverNow();
  const text = ms > 0 ? String(Math.ceil(ms / 1000)) : ms > -900 ? 'Play!' : null;
  if (!text) return null;
  return (
    <View style={styles.countdown} pointerEvents="none">
      <Text style={styles.countdownText}>{text}</Text>
    </View>
  );
}

function Button({
  label,
  onPress,
  disabled,
  tone = 'accent',
}: {
  label: string;
  onPress(): void;
  disabled?: boolean;
  tone?: 'accent' | 'danger' | 'neutral';
}) {
  return (
    <Pressable
      onPress={onPress}
      disabled={disabled}
      style={[styles.button, tone === 'danger' && styles.buttonDanger, tone === 'neutral' && styles.buttonNeutral, disabled && styles.disabled]}
    >
      <Text style={[styles.buttonText, tone !== 'accent' && { color: colors.text }]}>{label}</Text>
    </Pressable>
  );
}

// ---------------------------------------------------------------------------------------------

export function RoomScreen({ room, you, clock, serverUrl, error, latencyMs, onLatencyChange, send, onLeave }: Props) {
  useKeepAwake();
  const insets = useSafeAreaInsets();
  const web = useRef<WebViewHandle>(null);

  const [bridge, setBridge] = useState<BridgeState | null>(null);
  const [armState, setArmState] = useState<ArmState>({ kind: 'idle' });
  const [fired, setFired] = useState<{ lateMs: number; playing: boolean } | null>(null);
  const [detailsOpen, setDetailsOpen] = useState(false);
  const [rtt, setRtt] = useState<number | null>(null);

  const initialUri = useRef(room.tabUrl ?? SONGSTERR_HOME).current;
  const bridgeRef = useRef(bridge);
  bridgeRef.current = bridge;
  const latencyRef = useRef(latencyMs);
  latencyRef.current = latencyMs;
  const armedRun = useRef<number | null>(null);
  const followedTab = useRef<string | null>(room.tabUrl);

  const inject = useCallback((js: string) => web.current?.injectJavaScript(js), []);
  const goTo = useCallback((url: string) => isSongsterrUrl(url) && inject(cmdNavigate(url)), [inject]);

  const onMessage = useCallback((e: WebViewMessageEvent) => {
    const ev = parseBridgeEvent(e.nativeEvent.data);
    if (!ev) return;
    if (ev.type === 'state') {
      setBridge(ev.state);
    } else if (ev.type === 'armed') {
      setArmState((s) =>
        s.kind === 'arming' && s.runId === ev.runId
          ? ev.ok
            ? { kind: 'armed', runId: ev.runId }
            : { kind: 'failed' }
          : s,
      );
    } else if (ev.type === 'fired') {
      setFired({ lateMs: ev.lateMs, playing: ev.playing });
    }
  }, []);

  // Measured round trip, rounded so tiny changes do not trigger status updates.
  useEffect(() => {
    const id = setInterval(() => {
      const best = clock.bestRttMs;
      setRtt(best === null || !clock.ready ? null : Math.round(best / 5) * 5);
    }, 1000);
    return () => clearInterval(id);
  }, [clock]);

  // Tell the server what this player looks like. The bridge only reports changes.
  useEffect(() => {
    if (bridge) send({ t: 'status', status: { ...bridge, rttMs: rtt } });
  }, [bridge, rtt, send]);

  // Follow the host to a new song.
  useEffect(() => {
    if (!room.tabUrl || room.tabUrl === followedTab.current) return;
    followedTab.current = room.tabUrl;
    if (songKey(bridgeRef.current?.url) !== songKey(room.tabUrl)) goTo(room.tabUrl);
  }, [room.tabUrl, goTo]);

  // React to the room's synchronized run: arm when one is scheduled, pause when it is cleared.
  const runId = room.run?.runId ?? null;
  const startAt = room.run?.startAt ?? null;
  useEffect(() => {
    if (runId === null || startAt === null) {
      if (armedRun.current !== null) {
        armedRun.current = null;
        inject(cmdStop());
        setArmState({ kind: 'idle' });
      }
      return;
    }
    if (armedRun.current === runId) return;
    armedRun.current = runId;
    setFired(null);

    const fireAt = clock.toLocal(startAt) - latencyRef.current;
    if (fireAt - Date.now() < MIN_ARM_LEAD_MS) {
      setArmState({ kind: 'missed' });
      return;
    }
    setArmState({ kind: 'arming', runId });
    inject(cmdArm(runId, fireAt));
    const giveUp = setTimeout(() => setArmState((s) => (s.kind === 'arming' ? { kind: 'failed' } : s)), 1500);
    return () => clearTimeout(giveUp);
  }, [runId, startAt, clock, inject]);

  // ----- derived UI state -----
  const host = hostOf(room);
  const isHost = room.hostId === you;
  const myIssues = room.issues[you] ?? [];
  const notReady = room.members.filter((m) => (room.issues[m.id] ?? []).length > 0);
  const runActive = room.run !== null;
  const bridgeSong = songKey(bridge?.url);
  const canShare = bridgeSong !== null && bridge?.url != null;
  const onHostsSong = room.tabUrl !== null && bridgeSong === songKey(room.tabUrl);

  const status = ((): { text: string; warn: boolean } => {
    if (armState.kind === 'failed') return { text: 'This phone could not start the player. Reload the tab.', warn: true };
    if (armState.kind === 'missed') return { text: "You joined mid-song. You'll start with the next run.", warn: true };
    if (runActive && (armState.kind === 'arming' || armState.kind === 'armed')) {
      return { text: 'Armed. Starting together…', warn: false };
    }
    if (runActive && fired) {
      return fired.playing
        ? { text: 'Playing.', warn: false }
        : { text: 'Press play on the tab if it did not start.', warn: true };
    }
    if (myIssues.length > 0) return { text: describeIssue(myIssues[0] as Issue, host).text, warn: true };
    if (!room.tabUrl) {
      return { text: isHost ? 'Open a song, then share it with everyone.' : 'Waiting for the host to pick a song.', warn: false };
    }
    if (isHost) {
      return notReady.length === 0
        ? { text: 'Everyone is ready.', warn: false }
        : { text: `Waiting for ${notReady.map((m) => m.name).join(', ')}.`, warn: true };
    }
    return { text: 'Ready. Waiting for the host to press play.', warn: false };
  })();

  // The invite carries the server address too, so a friend never has to type it.
  const share = () =>
    void Share.share({
      message: serverUrl ? buildInviteMessage(room.code, serverUrl) : `Join my Songsterr Jam session. Code: ${room.code}`,
    }).catch(() => {});

  const runFix = (fix: IssueInfo['fix']) => {
    if (fix === 'goToSong') room.tabUrl && goTo(room.tabUrl);
    else if (fix) inject(cmdFix(fix));
  };

  const rttText = rtt === null ? 'syncing…' : `±${Math.ceil(rtt / 2)} ms`;
  const rttColor = rtt === null ? colors.muted : rtt <= 60 ? colors.accent : rtt <= 160 ? colors.warn : colors.danger;

  return (
    <View style={[styles.root, { paddingTop: insets.top }]}>
      <View style={styles.header}>
        <Pressable onPress={share} style={styles.codeChip}>
          <Text style={styles.codeLabel}>SESSION</Text>
          <Text style={styles.code}>{room.code}</Text>
        </Pressable>
        <View style={styles.headerMid}>
          <Text style={styles.headerText}>{room.members.length} in session</Text>
          <Text style={[styles.headerText, { color: rttColor }]}>sync {rttText}</Text>
        </View>
        <Pressable onPress={onLeave} hitSlop={8}>
          <Text style={styles.leave}>Leave</Text>
        </Pressable>
      </View>

      {error ? (
        <View style={styles.toast}>
          <Text style={styles.toastText}>{error}</Text>
        </View>
      ) : null}

      <View style={styles.webArea}>
        <TabView webRef={web} initialUri={initialUri} onMessage={onMessage} />
        {room.run ? <Countdown startAt={room.run.startAt} clock={clock} /> : null}

        {detailsOpen ? (
          <View style={styles.details}>
            <ScrollView contentContainerStyle={styles.detailsContent}>
              <Text style={styles.sectionTitle}>Players</Text>
              {room.members.map((m) => {
                const issues = room.issues[m.id] ?? [];
                const ok = issues.length === 0;
                return (
                  <View key={m.id} style={styles.memberRow}>
                    <View style={[styles.dot, { backgroundColor: ok ? colors.accent : colors.warn }]} />
                    <View style={styles.flex}>
                      <Text style={styles.memberName}>
                        {m.name}
                        {m.id === you ? ' (you)' : ''}
                        {m.isHost ? '  · host' : ''}
                      </Text>
                      <Text style={styles.memberSub}>
                        {ok ? (m.status.playing ? 'playing' : 'ready') : issues.map((i) => describeIssue(i, host).short).join(', ')}
                      </Text>
                    </View>
                  </View>
                );
              })}

              {myIssues.length > 0 ? (
                <>
                  <Text style={styles.sectionTitle}>To fix on your phone</Text>
                  {myIssues.map((i) => {
                    const info = describeIssue(i, host);
                    return (
                      <View key={i} style={styles.issueRow}>
                        <Text style={[styles.issueText, styles.flex]}>{info.text}</Text>
                        {info.fix ? (
                          <Pressable style={styles.fixButton} onPress={() => runFix(info.fix)}>
                            <Text style={styles.fixText}>Fix</Text>
                          </Pressable>
                        ) : null}
                      </View>
                    );
                  })}
                </>
              ) : null}

              <Text style={styles.sectionTitle}>Sound delay on this phone</Text>
              <Text style={styles.memberSub}>
                Bluetooth headphones add roughly 150-250 ms. If you hear yourself late compared to the others, raise this.
              </Text>
              <View style={styles.latencyRow}>
                <Pressable style={styles.stepper} onPress={() => onLatencyChange(Math.max(0, latencyMs - 10))}>
                  <Text style={styles.stepperText}>−10</Text>
                </Pressable>
                <Text style={styles.latencyValue}>{latencyMs} ms</Text>
                <Pressable style={styles.stepper} onPress={() => onLatencyChange(Math.min(600, latencyMs + 10))}>
                  <Text style={styles.stepperText}>+10</Text>
                </Pressable>
                <Pressable style={styles.stepper} onPress={() => onLatencyChange(0)}>
                  <Text style={styles.stepperText}>Wired</Text>
                </Pressable>
                <Pressable style={styles.stepper} onPress={() => onLatencyChange(180)}>
                  <Text style={styles.stepperText}>Bluetooth</Text>
                </Pressable>
              </View>
              {fired ? (
                <Text style={styles.memberSub}>
                  Last start: play pressed {fired.lateMs <= 0 ? 'on time' : `${fired.lateMs} ms late`}.
                </Text>
              ) : null}

              <View style={styles.actionsRow}>
                <Button label="Reload tab" tone="neutral" onPress={() => web.current?.reload()} />
                <Button label="Invite friends" tone="neutral" onPress={share} />
              </View>
            </ScrollView>
          </View>
        ) : null}
      </View>

      <View style={[styles.bottom, { paddingBottom: insets.bottom + 10 }]}>
        <Text style={[styles.statusText, status.warn && { color: colors.warn }]}>{status.text}</Text>
        <View style={styles.bottomRow}>
          {isHost ? (
            runActive ? (
              <Button label="■  Stop for everyone" tone="danger" onPress={() => send({ t: 'stop' })} />
            ) : !room.tabUrl || !onHostsSong ? (
              <Button
                label={room.tabUrl ? 'Share this song instead' : 'Share this song'}
                disabled={!canShare}
                onPress={() => bridge?.url && send({ t: 'setTab', url: bridge.url })}
              />
            ) : (
              <Button label="▶  Play for everyone" disabled={notReady.length > 0} onPress={() => send({ t: 'play' })} />
            )
          ) : room.tabUrl && !onHostsSong ? (
            <Button label="Go to the host's song" onPress={() => goTo(room.tabUrl as string)} />
          ) : null}
          <Button
            label={detailsOpen ? 'Hide ▾' : 'Session ▴'}
            tone="neutral"
            onPress={() => setDetailsOpen((o) => !o)}
          />
        </View>
        {isHost && !runActive && room.tabUrl && onHostsSong && notReady.length > 0 ? (
          <Pressable onPress={() => send({ t: 'play', force: true })} hitSlop={8}>
            <Text style={styles.forceText}>Start anyway (some players are not ready)</Text>
          </Pressable>
        ) : null}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  flex: { flex: 1 },
  root: { flex: 1, backgroundColor: colors.bg },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 14,
    paddingVertical: 8,
    gap: 14,
    borderBottomColor: colors.border,
    borderBottomWidth: StyleSheet.hairlineWidth,
  },
  codeChip: { backgroundColor: colors.panelRaised, borderRadius: 10, paddingHorizontal: 12, paddingVertical: 4 },
  codeLabel: { color: colors.muted, fontSize: 9, fontWeight: '700', letterSpacing: 1 },
  code: { color: colors.text, fontSize: 20, fontWeight: '800', letterSpacing: 3 },
  headerMid: { flex: 1 },
  headerText: { color: colors.muted, fontSize: 12 },
  leave: { color: colors.danger, fontSize: 15, fontWeight: '600' },
  toast: { backgroundColor: colors.danger, paddingHorizontal: 14, paddingVertical: 8 },
  toastText: { color: '#2a0707', fontWeight: '600' },
  webArea: { flex: 1 },
  web: { flex: 1, backgroundColor: colors.bg },
  countdown: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: 'rgba(0,0,0,0.55)',
  },
  countdownText: { color: colors.text, fontSize: 120, fontWeight: '900' },
  details: {
    position: 'absolute',
    left: 0,
    right: 0,
    bottom: 0,
    maxHeight: '75%',
    backgroundColor: colors.panel,
    borderTopLeftRadius: 16,
    borderTopRightRadius: 16,
    borderColor: colors.border,
    borderWidth: StyleSheet.hairlineWidth,
  },
  detailsContent: { padding: 16, gap: 10 },
  sectionTitle: { color: colors.text, fontSize: 14, fontWeight: '700', marginTop: 8 },
  memberRow: { flexDirection: 'row', alignItems: 'center', gap: 12 },
  dot: { width: 10, height: 10, borderRadius: 5 },
  memberName: { color: colors.text, fontSize: 15, fontWeight: '600' },
  memberSub: { color: colors.muted, fontSize: 13 },
  issueRow: { flexDirection: 'row', alignItems: 'center', gap: 10 },
  issueText: { color: colors.warn, fontSize: 14 },
  fixButton: { backgroundColor: colors.panelRaised, borderRadius: 8, paddingHorizontal: 14, paddingVertical: 6 },
  fixText: { color: colors.text, fontWeight: '700' },
  latencyRow: { flexDirection: 'row', alignItems: 'center', gap: 8, flexWrap: 'wrap' },
  latencyValue: { color: colors.text, fontSize: 16, fontWeight: '700', minWidth: 64, textAlign: 'center' },
  stepper: { backgroundColor: colors.panelRaised, borderRadius: 8, paddingHorizontal: 12, paddingVertical: 8 },
  stepperText: { color: colors.text, fontWeight: '600' },
  actionsRow: { flexDirection: 'row', gap: 10, marginTop: 8 },
  bottom: {
    backgroundColor: colors.panel,
    borderTopColor: colors.border,
    borderTopWidth: StyleSheet.hairlineWidth,
    paddingHorizontal: 14,
    paddingTop: 10,
    gap: 8,
  },
  statusText: { color: colors.text, fontSize: 14 },
  bottomRow: { flexDirection: 'row', gap: 10 },
  forceText: { color: colors.muted, fontSize: 13, textAlign: 'center', textDecorationLine: 'underline' },
  button: {
    flex: 1,
    backgroundColor: colors.accent,
    borderRadius: radius,
    alignItems: 'center',
    justifyContent: 'center',
    paddingVertical: 13,
    paddingHorizontal: 12,
  },
  buttonDanger: { backgroundColor: '#b91c1c' },
  buttonNeutral: { backgroundColor: colors.panelRaised },
  buttonText: { color: colors.accentText, fontSize: 16, fontWeight: '700' },
  disabled: { opacity: 0.4 },
});
