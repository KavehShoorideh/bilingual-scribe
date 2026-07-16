# On-device benchmarks — Fairphone 4 (SD750G), /e/OS

Filled from the benchmark screen (M1a) and manual runs. These numbers pick
the default models and live-mode parameters; until measured, the plan runs on
estimates (tiny-q5_1 RTF 0.3–0.5, base 0.6–1.0, small 1.5–2.5 @ 4 threads).

## Batch decode (final pass)

| Model | Threads | Audio len | Wall time | RTF | Peak RSS | Notes |
|---|---|---|---|---|---|---|
| ggml-tiny-q5_1 | 4 | — | — | — | — | |
| ggml-base-q5_1 | 4 | — | — | — | — | |
| ggml-small-q5_1 | 4 | — | — | — | — | |

## Live mode (M1b)

| Mode | Window/stride | Deadline miss % (10 min speech) | Battery %/10 min | Thermal |
|---|---|---|---|---|
| Full | 8 s / 3.5 s | — | — | — |
| Economy | 6 s / 5 s | — | — | — |
| Off (capture only) | — | n/a | — | — |

## Recorder baseline (M0)

| Scenario | Battery %/10 min | Notes |
|---|---|---|
| Recording, screen on | — | |
| Recording, screen off | — | |
