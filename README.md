# Songsterr Jam

Play the same Songsterr tab with other people and start on the same beat.

Songsterr has no public API and no way to link two accounts, so this app doesn't try to hook into the
Songsterr mobile app. Instead it opens songsterr.com's own player in a WebView and acts as the conductor:

1. One person **hosts a session** and gets a 4-letter code. Others **join** with it.
2. The host opens a song and taps **Share this song**. Everyone's phone loads it.
3. Before anyone can start, the app checks that every phone matches the host: same song, speed, audio
   source (Orig./Synth), count-in and tab revision, player loaded, sound allowed. Mismatches are listed per
   person, with a **Fix** button where the app can do it for you.
4. The host taps **Play for everyone**. The server picks a moment a few seconds ahead on its own clock; every
   phone converts that to its own clock, parks its player at bar 1, and presses play at that exact moment.

No audio is streamed, so network lag never ends up in the sound. Each phone plays Songsterr locally and only
the *start time* is shared.

```
app/      Expo (React Native) app: home screen, room screen, WebView + Songsterr bridge
server/   Node WebSocket server: rooms, host rules, readiness checks, scheduled start
shared/   Protocol, clock-sync math and Songsterr URL rules used by both
```

## Run it (the simple way)

You need a recent Node.js on your computer (built and tested with Node 24), and the free **Expo Go** app on
every phone (App Store / Google Play). Nothing else.

**On your computer, double-click one of these files** in the project folder:

| File | Use it when |
| --- | --- |
| `Start Songsterr Jam - same Wi-Fi.bat` | everyone is on the same Wi-Fi as your computer |
| `Start Songsterr Jam - friends online.bat` | friends are anywhere (free Cloudflare tunnels) |

(On Mac/Linux, or from a terminal: `npm run play` or `npm run play:online` in the project folder.)

The first run installs what it needs, which takes a few minutes. Then it starts the server and the app and
shows a **QR code**. On each phone:

1. Scan the QR code (iPhone: Camera app. Android: scanner inside Expo Go). The app opens in Expo Go.
2. Type your name. One person taps **Host a session**; everyone else enters the 4-letter code and taps
   **Join**. The server address is already filled in.

Press Ctrl+C in the window (or close it) to stop everything.

**Friends online** needs `cloudflared` once: `winget install Cloudflare.cloudflared` on Windows,
`brew install cloudflared` on Mac. The launcher tells you if it's missing. It opens two free tunnels, one for
the session server and one that lets phones load the app from your computer. No account, router or
firewall changes. The QR code is different on every run, your computer has to stay on while you play, and
anyone who has the QR code can open the app and reach your server, so share it only with the people you're
playing with.

## Installable Android app (no Expo Go, no dev server)

For friends on Android you can build a real app file (`.apk`) once, send them the download link, and they
never need Expo Go or your dev server again.

**Build it (once):** double-click `Build Android app (APK).bat`. It builds in Expo's cloud (free Expo
account needed; if you don't have one, create it at https://expo.dev/signup, and the script asks you to
log in). It also sets up a git repository for the project the first time, which the build service needs
because the app shares code with the server. When it finishes it prints a link. Open that link on an Android
phone and tap **Install** (Android will ask to allow installing from your browser). Send friends the same link.
Free-plan builds can wait in a queue, and I haven't been able to run the cloud build myself, so if it fails
the error is shown in that window and on the build page.

**Play with it:**

1. Double-click `Start Songsterr Jam server (for the installed app).bat`. It prints a `wss://...` address.
2. Open the app, enter that address, and tap **Host a session**.
3. Tap **Session** > **Invite friends** and send the message. It contains the code *and* the server address.
4. Friends open the app and paste the whole message into the join box (or tap the link in it, if their chat
   app makes it tappable), then tap **Join**. The address and code fill themselves in.

Notes:

- The installed app talks to the server over `wss://` (the tunnel address). Same-Wi-Fi sessions with a plain
  `ws://192.168...` address work through Expo Go (the first two launchers above) but not in the installed
  app, because Android blocks unencrypted connections in installed apps.
