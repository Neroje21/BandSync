// One command to get everyone playing:
//   node scripts/play.mjs            friends on the same Wi-Fi
//   node scripts/play.mjs --online   friends anywhere (uses free Cloudflare tunnels)
//
// It installs dependencies on first run, starts the session server, and starts the app's dev
// server with the server address already filled in, so the phone only needs to scan one QR code
// and the player only types a name and the session code. Ctrl+C stops everything.

import { spawn, spawnSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import net from 'node:net';
import { networkInterfaces } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(fileURLToPath(new URL('..', import.meta.url)));
const serverDir = path.join(root, 'server');
const appDir = path.join(root, 'app');
const online = process.argv.includes('--online');
const SERVER_PORT = process.env.PORT ?? '8080';
const METRO_PORT = '8081';
const isWin = process.platform === 'win32';

const children = [];
function killTree(child) {
  if (!child.pid || child.exitCode !== null) return;
  if (isWin) spawnSync('taskkill', ['/pid', String(child.pid), '/T', '/F'], { stdio: 'ignore' });
  else child.kill();
}
function shutdown(code = 0) {
  for (const c of children) killTree(c);
  process.exit(code);
}
process.on('SIGINT', () => shutdown(0));
process.on('SIGTERM', () => shutdown(0));
process.on('exit', () => children.forEach(killTree));

function fail(message) {
  console.error(`\n${message}\n`);
  shutdown(1);
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function waitFor(cond, ms) {
  const end = Date.now() + ms;
  while (Date.now() < end) {
    if (await cond()) return true;
    await sleep(250);
  }
  return false;
}

const healthy = () =>
  fetch(`http://127.0.0.1:${SERVER_PORT}/`)
    .then((r) => r.ok)
    .catch(() => false);

const portFree = (port) =>
  new Promise((resolve) => {
    const s = net.createServer();
    s.once('error', () => resolve(false));
    s.once('listening', () => s.close(() => resolve(true)));
    s.listen(Number(port));
  });

// ---- 1. dependencies (first run only) ----
for (const dir of [serverDir, appDir]) {
  if (existsSync(path.join(dir, 'node_modules'))) continue;
  console.log(`First run: installing ${path.basename(dir)} dependencies (a few minutes)...`);
  const r = spawnSync('npm', ['install'], { cwd: dir, stdio: 'inherit', shell: true });
  if (r.status !== 0) fail(`npm install failed in ${dir}.`);
}

// ---- 2. cloudflared, only needed online ----
function findCloudflared() {
  const candidates = ['cloudflared'];
  if (isWin) {
    for (const base of [process.env['ProgramFiles(x86)'], process.env.ProgramFiles]) {
      if (base) candidates.push(path.join(base, 'cloudflared', 'cloudflared.exe'));
    }
  }
  return candidates.find((c) => spawnSync(c, ['--version'], { stdio: 'ignore' }).status === 0) ?? null;
}

function openTunnel(cloudflared, port) {
  return new Promise((resolve, reject) => {
    const t = spawn(cloudflared, ['tunnel', '--url', `http://localhost:${port}`, '--no-autoupdate'], {
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    children.push(t);
    const timer = setTimeout(() => reject(new Error(`Timed out opening a tunnel to port ${port}.`)), 45_000);
    const scan = (chunk) => {
      const m = /https:\/\/[a-z0-9-]+\.trycloudflare\.com/.exec(chunk.toString());
      if (m) {
        clearTimeout(timer);
        resolve(m[0]);
      }
    };
    t.stdout.on('data', scan);
    t.stderr.on('data', scan);
    t.on('exit', (code) => {
      clearTimeout(timer);
      reject(new Error(`The tunnel to port ${port} stopped (exit ${code}).`));
    });
  });
}

function lanAddress() {
  const all = Object.values(networkInterfaces())
    .flat()
    .filter((a) => a && a.family === 'IPv4' && !a.internal)
    .map((a) => a.address);
  // Prefer home-network ranges over virtual adapters (WSL, Docker, VPN).
  return (
    all.find((a) => a.startsWith('192.168.')) ??
    all.find((a) => a.startsWith('10.')) ??
    all.find((a) => /^172\.(1[6-9]|2\d|3[01])\./.test(a)) ??
    all[0]
  );
}

// ---- 3. session server ----
if (await healthy()) {
  console.log(`Using the session server already running on port ${SERVER_PORT}.`);
} else {
  console.log('Starting the session server...');
  const server = spawn(process.execPath, ['--import', 'tsx', 'src/main.ts'], {
    cwd: serverDir,
    env: { ...process.env, PORT: SERVER_PORT },
    stdio: ['ignore', 'ignore', 'inherit'],
  });
  children.push(server);
  server.on('exit', (code) => fail(`The session server stopped (exit ${code}). Run "npm start" in the server folder to see why.`));
  if (!(await waitFor(healthy, 20_000))) fail('The session server did not start.');
}

// ---- 4. addresses ----
let serverUrl;
const expoEnv = {};
if (online) {
  const cloudflared = findCloudflared();
  if (!cloudflared) {
    fail(
      'Online mode needs "cloudflared" (a free Cloudflare tool), which is not installed.\n' +
        'Windows: run  winget install Cloudflare.cloudflared  then start this again.\n' +
        'Mac:     brew install cloudflared\n' +
        'Or use the same-Wi-Fi version, which needs nothing extra.',
    );
  }
  console.log('Opening tunnels (this takes a few seconds)...');
  try {
    const [serverHttps, metroHttps] = await Promise.all([openTunnel(cloudflared, SERVER_PORT), openTunnel(cloudflared, METRO_PORT)]);
    serverUrl = serverHttps.replace('https://', 'wss://');
    expoEnv.EXPO_PACKAGER_PROXY_URL = metroHttps;
  } catch (e) {
    fail(e.message);
  }
} else {
  const ip = lanAddress();
  if (!ip) fail('Could not find this computer\'s Wi-Fi/network address. Are you connected to a network?');
  serverUrl = `ws://${ip}:${SERVER_PORT}`;
  expoEnv.REACT_NATIVE_PACKAGER_HOSTNAME = ip;
}

// ---- 5. app dev server ----
if (!(await portFree(METRO_PORT))) {
  fail(`Port ${METRO_PORT} is already in use (is another Expo/Metro server running?). Close it and try again.`);
}

console.log('\n==============================================================');
console.log(online ? '  SONGSTERR JAM - friends anywhere' : '  SONGSTERR JAM - same Wi-Fi');
console.log('==============================================================');
console.log('  Everyone (once): install the free "Expo Go" app on their phone.');
console.log('  Then scan the QR code below:');
console.log('    - iPhone: with the Camera app');
console.log('    - Android: with the scanner inside Expo Go');
console.log('  In the app, type a name. One person taps "Host a session",');
console.log('  everyone else enters that 4-letter code and taps "Join".');
if (online) {
  console.log('\n  Anyone who has the QR code can open the app and reach your server,');
  console.log('  so share it only with the people you are playing with.');
}
console.log('  Press Ctrl+C here to stop everything.');
console.log('==============================================================\n');

const expo = spawn('npx', ['expo', 'start', '--port', METRO_PORT], {
  cwd: appDir,
  env: { ...process.env, ...expoEnv, JAM_SERVER_URL: serverUrl },
  stdio: 'inherit',
  shell: true,
});
children.push(expo);
expo.on('exit', (code) => shutdown(code ?? 0));
