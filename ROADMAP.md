# C1250 Deck roadmap

What's next for the treadmill console, in order. Tick items off as they ship (version in brackets).
Started 22 Oct 2026, after 1.8.5. Each release goes out over the air (Settings → Check for updates).

## Sharing it with other owners
The current goal, in this order. Until it's done, the app isn't ready to hand to anyone else.
- [x] **A key-free public build** (1.12.0): one APK with nothing private in it, on a public update channel; backups and reports only for the site's owner.
- [x] **Treadmill check** (1.13.0): the screen reads the treadmill's real speed and incline limits, checks the treadmill is compatible, and asks for a safety agreement before the belt moves.
- [ ] **Setup wizard**: first-run setup, including an optional Home Assistant step that lists the owner's own devices to pick from. Without Home Assistant, those buttons stay hidden.
- [ ] **Install guide**: on the site, with photos, including blocking iFit's updates and going back to iFit.
- [ ] **Decide what a shared build allows**: Control from phone can start the belt from another room. Take that to the legal consult before anyone else gets it.
- [ ] **Private test**: 2–3 owners, ideally on another model.
- [ ] **FAQs**, from what those owners ask. Then go public.

## 1. Your runs, kept
- [x] **Workout history** (1.9.0): every run of a minute or more is saved on the console: date, workout, time, miles, pace, climb, heart rate, the run over time. A History sheet from Home: this week's runs, miles and time, the streak, personal bests, and every run, each opening its own summary.
- [x] **Personal bests** (1.9.0): longest run, longest time, fastest pace over a mile, most climb. A new best says so on the Done screen.
- [ ] **Strava**: connect once, then each run uploads with its speed, incline and heart rate. Also a file export (TCX) for anything else.
- [x] **Backup** (1.9.1): each run is uploaded to the site (`/runs`, behind the deck key), kept for good, so a factory reset doesn't lose it.
- [x] **Apple Fitness** (1.9.1): an iPhone Shortcut picks up new runs from the site (`/runs/pending`) and logs them as workouts (time, distance, calories), so they count toward the rings. Heart rate comes from a Watch broadcast app, with its own workout saving off. (The Shortcut's steps are in README.md.)

## 2. Smarter workouts
- [ ] **Heart-rate mode**: pick a zone; speed (or incline) adjusts to hold you in it. Needs the Polar H10.
- [x] **Goals** (1.11.0): a quick start runs to a time or a distance (Home → Goal), with what's left under the time and miles, then eases to a walk at 0% for a cool-down (3 minutes by default) and ends. Keep going skips the cool-down.
- [x] **Workout builder** (1.14.0): build workouts on a phone over the home Wi-Fi (steps, repeats, starting from a template) and send one to the treadmill, where it waits on Home until someone taps Start.
- [x] **Phone control** (1.14.0): start, change, pause, resume and end a run from the phone. Its own switch, off until allowed at the console. Starting takes a long press and a 5-second countdown on the treadmill, with the safety key in.
- [x] **Calories from your weight** (1.9.2): Settings → You sets your weight; calories are active calories (what Apple Fitness counts), from speed, incline and weight.
- [x] **Distance calibration** (1.9.2): a ±% setting in You, from a tape-on-the-belt check. Each run also keeps the motor board's own distance and time counters, to calibrate against.
- [x] **Check the board's distance** (checked 1 Nov 2026, six runs): the board's time counter matches the app's to the second, but its distance counter reads 0 on every run, so it can't be used. Distance stays the app's own, with the calibration in You.

## 3. Bigger
- [ ] **Bluetooth treadmill (FTMS)**: the console shows up to Zwift, Peloton and other apps (on the gym Apple TV) as a smart treadmill: they read speed and set incline.
- [ ] **Real routes**: load a GPX of a real climb or race; incline follows the terrain as distance goes by.
- [ ] **Race your past self**: a ghost runner on the lap track from your best at the same workout.

## 4. Small things
- [x] **Ring cameras** (1.10.0–1.10.17): pictures and live view through Home Assistant, choose which show, and the doorbell opens on the screen mid-run.
- [x] **Sleep** (1.10.18): dark after 10 minutes (Settings → Sleep) with the belt stopped; a tap wakes it.
- [x] **Keep iFit away** (1.10.7–1.10.8) and **crash recovery** (1.10.10): the console stays in front, and a run survives a crash.
- [ ] **Belt care**: the board's total belt distance, with a reminder when the belt needs lubricating.
- [ ] **Music**: what's playing on the gym Sonos, with play/pause and skip.
- [ ] **Coaching cues**: spoken "one minute left", "next: recover", halfway.

## Done before the roadmap
1.8.0 belt control over USB · 1.8.1 Incline Workout, big Pause/End, time and miles together, Gym lights, start on power-up · 1.8.2 follows the console's own speed and incline keys · 1.8.3 Gym TV remote · 1.8.4–1.8.5 Netflix, Prime Video and Spotify buttons.
