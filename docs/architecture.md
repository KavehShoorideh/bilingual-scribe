# Architecture

> The living plan. Updated as milestones land. For the dataset contract see
> `dataset-format.md`; for measured device numbers see `benchmarks.md`.

## What this is

A private, on-device voice notepad for Farsi/English code-switched speech,
with a correction loop that feeds a personal Whisper fine-tune:

1. **Never lose an idea** — raw audio persists independently of everything else.
2. **Rough live transcript** while speaking (imperfect by design).
3. **Pause/resume** sessions.
4. **Tap-to-fix** misheard words after stopping.
5. **The model learns** from corrections: LoRA fine-tune off-phone, personalized
   model imported back. This is the long pole.
6. **Privacy first**; a future multi-user improvement scheme must not leak
   voice or content (see `privacy-roadmap.md`).

Target device: Fairphone 4 (Snapdragon 750G, 6/8 GB) on /e/OS (Android 14,
no Google services). Training: MacBook Pro M3 (PyTorch MPS) primary, Hetzner
CUDA overflow.

## Key decisions (researched July 2026)

| Decision | Why |
|---|---|
| whisper.cpp as a git submodule pinned `v1.9.1`, CMake `externalNativeBuild` | No official Maven artifact; third-party AARs lag; we need `GGML_VULKAN=OFF` and version-lock with the trainer's conversion scripts |
| CPU-only, arm64-v8a, `-march=armv8.2-a+dotprod+fp16` | Vulkan on Adreno 6xx crashes (16-bit storage unsupported, DeviceLost) |
| Live decode: VAD-gated, ~8 s window / ~3.5 s stride, greedy, LocalAgreement-2 commit | Naive 0.5 s-stride streaming measured ~5× slower than realtime on mobile CPUs |
| Multilingual models only (tiny→live, base→final default, small→quality) | The `.en` tokenizers can never learn Farsi; fine-tuning path needs the multilingual vocab |
| Taps select words by **index**; timestamps only cut audio, snapped to VAD bounds ±padding | Token timestamps jitter 100–400 ms |
| Models are ggml `.bin` (not GGUF) | whisper.cpp's format; conversion = `convert-h5-to-ggml.py` → `quantize` |
| Corrections are an **overlay**; transcript words are immutable | Corrections survive re-transcription and double as training pairs |
| Trainer: plain PyTorch + PEFT, device auto-detect mps/cuda/cpu, no bitsandbytes | Must run identically on Apple Silicon and CUDA |

## Modules

```
android/
  :core   pure JVM — WAV write/repair/read, levels, ASR domain types.
          Unit-tested without the Android SDK (runs in restricted sandboxes).
  :data   Room schema v1 + repositories + DataStore settings.
          SessionEntity → RecordingEntity (one per pause/resume segment, one WAV each)
          → TranscriptWordEntity (immutable) → CorrectionEntity (overlay).
  :asr    whisper.cpp JNI boundary (M1a) + model file identity.
  :app    Compose UI + RecordingService (foreground, mic).
```

### The capture contract (requirement #1)

`RecordingService` owns the only `AudioRecord`. The capture thread does, in
order: (1) append PCM to the WAV via `WavFileWriter` — synchronously, disk
first; (2) offer the same buffer to the live transcriber (never blocks);
(3) update UI state. The WAV header is patched every ~5 s; if the process
dies, `WavRepair` reconstructs it from file length at next app start
(`SessionRepository.recoverInterrupted`), and interrupted sessions land in
STOPPED with their audio playable. Crash-losing at most the final unflushed
buffer (~0.3 s) is the accepted worst case.

### Session lifecycle

RECORDING → PAUSED → RECORDING … → STOPPED → TRANSCRIBING → TRANSCRIBED → REVIEWED.
Pause finalizes the current WAV; resume opens `seg<idx+1>.wav`. Gaps are real
wall-clock gaps. Stop (M1a+) triggers the final accurate pass under a
"Transcribing…" foreground notification.

## Milestones

- **M0 — Trustworthy recorder** *(this milestone)*: capture, pause/resume,
  crash recovery, session list/detail/playback, settings shell, CI APK.
- **M1a — Batch transcription**: JNI, model manager + downloader, final pass
  with word timestamps, word chips, tap-to-play, on-device benchmark screen.
- **M1b — Live transcript**: ring buffer, Silero-VAD gate, chunked decode,
  LocalAgreement commit, Full/Economy/Off. *Gate: >30 % deadline misses in
  Economy on FP4 → swap live engine to a streaming Zipformer (sherpa-onnx)
  behind `LiveTranscriber`, whisper stays the final-pass/fine-tune engine.*
- **M1c — Corrections + export**: word-edit sheet, span merge, mark-reviewed
  (accepted words = weak positives), SAF export bundle (schema v1).
- **M2 — Trainer**: validate → build_dataset → train_lora → merge_and_convert
  → evaluate; regression gate (personal WER must improve, generic WER must not
  degrade >1 pt); quantized model smoke-tested before it ships to the phone.
- **M3 — Polish + roadmap**: settings depth, backup/restore, correction stats.

## Risks being managed

1. SD750G too slow for live decode → coarse stride + VAD + auto-degrade;
   sherpa-onnx contingency behind the `LiveTranscriber` interface.
2. Fine-tune forgetting → LoRA-only, low LR, 30–50 % generic EN+FA mixin,
   hard eval gate, keep every adapter for rollback.
3. Conversion-chain breakage → vocab assert, shared submodule pin, smoke test
   converted model vs HF output on held-out clips.
4. Timestamp jitter → index-based taps, VAD-snapped clips.
5. 6 GB memory → single loaded ggml context (`ModelSlot`), live model released
   before final-pass model loads, 30 s-window streaming decode.
