# BandSync

Android app for a band: every phone shows its player's own sheet music and flashes the same beat at the
same moment, with no internet or router needed.

One phone hosts as the **master**. The others join as **followers** and pick a part. The master assigns
PDFs to parts, sets the song, and presses Start. Phones talk directly over Google Nearby Connections.

- **The right sheet for each part:** PDFs go only to the phones that need them, and a phone that already has one
  isn't sent it again.
- **One shared beat:** phones agree on a clock and work out every beat themselves, so nothing depends on a message
  arriving in time.
- **An arrow on the tab:** it shows what to play and scrolls the page to keep up.
- **Tempo for each instrument:** tempo and time signature changes can be read from each part's PDF.
- **A song library:** load a ready-made song in one tap, with its tabs and tempo presets.

## Run it

Build the app:

```bash
./gradlew :app:assembleDebug
```

Then install `app/build/outputs/apk/debug/app-debug.apk` on every phone. You need Android Studio or Android SDK
platform 37, plus JDK 17 or newer. Open this folder in Android Studio and it fills in the SDK path.

Phones need Android 8.0 or newer with Google Play services, with Bluetooth, Wi-Fi and Location switched on. Nearby
Connections doesn't work on emulators, so trying the real thing takes at least two physical phones.

Tabs aren't included in this repository, because they're commercial. A build from a fresh clone has an empty song
library, so add your own PDFs as described under [Song library](#song-library), or use **Add PDFs** in the app.

```bash
./gradlew :core:test
```

The tests in `:core` cover the timing, protocol and session logic. They need only a JDK, no Android SDK. Tests that
read real tabs are skipped when the library isn't there.

## What's been tested

- **On real phones:** the sheet viewer, the arrow, per-part tempos and joining two Android 13+ phones. Sync accuracy
  hasn't been measured on real phones yet.
- **In the tests:** clock sync, the timeline, the session protocol, and the PDF reader against real tabs. Timing
  figures below come from a simulator.

## Using it

1. Everyone enters a name and picks a part.
2. One phone taps **Host Session**. The others tap **Join Session** and choose it from the list.
3. On the master's **Band** tab, compare the 4-digit code with the phone asking to join, then accept.
4. On **Song**, set the title, tempo, time signature and count-in. Add PDFs, then assign them to parts. A part
   can have several PDFs, and page ranges such as `1-3, 5` limit what that part sees.
5. If the song changes tempo or time signature, tap **Read tempo from tabs**, or add each change by hand.
6. On **Live**, press **Start**. Everyone gets a countdown, the count-in, then a beat and bar number.

**Pause** freezes every phone on the same instant. **Resume** restarts from the start of that bar, with a
count-in at that section's tempo. The master can't change the song while it is playing, but can while paused.
The metronome keeps going after the last bar until someone presses **Stop**.

The master's Band tab shows each phone's sync as `±N ms`, the most its clock can be off the master's.

### If phones can't find each other

The joining phone and the master's Band tab both show a status card while they wait: whether Nearby started, the
exact error if it didn't, and whether Bluetooth, Wi-Fi, Location, permissions and Google Play services are ready.
Fix anything marked ✗, and tap **Try again** on both phones.

Location has to be switched on as well as allowed, with precise location chosen. The app asks for more than Google's
Nearby documentation lists, because two Android 13+ phones couldn't find each other with that list. The master also
stops being visible while a song is playing, so pause or stop it for a phone to join.

### Parts on different tempos

Each instrument can have its own tempo and time signature changes, so the drums can go to double time while the
bass holds. Each phone then shows its own part's beat, BPM, bar number and arrow.

**Read tempo from tabs** checks every sheet assigned to every part and gives each instrument its own list of
changes, even where the tabs agree, so one can be edited without touching the others. If a part has several sheets
it uses all of them, and the first in its order wins where they disagree. A part with no marks follows what the
marked parts agree on. It replaces any changes already entered, and needs tabs that print tempo marks and bar
numbers, as Songsterr's PDFs do. Sheets not assigned to a part are skipped, and a message says so.

Pick an instrument above the list to see and edit its changes. **Add a change** adds one for that instrument. The
**For** menu under a change moves it to other parts, or to **Everyone**.

All parts stay on one song clock. They can move apart and meet again exactly, and after a count-in everyone comes
in on the same instant, even when each part counts in at its own tempo. **Resume** goes by the bar the master's own
part paused in. Parts on the master's tempo come back in on that bar line, and a part on its own tempo comes back
in at its place at that moment, which may not be the start of one of its own bars.

### The arrow

While a song runs, a red arrow moves through the tab to show what to play, and the page scrolls to keep the
current line in view. It waits on the first note during the count-in. Pinch to zoom and following pauses until
you zoom back out.

The arrow needs a tab that prints bar numbers, as Songsterr's PDFs do. Other PDFs still show and scroll by hand,
with no arrow. It also relies on the song's bars matching the tab, so add a change for any tempo or time
signature change, especially a bar of a different length: otherwise the arrow falls behind or runs ahead from
that bar on.

The arrow lands on the first note of every bar. Within a bar it moves evenly by beat, and tabs give fast passages
extra width, so it can sit a little ahead of or behind individual notes there. Repeat signs aren't followed: the
arrow reads straight through the printed bars.

### Song library

Debug builds can include ready-to-play songs, so a rehearsal doesn't start with copying PDFs to the master phone.
On the master's **Library** tab, **Load** sends each part its tab and presets every instrument's tempo and time
signature changes from the tabs. It replaces whatever was loaded, and **Reload** gets back to the preset after
editing a song. A song has to be stopped before another can load. Phones keep what they've received, so going back
to an earlier song sends nothing again. The count-in setting carries over from song to song.

Parts with no tab, such as Vocals and Keys here, get no sheet and follow what the tabs agree on.

| Song | Tempo | Time signature | Bars |
| --- | --- | --- | --- |
| Iron Man | Eight tempos, 65 to 168 BPM | 4/4, with a 2/4 bar at bar 8 | 149 |
| Paranoid | 163 BPM | 4/4 | 112 |
| Seven Nation Army | 124 BPM | 4/4 | 117 |

To add a song, make a folder named after it in `app/src/debug/assets/library/`, put in one PDF per part named after
the part (`guitar.pdf`, `bass.pdf`, `drums.pdf`, `vocals.pdf`, `keys.pdf` or `other.pdf`), and rebuild. The PDFs are
commercial tabs, so that folder is gitignored and never goes into a release build. Without it the tab doesn't appear.

## How the timing works

No beat ever crosses the network. The master sends one description of the song's timeline, and each phone
works out every beat from its own clock.

**Clock sync.** Each follower pings the master with `System.nanoTime()` stamps:

```
rtt    = (t3 - t0) - (t2 - t1)
offset = ((t1 - t0) + (t2 - t3)) / 2      masterTime = localTime + offset
error  ≤ rtt / 2
```

Delay on the network is rarely even in both directions, and queueing is what skews it. Queueing also makes
the round trip longer, so the filter keeps the fastest quarter of the last 16 exchanges and takes their median
offset. Pings carry on every 500 ms for the whole session, because phone clocks drift apart by milliseconds
over a song. Corrections are limited to 0.5 ms each, so a new estimate can't make a beat visibly jump.

**Scheduled start.** Start sends `START_AT(masterNow + 2000 ms)`, so the message has time to reach everyone
before anything happens. A phone that hears it late, or joins mid-song, works out the current beat and lands
on it.

**Drift-free beats.** Beat `n` is always computed from `n`, using the section of the song it falls in:

```
timeOf(n) = sectionStart + (n - sectionFirstBeat) * 60e9 / sectionBpm
```

Nothing is added to the previous beat, so rounding can't build up: at most one nanosecond per section, however
long the song runs. The ticker runs on its own high-priority thread, sleeps until just before the beat, and
spins for the last few milliseconds. If a device's timers turn out to be coarse, the spin window widens by
itself.

**Tempo changes.** `START_AT` carries the whole song map: where each section starts, its tempo and its beats per
bar. Each phone works out every beat from that, so a tempo change needs no message of its own and can't arrive
late. The arithmetic is all integers, so every phone gets identical answers.

**Parts on different tempos.** The master sends each phone the map for its own part. All of them hang off one
shared time, the moment of bar 1: a part at song time `t` plays at `barOneTime + t`, whatever its tempo. So parts
stay locked together however far apart their bar numbers get. The start is timed so the slowest part's count-in
still begins at least 2 seconds ahead.

**What "zero delay" means here.** The remaining differences are the clock error above and up to one display
frame (about 16 ms at 60 Hz) between phones. The simulated figures below cover clock error only:

| Simulated link | Worst flash error against the master, 5-minute song |
| --- | --- |
| Fast, Wi-Fi style (50 random runs) | 1.8 ms |
| Fast, Wi-Fi style, across Iron Man's tempo changes | 1.2 ms |
| Slow, Bluetooth style | 5.9 ms |
| Fast link, synced only before the song | 17.9 ms |

These come from `BandSimulationTest`, which models clock drift and random uneven delays. They aren't
measurements from real phones.

**The arrow.** Each phone finds the printed bar numbers in its own PDF, which gives the position of every bar.
It then works out the arrow's place from the same shared timeline as the beat, so arrows need no messages of
their own and move together on every phone.

## How sheets reach the right phone

Files are named by their SHA-256 hash, and sheets are assigned to parts rather than devices. Two guitarists
get the same sheets, and a phone that reconnects isn't sent anything twice.

```
RoleSheetMap   Role -> ordered list of SheetSegment(fileId, pages)
SheetManifest  what one part needs: its files and its pages in order
```

1. The master sends each phone the manifest for its part.
2. The phone replies with the files it doesn't have.
3. The master sends those as `Payload.Type.FILE`, with a small message naming each one. Either can arrive first.
4. The phone checks the hash, stores the file and confirms. A corrupt transfer is discarded, and the master
   can resend it.

Page ranges control what a phone *shows*. The whole PDF is still transferred, because `PdfRenderer` can't cut
pages out without turning them into images. Don't rely on page ranges to keep pages private from a band member.

## Code layout

```
core/   Plain Kotlin with no Android imports, fully unit tested
  time/       clock sync
  metronome/  song map, timeline, playhead, drift-free ticker
  sheets/     roles, role-to-file map, page ranges, file store, bar positions
  protocol/   wire messages
  session/    master and follower logic, behind a Transport interface

app/    Android
  net/        Nearby Connections adapter and permissions
  pdf/        import and rendering
  session/    ViewModels
  ui/         Compose screens
```

The master stops advertising while a song runs, because discovery traffic competes with the links for the
radio. A phone that drops during a song keeps following the timeline by itself and can rejoin once the master
pauses or stops.

## License

BandSync's code is under the [MIT License](LICENSE). That doesn't cover any tabs you use with it, which stay under
their own copyright.
