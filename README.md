# BandSync

Android app for a band: every phone shows its player's own sheet music and flashes the same beat at the
same moment, with no internet or router needed.

One phone hosts as the **master**. The others join as **followers** and pick a part. The master assigns
PDFs to parts, sets the song, and presses Start. Phones talk directly over Google Nearby Connections.

## Run it

Install `BandSync-debug.apk` on every phone, or build it:

```bash
./gradlew :app:assembleDebug
```

You need Android Studio or Android SDK platform 37, plus JDK 17 or newer. Open this folder in Android Studio
and it fills in the SDK path. Phones need Android 8.0 or newer with Google Play services.

Nearby Connections doesn't work on emulators, so trying the real thing takes at least two physical phones.

```bash
./gradlew :core:test
```

The tests in `:core` cover the timing, protocol and session logic. They need only a JDK, no Android SDK.

## Using it

1. Everyone enters a name and picks a part.
2. One phone taps **Host Session**. The others tap **Join Session** and choose it from the list.
3. On the master's **Band** tab, compare the 4-digit code with the phone asking to join, then accept.
4. On **Song**, set the title, tempo, time signature and count-in. Add PDFs, then assign them to parts. A part
   can have several PDFs, and page ranges such as `1-3, 5` limit what that part sees.
5. On **Live**, press **Start**. Everyone gets a countdown, the count-in, then a shared beat and bar number.

**Pause** freezes every phone on the same instant. **Resume** restarts from the start of that bar, with a
count-in. The master can't change the song while it is playing, but can while paused.

The master's Band tab shows each phone's sync as `±N ms`, the most its clock can be off the master's.

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

**Drift-free beats.** Beat `n` is always computed from `n`:

```
masterTime(n) = startTime + (n - firstBeat) * 60e9 / bpm
```

Nothing is added to the previous beat, so rounding can't build up. The ticker runs on its own high-priority
thread, sleeps until just before the beat, and spins for the last few milliseconds. If a device's timers turn
out to be coarse, the spin window widens by itself.

**What "zero delay" means here.** The remaining differences are the clock error above and up to one display
frame (about 16 ms at 60 Hz) between phones. The simulated figures below cover clock error only:

| Simulated link | Worst flash error against the master, 5-minute song |
| --- | --- |
| Fast, Wi-Fi style (50 random runs) | 1.8 ms |
| Slow, Bluetooth style | 5.9 ms |
| Fast link, synced only before the song | 17.9 ms |

These come from `BandSimulationTest`, which models clock drift and random uneven delays. They aren't
measurements from real phones.

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
  metronome/  timeline, playhead, drift-free ticker
  sheets/     roles, role-to-file map, page ranges, file store
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
