import Constants from 'expo-constants';

/** Server address handed over by the launcher through the Expo config, or null when started by hand. */
const extra = Constants.expoConfig?.extra as { serverUrl?: unknown } | undefined;
export const PRESET_SERVER: string | null =
  typeof extra?.serverUrl === 'string' && extra.serverUrl.length > 0 ? extra.serverUrl : null;