- Android only. iPhones can still use Expo Go with the launchers above; an installable iPhone app needs a
  paid Apple developer account.
- The app id is `com.example.songsterrjam` (in `app/app.json`). It's fine for sharing among friends; change
  it before ever publishing to a store.
- The tunnel address changes every time you restart the server, so friends need a new invite each time.

### Running the pieces by hand

```bash
cd server && npm install && npm start      # session server; prints ws://<your-ip>:8080
cd server && npm run public                # same, plus a public wss:// tunnel address
cd app && npm install && npx expo start    # the app; scan the QR code with Expo Go
cd app && npx eas-cli login && npx eas-cli build -p android --profile preview   # the installable .apk
```

When started by hand the app asks for the server address on its first screen (`192.168.1.20:8080` on the
same Wi-Fi, or a `wss://...` address; a bare domain like `jam.example.com` is treated as `wss://`). The session server is a plain Node app that reads `PORT` and answers
`GET /` with `ok`, so it can also run on any Node host instead of your computer. Use `wss://` for anything
over the internet. Plain `ws://` works in Expo Go for development, but a standalone Android/iOS build blocks
unencrypted connections unless you configure it.

## Playing together

- **Host:** browse Songsterr inside the app (search, log in, pick your instrument), then **Share this song**.
  **Play for everyone** unlocks when every player is green. **Session** shows who is blocked and why.
  **Start anyway** is there if you want to override.
- **Everyone:** tap the tab once after it loads (phones only allow sound after a touch). Then match the host's
  speed and audio source; the app tells you what differs.
- **Different instruments are fine.** Each person can open their own track of the same song.
- **Bluetooth headphones:** open **Session** and set **Sound delay** to *Bluetooth* (about 180 ms), then
  nudge by ear. The app presses play that much earlier on your phone so your sound lands with everyone else's.
- **Joining mid-song** isn't possible: the player can't seek to a shared position. You'll start with the next
  run, and the host can restart at any time.

## How accurate is it, honestly

Measured here, with simulated phones whose clocks were seconds apart and with uneven network delays, using
the same clock-sync code the app runs: the moments they pressed play differed by **0.5-15.5 ms** across 12
runs. The remaining error is network *asymmetry* (outbound slower than return), which no clock-sync scheme
can see. Expect about that on a good connection and more on a bad one. The session screen shows `sync ±N ms`,
the worst-case bound for your phone.

What that does **not** cover:

- **Not tested on real phones yet.** The server, sync logic and the page-control script were tested (the
  script against the live Songsterr player); the app builds and typechecks, but I couldn't run it on a device.
  First things to check: that audio starts when the app presses play, and how your phones' sound delay lines up.
- Each phone starts its audio engine in its own time (and phones differ by tens of ms). That is what the
  per-phone **Sound delay** control is for.
- Phones keep their own audio clocks after the start. Over a few minutes drift should be small, but I haven't
  measured it; the host can restart whenever it matters.
- This is tight enough to play along at normal tempos. It is not sample-accurate.

## Things to know

- **Unofficial.** Not affiliated with Songsterr. It shows their website and presses the buttons on their
  page; nothing is copied or streamed.
- **It depends on Songsterr's page.** All the page-control code lives in
  `app/src/bridge/songsterrBridge.ts`. It uses the player's element ids (`#control-play`, `#control-speed`,
  `#control-source`, `#control-countin`), which were correct when I checked. If Songsterr changes its page,
  that's the one file to update.
- **Safety:** only `https://songsterr.com` links can be shared, and the in-app browser only navigates within
  Songsterr, so a host can't send others to another site.
- Sessions live in server memory (up to 12 players each) and vanish when the server restarts.
- **Tested without phones:** both launcher modes were run here and checked at the network level (the Expo
  manifest carries the server address, the app bundle downloads through the public tunnel, the session server
  answers through its tunnel). Scanning the QR code in Expo Go on a real phone is the part I couldn't do.

## Tests

```bash
cd server
npm test          # 37 tests: rooms, host rules, readiness, URL safety, invites, clock-skew simulation
npm run typecheck

cd ../app
npx tsc --noEmit
```
