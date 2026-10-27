// The protocol, clock-sync math and Songsterr URL rules live in ../shared so the
// server and the app use exactly the same code. Metro only watches the project
// folder by default, so tell it about the shared folder.
const { getDefaultConfig } = require('expo/metro-config');
const path = require('path');

const config = getDefaultConfig(__dirname);
config.watchFolders = [...(config.watchFolders ?? []), path.resolve(__dirname, '../shared')];

module.exports = config;
