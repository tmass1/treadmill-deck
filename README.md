# C1250 Deck

A replacement console for the **NordicTrack Commercial 1250** treadmill. It runs on the treadmill's own
screen instead of iFit and drives the motor directly: speed, incline, start, pause and stop, with no
subscription and no account.

**[treadmill-deck.tommymassaro.com](https://treadmill-deck.tommymassaro.com)** is the project page,
and **[/console](https://treadmill-deck.tommymassaro.com/console/)** runs the interface in a browser in demo mode.

It's a personal project, built for one treadmill. It may work on other iFit consoles of the same
generation, but it hasn't been tried on any.

## What it does
- **Workouts:** quick starts (walk, jog, run, a 10% incline walk) and programs (intervals, hill climb,
  steady, walk), with a 3-2-1 countdown, big Pause and End buttons, and the console's own Start, STOP,
  speed and incline keys still working.
- **The run:** time and miles side by side, a lap track, pace, feet climbed, heart-rate zone.
- **Heart rate** from any Bluetooth heart-rate strap, or an Apple Watch through a broadcast app.
- **After the run:** heart rate, speed and incline over time, and time in each zone.
- **History:** every run kept, with weekly totals, a streak and personal bests.
- **Apple Fitness:** runs go to the site, and an iPhone Shortcut logs them as workouts so they count
  toward the rings (steps below).
- **Calories** from your weight, as active calories (what Apple Fitness counts); a distance calibration.
- **Home Assistant** (optional): a gym lights button and a TV remote (arrows, OK, Back, Home,
  Play/Pause, volume, and Netflix, Prime Video and Spotify).
- **Starts on power-up** in place of iFit, and **updates itself** over Wi-Fi.

## How it works
- **Interface:** `c1250-deck.html` is the whole UI, in a full-screen WebView on the console. It uses
  the [Cabin](https://github.com/tmass1/cabin) design system's Lit finish (inlined), and also runs as a
  plain web page (`/console/`, demo mode, or Bluetooth FTMS through QZ in Chrome).
- **Motor:** the console's motor board is a USB HID device (ICON's "FitPro 1" protocol). `FitPro.java`
  speaks it, as worked out from the iFit app: the startup sequence, the security handshake, and the
  read/write of speed, incline and workout mode. `Belt.java` runs it four times a second. USB permission
  comes from iFit's own updater, which grants the board to any app that asks; iFit has to be stopped
  first, since only one app can hold the board. Older consoles with iFit's separate GlassOS service are
  driven over its local gRPC API instead (`GlassOS.java`).
- **The site** (`worker/index.js` on Cloudflare Workers, data in KV): the project page, the web
  console, and three parts behind a private key: the app's update channel, the Diagnostics report box,
  and the run store that feeds Apple Fitness.

## Layout

| Path | What |
|---|---|
| `c1250-deck.html` | The console UI (one file) |
| `android/` | The app: Java source, build and release scripts |
| `installer/` | Putting the app on a treadmill |
| `site/`, `tools/` | The project page; `tools/make-site.mjs` builds the web console into `site/console/` |
| `worker/`, `wrangler.jsonc` | The Cloudflare Worker |
| `bg/` | Home screen photos (Unsplash; credits in `bg/CREDITS.md`) |

## Build

Needs JDK 11+ and the Android SDK command-line tools with platform 33 and build-tools 35.0.0:

```
sdkmanager "platforms;android-33" "build-tools;35.0.0"
ANDROID_HOME=~/Library/Android/sdk bash android/build.sh
```

That gives `android/c1250-deck.apk`. Private settings come from a folder next to this repo,
`../treadmill-deck-private` (or `DECK_PRIVATE=…`):

- `secrets.env` with `DECK_KEY` (the site key), `DECK_SITE` (your site), `KS_PASS` and
  `CLOUDFLARE_ACCOUNT_ID`
- `keystore/c1250deck.jks`, the signing key

Without it, the build makes a throwaway signing key and leaves uploads and updates off. Keep your
signing key safe: a console only takes updates signed with the key it was installed with.

## Install on a treadmill

See `installer/README.txt`. In short: unlock the console's Android settings (privileged mode), then
install the APK, either with `installer/install.sh` over ADB or by opening it in the console's browser.
In the app, Settings → Start on power-up makes it the treadmill's home screen.

## Release an update

```
bash android/release.sh 1.9.3 "What's new"
```

It bumps the version, builds, writes the APK and manifest to the private folder's `releases/`, and
publishes both to the update channel (Cloudflare KV; needs `CLOUDFLARE_API_TOKEN`). Consoles offer the
update within 6 hours, or straight away from Settings → Check for updates. Site changes deploy with
`npx wrangler@4 deploy`.

## Apple Fitness

Each finished run goes to `<site>/runs`. An iPhone Shortcut picks up new ones and logs them:

1. **Get Contents of URL** `<site>/runs/pending?k=<DECK_KEY>`
2. **Repeat with Each** item, and inside it: **Get Dictionary Value** for `start`, `minutes`, `miles`
   and `kcal`; **Log Workout** (Running) with those as date, duration (min), calories (kcal) and
   distance (mi); then **Get Dictionary Value** `id` and **Get Contents of URL**
   `<site>/runs/<id>/logged?k=<DECK_KEY>`.
3. Personal automations: run it when Fitness opens, and once a day.

If you also wear an Apple Watch, broadcast heart rate with an app like BlueHeart or HeartCast and turn
off that app's own workout saving, or each run counts twice.

## Credits

The first motor client was adapted from
[nordictrack-ifit-bypass](https://github.com/mattwissler-AE/nordictrack-ifit-bypass) by Matt Wissler
(MIT). OkHttp and Okio are Apache 2.0. Home photos from Unsplash (see `bg/CREDITS.md`).

C1250 Deck isn't affiliated with NordicTrack, iFit or ICON. Use it at your own risk: the console's
STOP key and safety key always override the app, and should be within reach whenever the belt moves.
