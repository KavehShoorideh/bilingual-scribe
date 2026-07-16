# Device test plan

Manual checks on the Fairphone 4 (/e/OS). Run after sideloading each
milestone APK (`adb install app-debug.apk` or direct install). Results feed
`benchmarks.md` and gate the next milestone.

## M0 — Trustworthy recorder

Install & launch
- [ ] APK installs on /e/OS without Play Services warnings
- [ ] First launch: session list empty-state renders

Basic capture
- [ ] Record → speak 30 s → Stop: session appears with correct duration
- [ ] Playback: audio is intelligible, no gaps/clicks, correct speed
      (16 kHz mono — a chipmunk/slow voice means a sample-rate bug)
- [ ] VU meter moves with voice, settles near zero in silence
- [ ] Timer matches wall clock

Pause/resume
- [ ] Record 10 s → Pause → wait 30 s → Resume → 10 s → Stop:
      two segments listed, total ≈ 20 s, playback plays both in order
- [ ] Pause → Stop directly also works

Lifecycle & recovery (the important ones)
- [ ] Screen off 2 min while recording → audio kept recording (notification live)
- [ ] Navigate away / swipe app from recents while recording → recording
      continues (foreground service)
- [ ] **Kill test**: while recording ≥1 min, `adb shell am kill dev.bscribe.app`
      (or force-stop) → relaunch → session is in "Audio saved" state and the
      audio up to ~0.3 s before the kill plays back fine
- [ ] Notification Pause/Resume/Stop actions work with the app closed
- [ ] Deny mic permission → Record: no crash, sensible behavior

Housekeeping
- [ ] Rename, delete session (files actually gone: check app storage usage)
- [ ] Settings toggle persists across app restart

Battery/thermal baseline (for later comparison)
- [ ] 10 min continuous recording, screen on: note battery % drop and
      whether the device warmed → `benchmarks.md`

## M1a — add when it lands

- [ ] Model download on first run; offline sideload path
- [ ] Final pass produces readable transcript; word chips render; tap plays audio
- [ ] Benchmark screen numbers for tiny/base/small → `benchmarks.md`
- [ ] RAM: no LMK kill during small-q5_1 final pass on a 6 GB unit
