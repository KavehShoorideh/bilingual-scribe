# Privacy roadmap — improving the shared model without sharing voices

Out of scope until M3 ships. Design notes so earlier decisions don't paint us
into a corner.

## Invariants already in place

- All audio, transcripts, and corrections stay on the phone unless the user
  exports them to a folder they control.
- The export format (schema v1) carries a consent block from day one:
  `scope: personal-training-only`, `share_opt_in: false`, and a pseudonymous
  speaker ID with no PII derivation. Flipping to a sharing mode is a consent
  UI + field change, not a migration.
- Every transcript word records the exact model that produced it, so any
  future aggregate can be audited per-contribution.

## Candidate mechanisms (to evaluate at M3+)

1. **Adapter sharing, not data sharing**: users contribute LoRA adapter
   deltas trained locally, never audio or text. A coordinator averages
   adapters (federated averaging). Risk: adapters can memorize; needs
   clipping + noise (DP-style) and a minimum-cohort rule before any merge.
2. **Differentially private aggregation**: add calibrated noise to adapter
   updates; publish the privacy budget. Cost: needs many contributors before
   the aggregate beats a good generic Farsi/EN fine-tune.
3. **Curated opt-in corpus**: users explicitly flag individual clips as
   donatable (per-clip consent in the export UI), reviewed client-side for
   incidental third-party speech before leaving the device.

Honest assessment: (1)+(2) are research-grade; (3) is buildable with today's
schema and probably the right first step — but only after the single-user
loop (M2) demonstrably works.

## Non-goals

- No telemetry, ever, including "anonymous usage stats".
- No server component operated by this project that receives audio.
