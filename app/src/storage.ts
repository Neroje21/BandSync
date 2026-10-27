import AsyncStorage from '@react-native-async-storage/async-storage';
import { useCallback, useEffect, useState } from 'react';
import { PRESET_SERVER } from './presetServer';

const KEY = 'songsterr-jam.settings.v1';

export interface Settings {
  name: string;
  server: string;
  /**
   * Extra delay between "app presses play" and "sound reaches your ears" on this phone.
   * Bluetooth headphones add roughly 150-250 ms; wired/speaker is ~0. The app presses
   * play this much earlier so everyone's sound lands together.
   */
  latencyMs: number;
}

const DEFAULTS: Settings = { name: '', server: '', latencyMs: 0 };

export function useSettings(): { loaded: boolean; settings: Settings; update(patch: Partial<Settings>): void } {
  const [loaded, setLoaded] = useState(false);
  // A server address from the launcher always wins over a saved one: tunnel addresses change every run.
  const preset = PRESET_SERVER ? { server: PRESET_SERVER } : {};
  const [settings, setSettings] = useState<Settings>({ ...DEFAULTS, ...preset });

  useEffect(() => {
    AsyncStorage.getItem(KEY)
      .then((raw) => {
        if (raw) setSettings({ ...DEFAULTS, ...(JSON.parse(raw) as Partial<Settings>), ...preset });
      })
      .catch(() => {
        /* first run or unreadable: keep defaults */
      })
      .finally(() => setLoaded(true));
  }, []);

  const update = useCallback((patch: Partial<Settings>) => {
    setSettings((prev) => {
      const next = { ...prev, ...patch };
      // Don't save the launcher's address: it would shadow the user's own on a later manual run.
      const toSave = PRESET_SERVER && next.server === PRESET_SERVER ? { ...next, server: '' } : next;
      AsyncStorage.setItem(KEY, JSON.stringify(toSave)).catch(() => {});
      return next;
    });
  }, []);

  return { loaded, settings, update };
}
