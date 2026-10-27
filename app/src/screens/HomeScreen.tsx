import React, { useEffect, useState } from 'react';
import {
  ActivityIndicator,
  KeyboardAvoidingView,
  Platform,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { ROOM_CODE_LEN, normalizeRoomCode } from '../../../shared/protocol';
import { parseInvite, type Invite } from '../invite';
import type { Session } from '../net/useSession';
import { PRESET_SERVER } from '../presetServer';
import type { Settings } from '../storage';
import { colors, radius } from '../theme';

interface Props {
  session: Session;
  settings: Settings;
  onSettings(patch: Partial<Settings>): void;
  /** A tapped invite link, already applied to the server address; its code fills the join box. */
  incoming: Invite | null;
}

export function HomeScreen({ session, settings, onSettings, incoming }: Props) {
  const insets = useSafeAreaInsets();
  const [code, setCode] = useState('');
  useEffect(() => {
    if (incoming?.code) setCode(incoming.code);
  }, [incoming]);

  // The box takes a 4-letter code, or a pasted invite message (which also carries the server address).
  const onCodeInput = (text: string) => {
    if (text.length <= ROOM_CODE_LEN) return setCode(normalizeRoomCode(text));
    const invite = parseInvite(text);
    if (invite.server) onSettings({ server: invite.server });
    setCode(invite.code ?? normalizeRoomCode(text));
  };
  const [editServer, setEditServer] = useState(false);
  const presetShown = PRESET_SERVER !== null && settings.server === PRESET_SERVER && !editServer;
  const name = settings.name.trim() || 'Player';
  const canJoin = code.length === ROOM_CODE_LEN && !session.busy;

  return (
    <KeyboardAvoidingView style={styles.flex} behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
      <ScrollView
        contentContainerStyle={[styles.content, { paddingTop: insets.top + 32, paddingBottom: insets.bottom + 24 }]}
        keyboardShouldPersistTaps="handled"
      >
        <Text style={styles.title}>Songsterr Jam</Text>
        <Text style={styles.tagline}>Play the same Songsterr tab together, starting on the same beat.</Text>

        <Text style={styles.label}>Your name</Text>
        <TextInput
          style={styles.input}
          value={settings.name}
          onChangeText={(name) => onSettings({ name })}
          placeholder="Alex"
          placeholderTextColor={colors.muted}
          maxLength={24}
          autoCorrect={false}
        />

        {presetShown ? (
          <Text style={styles.hint}>
            Server is already set up for you.{' '}
            <Text style={styles.link} onPress={() => setEditServer(true)}>
              Change
            </Text>
          </Text>
        ) : (
          <>
            <Text style={styles.label}>Server address</Text>
            <TextInput
              style={styles.input}
              value={settings.server}
              onChangeText={(server) => onSettings({ server })}
              placeholder="192.168.1.20:8080"
              placeholderTextColor={colors.muted}
              autoCapitalize="none"
              autoCorrect={false}
              keyboardType="url"
            />
            <Text style={styles.hint}>
              Whoever runs the server tells you this. The host and everyone joining use the same one.
            </Text>
          </>
        )}

        {session.error ? <Text style={styles.error}>{session.error}</Text> : null}

        <Pressable
          style={[styles.button, session.busy && styles.disabled]}
          disabled={session.busy}
          onPress={() => session.host(settings.server, name)}
        >
          {session.busy ? <ActivityIndicator color={colors.accentText} /> : <Text style={styles.buttonText}>Host a session</Text>}
        </Pressable>

        <View style={styles.divider}>
          <View style={styles.rule} />
          <Text style={styles.or}>or join one</Text>
          <View style={styles.rule} />
        </View>

        <View style={styles.joinRow}>
          <TextInput
            style={[styles.input, styles.codeInput]}
            value={code}
            onChangeText={onCodeInput}
            placeholder="CODE"
            placeholderTextColor={colors.muted}
            autoCapitalize="characters"
            autoCorrect={false}
          />
          <Pressable
            style={[styles.button, styles.joinButton, !canJoin && styles.disabled]}
            disabled={!canJoin}
            onPress={() => session.join(settings.server, code, name)}
          >
            <Text style={styles.buttonText}>Join</Text>
          </Pressable>
        </View>
        <Text style={styles.hint}>Type the 4-letter code, or paste the whole invite message your host sent you.</Text>
      </ScrollView>
    </KeyboardAvoidingView>
  );
}

const styles = StyleSheet.create({
  flex: { flex: 1, backgroundColor: colors.bg },
  content: { paddingHorizontal: 20, gap: 8 },
  title: { color: colors.text, fontSize: 32, fontWeight: '800' },
  tagline: { color: colors.muted, fontSize: 16, marginBottom: 20 },
  label: { color: colors.text, fontSize: 14, fontWeight: '600', marginTop: 12 },
  hint: { color: colors.muted, fontSize: 13 },
  link: { color: colors.accent, fontWeight: '600' },
  input: {
    backgroundColor: colors.panel,
    borderColor: colors.border,
    borderWidth: 1,
    borderRadius: radius,
    color: colors.text,
    fontSize: 16,
    paddingHorizontal: 14,
    paddingVertical: 12,
  },
  error: { color: colors.danger, fontSize: 14, marginTop: 8 },
  button: {
    backgroundColor: colors.accent,
    borderRadius: radius,
    alignItems: 'center',
    justifyContent: 'center',
    paddingVertical: 14,
    marginTop: 16,
    minHeight: 50,
  },
  buttonText: { color: colors.accentText, fontSize: 17, fontWeight: '700' },
  disabled: { opacity: 0.45 },
  divider: { flexDirection: 'row', alignItems: 'center', gap: 12, marginTop: 28 },
  rule: { flex: 1, height: StyleSheet.hairlineWidth, backgroundColor: colors.border },
  or: { color: colors.muted, fontSize: 13 },
  joinRow: { flexDirection: 'row', gap: 12, alignItems: 'center' },
  codeInput: { flex: 1, fontSize: 22, letterSpacing: 6, fontWeight: '700', textAlign: 'center' },
  joinButton: { flex: 1, marginTop: 0 },
});
