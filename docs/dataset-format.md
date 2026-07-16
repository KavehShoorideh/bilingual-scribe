# Dataset export format — schema v1 (normative)

The contract between the phone app and `trainer/`. Changes are append-only
within a major version; the trainer refuses unknown majors.

## Bundle layout

```
scribe-export-<ISO8601>/
├── bundle.json
├── manifest.jsonl
└── audio/<session_id>/<clip_id>.wav     # 16 kHz mono PCM16
```

The user picks the destination folder once (e.g. a Syncthing share). Nothing
is uploaded anywhere by the app.

## bundle.json

```json
{
  "schema_version": 1,
  "bundle_id": "uuid",
  "created_at": "2026-07-16T10:30:00Z",
  "app_version": "0.3.0 (14)",
  "device": {"model": "FP4", "os": "e_2.9-a14"},
  "asr": {
    "engine": "whisper.cpp",
    "engine_version": "v1.9.1",
    "final_model_id": "ggml-base-q5_1.bin:sha256:ab12cd34ef56"
  },
  "consent": {
    "scope": "personal-training-only",
    "share_opt_in": false,
    "share_terms_version": null,
    "speaker_pseudonym": "spk_9f3a"
  },
  "counts": {"clips": 214, "corrected": 61, "accepted": 153, "audio_seconds": 1830.5}
}
```

The consent block exists from v1 so the future shared-model path is a field
flip plus real consent UI — not a format migration. `speaker_pseudonym` is a
stable random ID, never derived from PII.

## manifest.jsonl — one record per clip

```json
{
  "clip_id": "uuid",
  "audio": "audio/<session_id>/<clip_id>.wav",
  "sample_rate": 16000,
  "duration_s": 7.42,
  "text": "remind me to call مامان tomorrow evening",
  "original_text": "remind me to call ma mon tomorrow evening",
  "label_quality": "corrected",
  "corrections": [
    {"char_span_in_text": [18, 23], "original": "ma mon", "corrected": "مامان"}
  ],
  "language_hint": "mixed",
  "session_id": "uuid",
  "recording_id": "uuid",
  "source_span_ms": [124300, 131720],
  "context_padding_ms": [1500, 1500],
  "created_at": "2026-07-16T10:30:00Z",
  "asr_model_id": "ggml-base-q5_1.bin:sha256:ab12cd34ef56",
  "review_state": "reviewed"
}
```

Field notes:

- `text` is the **full clip transcript with all overlapping corrections
  applied** — Whisper is seq2seq over the whole clip, so the target must be
  the whole clip's text, never just the fixed word.
- `label_quality`: `"corrected"` (user fixed something in this clip) or
  `"accepted"` (clip from a session marked *reviewed*, untouched — a weak
  positive).
- `language_hint`: `"en" | "fa" | "mixed" | "und"` from the session.
- `source_span_ms` locates the clip inside its source segment WAV, before
  padding.

## Clip construction rules

1. A clip is **never just the corrected word**: expand the correction's audio
   span outward to the enclosing VAD utterance boundaries, then add 1.5 s
   padding each side.
2. Clamp duration to 3–28 s (Whisper trains on ≤30 s windows).
3. From **reviewed** sessions, all utterances export: corrected regions as
   `corrected`, untouched utterances as `accepted`. From unreviewed sessions,
   only corrected clips export.
4. Clips overlapping the same audio region are deduped; `corrected` wins.
5. Deleted-word corrections (empty `correctedText`) still produce clips —
   silence/noise mislabeled as speech is valuable training signal.
