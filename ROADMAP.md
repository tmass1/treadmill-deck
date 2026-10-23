# C1250 Deck roadmap

What's next for the treadmill console, in order. Tick items off as they ship (version in brackets).
Started 22 Oct 2026, after 1.8.5. Each release goes out over the air (Settings → Check for updates).

## 1. Your runs, kept
- [x] **Workout history** (1.9.0): every run of a minute or more is saved on the console: date, workout, time, miles, pace, climb, heart rate, the run over time. A History sheet from Home: this week's runs, miles and time, the streak, personal bests, and every run, each opening its own summary.
- [x] **Personal bests** (1.9.0): longest run, longest time, fastest pace over a mile, most climb. A new best says so on the Done screen.
- [ ] **Strava**: connect once, then each run uploads with its speed, incline and heart rate. Also a file export (TCX) for anything else.
- [x] **Backup** (1.9.1): each run is uploaded to the site (`/runs`, behind the deck key), kept for good, so a factory reset doesn't lose it.
- [x] **Apple Fitness** (1.9.1): an iPhone Shortcut picks up new runs from the site (`/runs/pending`) and logs them as workouts (time, distance, calories), so they count toward the rings. Heart rate comes from a Watch broadcast app, with its own workout saving off. (The Shortcut's steps are in README.md.)

## 2. Smarter workouts
- [ ] **Heart-rate mode**: pick a zone; speed (or incline) adjusts to hold you in it. Needs the Polar H10.
- [ ] **Goals**: run to a distance or a time (5K, 30 min) with progress on screen and an automatic cool-down.
- [ ] **Workout builder**: make workouts on the Mac or phone (intervals, hills, pace targets); they appear on the treadmill.
- [x] **Calories from your weight** (1.9.2): Settings → You sets your weight; calories are active calories (what Apple Fitness counts), from speed, incline and weight.
- [x] **Distance calibration** (1.9.2): a ±% setting in You, from a tape-on-the-belt check. Each run also keeps the motor board's own distance and time counters, to calibrate against.
- [ ] **Check the board's distance**: after a run or two, compare the board's counters (in `/runs`) with the app's distance; if they're the belt's measured distance, use them.

## 3. Bigger
- [ ] **Bluetooth treadmill (FTMS)**: the console shows up to Zwift, Peloton and other apps (on the gym Apple TV) as a smart treadmill: they read speed and set incline.
- [ ] **Real routes**: load a GPX of a real climb or race; incline follows the terrain as distance goes by.
- [ ] **Race your past self**: a ghost runner on the lap track from your best at the same workout.

## 4. Small things
- [ ] **Belt care**: the board's total belt distance, with a reminder when the belt needs lubricating.
- [ ] **Music**: what's playing on the gym Sonos, with play/pause and skip.
- [ ] **Coaching cues**: spoken "one minute left", "next: recover", halfway.

## Done before the roadmap
1.8.0 belt control over USB · 1.8.1 Incline Workout, big Pause/End, time and miles together, Gym lights, start on power-up · 1.8.2 follows the console's own speed and incline keys · 1.8.3 Gym TV remote · 1.8.4–1.8.5 Netflix, Prime Video and Spotify buttons.
