import { StatusBar } from 'expo-status-bar';
import { useEffect, useState } from 'react';
import { Linking, View } from 'react-native';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { parseInvite, type Invite } from './src/invite';
import { HomeScreen } from './src/screens/HomeScreen';
import { RoomScreen } from './src/screens/RoomScreen';
import { useSession } from './src/net/useSession';
import { useSettings } from './src/storage';
import { colors } from './src/theme';

export default function App() {
  const session = useSession();
  const { loaded, settings, update } = useSettings();

  // An invite link (songsterrjam://join?code=...&server=...) opened this app: remember the server,
  // and hand the code to the join box.
  const [incoming, setIncoming] = useState<Invite | null>(null);
  useEffect(() => {
    const handle = (url: string | null | undefined): void => {
      if (!url) return;
      const invite = parseInvite(url);
      if (invite.code || invite.server) setIncoming(invite);
    };
    Linking.getInitialURL().then(handle).catch(() => {});
    const sub = Linking.addEventListener('url', (e) => handle(e.url));
    return () => sub.remove();
  }, []);
  useEffect(() => {
    // Wait for the saved settings to load, or they would overwrite the address from the link.
    if (loaded && incoming?.server) update({ server: incoming.server });
  }, [loaded, incoming, update]);

  return (
    <SafeAreaProvider>
      <StatusBar style="light" />
      {!loaded ? (
        <View style={{ flex: 1, backgroundColor: colors.bg }} />
      ) : session.room && session.you && session.clock ? (
        <RoomScreen
          room={session.room}
          you={session.you}
          clock={session.clock}
          serverUrl={session.serverUrl}
          error={session.error}
          latencyMs={settings.latencyMs}
          onLatencyChange={(latencyMs) => update({ latencyMs })}
          send={session.send}
          onLeave={session.leave}
        />
      ) : (
        <HomeScreen session={session} settings={settings} onSettings={update} incoming={incoming} />
      )}
    </SafeAreaProvider>
  );
}
