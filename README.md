# Bilingual Scribe

A **private, on-device voice notepad** that gradually learns your mixed
Persian/English speech.

Speak freely — your audio is always saved, even if the app crashes. A rough
live transcript streams while you talk. Afterward, tap misheard words and fix
them; your corrections become training data for a **personal Whisper
fine-tune** that slowly learns your voice and your Farsi/English
code-switching.

**Privacy first**: everything runs on your phone. No Google services, no
speech APIs, no telemetry. Training data leaves the device only when *you*
export it to a folder *you* control (e.g. a Syncthing share), and training
runs on *your* computer.

## How it works

```
┌─────────────── Fairphone 4 (/e/OS) ───────────────┐      ┌── your computer ──┐
│ mic → WAV on disk (crash-safe, always)            │      │ MacBook M3 (MPS)  │
│     → live rough transcript (whisper tiny, VAD)   │      │ or Hetzner (CUDA) │
│ stop → accurate pass (whisper base/small)         │      │                   │
│      → tap-to-fix word chips → corrections DB     │      │ LoRA fine-tune    │
│ export → JSONL + WAV clips → Syncthing folder ────┼──────▶ merge → ggml .bin │
│ import ◀── personalized ggml model ───────────────┼──────┤ quantize + eval   │
└───────────────────────────────────────────────────┘      └───────────────────┘
```

## Repository layout

| Path | What |
|---|---|
| `android/` | The app. Gradle modules: `:app` (UI + recording service), `:core` (pure-JVM audio/ASR logic, unit-tested), `:data` (Room DB, repositories), `:asr` (whisper.cpp JNI) |
| `trainer/` | Portable Python pipeline (uv): validate exports → build dataset → LoRA fine-tune → convert/quantize → evaluate |
| `third_party/whisper.cpp/` | Pinned submodule — the on-device ASR engine and the model conversion tools |
| `docs/` | Architecture, dataset format (normative), device test plan, benchmarks, privacy roadmap |

## Building

The app builds on any machine with JDK 17+ and the Android SDK. The Gradle
wrapper is committed, so no bootstrap step is needed:

```sh
git clone --recurse-submodules <this repo>
cd android
./gradlew :app:assembleDebug             # APK at app/build/outputs/apk/debug/
```

Use `./gradlew`, not a system `gradle` — the wrapper is pinned to Gradle 8.13
and Gradle 9.6+ cannot configure this project at all (it removed an internal
API that AGP 8.13 still uses).

CI (GitHub Actions) builds a sideloadable debug APK on every push — grab it
from the workflow run's artifacts. Tagging `v*` cuts a signed GitHub Release,
which is what Obtainium on the phone installs from.

Pure-JVM logic tests (no Android SDK needed): `./gradlew :core:test`.

Full toolchain setup, adb pairing, and the update flow:
[`docs/build-and-install.md`](docs/build-and-install.md).

## Status

- [x] Plan (`docs/architecture.md`)
- [x] M0.5 — Build system: wrapper, green build, CI, signed releases
- [ ] **M0 — Trustworthy recorder**: crash-safe capture, pause/resume, sessions
      (code complete and building; not yet verified on hardware — see
      `docs/device-test-plan.md`)
- [ ] M1a — On-device transcription (final pass, word chips)
- [ ] M1b — Live rough transcript while speaking
- [ ] M1c — Tap-to-fix corrections + dataset export
- [ ] M2 — Personal fine-tuning loop (trainer/)
- [ ] M3 — Polish + privacy roadmap

## Requirements

- Phone: Android 13+ (developed against /e/OS on Fairphone 4), arm64. No
  Google services required.
- Trainer: Python 3.11+, PyTorch (MPS or CUDA). See `trainer/README.md` (M2).
