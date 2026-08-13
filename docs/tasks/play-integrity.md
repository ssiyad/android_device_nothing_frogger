# Pass Play Integrity DEVICE

Root via KernelSU-Next ships and is confirmed on-device, which retires Magisk.
How the whole stack works — root, the manager, the attestation layers, the keybox
— is in [reference/play-integrity.md](../reference/play-integrity.md). What remains
is making `MEETS_DEVICE_INTEGRITY` actually pass.

- Flash the attestation stack through the manager, rebooting after the provider:
  ReZygisk, then PlayIntegrityFix, then TrickyStore.
- Source a valid, unrevoked keybox, place it at
  `/data/adb/tricky_store/keybox.xml`, and set `target.txt` for the banking app.
- Confirm the verdict shows BASIC and DEVICE both present, then that the bank
  itself works.

The keybox is the gate and the recurring cost: no legitimate individual source,
and revocation drops DEVICE until it is refreshed. If the bank runs its own root
detection that survives this, escalate to SUSFS — see the reference.
