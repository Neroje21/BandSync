// One command to make the session server reachable from the internet:
//   npm run public
// Starts the server (or reuses one already running on the port), opens a Cloudflare quick
// tunnel to it, and prints the wss:// address to give everyone. Ctrl+C stops both.
// Needs `cloudflared` installed (Windows: winget install Cloudflare.cloudflared).

import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const port = process.env.PORT ?? '8080';
const serverDir = fileURLToPath(new URL('..', import.meta.url));
const children = [];

function stopAll() {
  for (const c of children) c.kill();
}
process.on('SIGINT', () => {
  stopAll();
  process.exit(0);
});
process.on('SIGTERM', () => {
  stopAll();
  process.exit(0);
});

const healthy = () =>
  fetch(`http://127.0.0.1:${port}/`)
    .then((r) => r.ok)
    .catch(() => false);

async function waitFor(cond, ms) {
  const end = Date.now() + ms;
  while (Date.now() < end) {
    if (await cond()) return true;
    await new Promise((r) => setTimeout(r, 250));
  }
  return false;
}

if (await healthy()) {
  console.log(`Using the server already running on port ${port}.`);
} else {
  console.log(`Starting the session server on port ${port}...`);
  const server = spawn(process.execPath, ['--import', 'tsx', 'src/main.ts'], {
    cwd: serverDir,
    env: { ...process.env, PORT: port },
    stdio: ['ignore', 'ignore', 'inherit'],
  });
  children.push(server);
  server.on('exit', (code) => {
    if (code) console.error(`Server stopped (exit ${code}).`);
    stopAll();
    process.exit(code ?? 0);
  });
  if (!(await waitFor(healthy, 15_000))) {
    console.error('The server did not start. Try `npm start` to see the error.');
    stopAll();
    process.exit(1);
  }
}

console.log('Opening the tunnel...');
const tunnel = spawn('cloudflared', ['tunnel', '--url', `http://localhost:${port}`, '--no-autoupdate'], {
  stdio: ['ignore', 'pipe', 'pipe'],
});
children.push(tunnel);

tunnel.on('error', (err) => {
  if (err.code === 'ENOENT') {
    console.error('cloudflared is not installed (or this terminal was opened before installing it).');
    console.error('Windows: winget install Cloudflare.cloudflared   then open a new terminal.');
    console.error('Mac:     brew install cloudflared');
    console.error('Others:  https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/');
  } else {
    console.error('Could not start cloudflared:', err.message);
  }
  stopAll();
  process.exit(1);
});
tunnel.on('exit', (code) => {
  console.error(`The tunnel stopped (exit ${code}). Run this again for a new address.`);
  stopAll();
  process.exit(code ?? 1);
});

let announced = false;
const scan = (chunk) => {
  if (announced) return;
  const m = /https:\/\/[a-z0-9-]+\.trycloudflare\.com/.exec(chunk.toString());
  if (!m) return;
  announced = true;
  const wss = m[0].replace('https://', 'wss://');
  console.log('\n============================================================');
  console.log('  Server address for the app:');
  console.log(`\n      ${wss}\n`);
  console.log('  1. Open the app and enter (or paste) this address, then tap "Host a session".');
  console.log('  2. In the session, open "Session" > "Invite friends" and send the message.');
  console.log('     It contains the code AND this address, so friends only paste it into the app.');
  console.log('  It is valid until you press Ctrl+C. A new run gives a new address.');
  console.log('============================================================\n');
};
tunnel.stdout.on('data', scan);
tunnel.stderr.on('data', scan);
