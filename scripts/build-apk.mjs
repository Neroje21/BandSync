// Builds an installable Android app (.apk) in Expo's cloud, so friends can install it
// without Expo Go:   node scripts/build-apk.mjs
//
// What it does for you:
//  1. installs the app's dependencies if needed
//  2. makes sure the project is in a git repository (Expo's build service uploads the repo,
//     and the app imports ../shared, so the repo has to be the whole project folder)
//  3. logs you in to Expo if you aren't (you type your own login; this script never sees it)
//  4. starts the cloud build and prints the download link when it finishes

import { spawnSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(fileURLToPath(new URL('..', import.meta.url)));
const appDir = path.join(root, 'app');
const EAS = 'eas-cli@latest';

// npm/npx are .cmd files on Windows, so they need a shell. Their arguments never contain spaces.
const run = (cmd, args, cwd, opts = {}) =>
  spawnSync(cmd, args, { cwd, stdio: 'inherit', shell: true, ...opts });
const capture = (cmd, args, cwd) =>
  spawnSync(cmd, args, { cwd, encoding: 'utf8', shell: true });
// git is a real program, so no shell: that keeps arguments like the commit message in one piece.
const git = (args, stdio = 'inherit') => spawnSync('git', args, { cwd: root, stdio, encoding: 'utf8' });

function fail(message) {
  console.error(`\n${message}\n`);
  process.exit(1);
}

// 1. dependencies
if (!existsSync(path.join(appDir, 'node_modules'))) {
  console.log('Installing the app dependencies (a few minutes)...');
  if (run('npm', ['install'], appDir).status !== 0) fail('npm install failed.');
}

// 2. git repository at the project root
if (git(['--version'], 'pipe').status !== 0) {
  fail('Git is not installed. Install it from https://git-scm.com/downloads and run this again.');
}
if (!existsSync(path.join(root, '.git'))) {
  console.log('Setting up a git repository for the project (a one-time step the build service needs)...');
  if (git(['init']).status !== 0) fail('git init failed.');
}
// Checked separately so an interrupted first run (repository but no commit) is repaired.
if (git(['rev-parse', '--verify', 'HEAD'], 'pipe').status !== 0) {
  console.log('Making the first git commit...');
  // Only set an identity inside this repository, and only if the machine has none.
  if (!git(['config', 'user.email'], 'pipe').stdout.trim()) {
    git(['config', 'user.email', 'jam@localhost.invalid']);
    git(['config', 'user.name', 'Songsterr Jam']);
  }
  if (git(['-c', 'core.safecrlf=false', 'add', '-A']).status !== 0) fail('git add failed.');
  if (git(['commit', '-q', '-m', 'Songsterr Jam']).status !== 0) fail('Could not make the first git commit.');
}

// 3. Expo login
const who = capture('npx', ['--yes', EAS, 'whoami'], appDir);
if (who.status !== 0 || /not logged in/i.test(`${who.stdout}${who.stderr}`)) {
  console.log('\nYou need a free Expo account to use the build service.');
  console.log('If you do not have one, create it at https://expo.dev/signup, then log in below.\n');
  if (run('npx', ['--yes', EAS, 'login'], appDir).status !== 0) fail('Login did not complete.');
}

// 4. build
console.log('\nStarting the cloud build. Answer "yes" if it offers to create a project or generate a keystore.');
console.log('Free-plan builds can wait in a queue, so this may take a while. You can close this window once');
console.log('it says the build is queued and open the link it prints to watch progress.\n');
const build = run('npx', ['--yes', EAS, 'build', '--platform', 'android', '--profile', 'preview'], appDir);
if (build.status !== 0) fail('The build did not finish. Read the messages above for the reason.');

console.log('\nDone. Open the build link above on an Android phone and tap Install (allow "install unknown apps"');
console.log('for your browser if Android asks). Send friends the same link.');
