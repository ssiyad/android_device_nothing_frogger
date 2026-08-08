# Sign vbmeta and decide on re-locking

The build ships `BOARD_AVB_MAKE_VBMETA_IMAGE_ARGS += --flags 3`, which disables
verification entirely.

With the signing keys in `vendor/lineage-priv/keys/` the vbmeta chain can be
signed properly. If the bootloader accepts a custom AVB key, re-locking then
yields yellow verified boot instead of orange.

## Scope

- Drop `--flags 3` and sign `vbmeta` with the release key.
- Confirm the bootloader accepts a custom AVB key before re-locking. A device
  that rejects it and is already locked cannot boot an unsigned image.

## What this does not buy

Neither `MEETS_STRONG_INTEGRITY` nor `MEETS_DEVICE_INTEGRITY` becomes reachable:
the first needs hardware key attestation, the second a Google-signed OS, and
yellow verified boot is not green. See
[Play Integrity verdicts](https://developer.android.com/google/play/integrity/verdicts).
