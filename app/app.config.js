// Extends app.json. The one-command launcher (scripts/play.mjs) sets JAM_SERVER_URL so the
// phone opens with the server address already filled in. This lives in the manifest the dev
// server hands to Expo Go, not in the JS bundle, so it is never stale from a bundler cache.
module.exports = ({ config }) => ({
  ...config,
  extra: {
    ...config.extra,
    serverUrl: process.env.JAM_SERVER_URL || null,
  },
});
