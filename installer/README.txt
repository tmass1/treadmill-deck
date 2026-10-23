C1250 Deck — on-console install
================================

What you need
- A Mac or Linux computer on the same Wi-Fi as the treadmill
- adb (Mac: brew install android-platform-tools)
- This folder: install.sh and c1250-deck.apk

On the treadmill
1. Tap the screen 10 times, wait 7 seconds, tap 10 more times.
   You should see "privileged mode is enabled" and the Android settings.
   If it asks for a code instead, stop here; newer firmware needs an extra step.
2. Settings > About > tap "Build number" 7 times to turn on Developer options.
3. Settings > Developer options > turn on Wireless debugging.
4. Open Wireless debugging and note the IP address.
   Tap "Pair device with pairing code" and note the pairing port and the 6-digit code.

On your computer
5. In this folder, run:
     bash install.sh <IP> <PAIRING_PORT> <CODE>
   e.g. bash install.sh 192.168.1.50 37123 482913
   If it asks for a port, enter the number after the colon under
   "IP address & Port" on the Wireless debugging screen.
6. Answer the questions. Saying yes to all three gives you the full
   replacement: iFit's lock-out off, the iFit home screen removed, and
   C1250 Deck opening when the treadmill starts.

Heart rate (Apple Watch)
- On the Watch, open a broadcast app such as BlueHeart, HeartCast or ECHO
  and start broadcasting. Turn off that app's own workout saving so the
  session isn't counted twice in Apple Health.
- On the treadmill, tap the Heart rate card, then Find heart rate monitors,
  and pick your Watch. C1250 Deck remembers it and reconnects next time.
- Set your max heart rate in the same sheet; the Z1-Z5 zone badge uses it.

Safety
- Keep the safety key clipped on. The console's physical STOP button and
  safety key always work and override the app.
- Start with a slow free run and check the belt follows the app before
  running a full workout.

Updates
- C1250 Deck checks for new versions every 6 hours. When one is ready, an
  "Update to …" button appears in the dock. Tap it, then Install update.
  The console restarts in a few seconds. Install is disabled mid-workout.
- To check right away: tap ? in the dock, then Check for updates.
- If Android shows its own "Update this app?" prompt, tap Update.
- Running install.sh again with a newer c1250-deck.apk still works too.

Undo
- The factory-reset pinhole on the console restores the stock iFit software.

Credits
- Motor-control code adapted from nordictrack-ifit-bypass by Matt Wissler (MIT).
  The iFit certificates are never bundled; the installer copies them from your
  own treadmill.
