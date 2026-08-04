# Frogger bring-up documentation

This directory records how `device/nothing/frogger` was derived from
`device/nothing/asteroids` (Nothing Phone (3a)), and why each change was made.

| Document | Contents |
|---|---|
| [hardware-facts.md](hardware-facts.md) | Every device-specific value used, and how it was measured |
| [changes.md](changes.md) | Per-file change log |
| [decisions.md](decisions.md) | Judgement calls, with rationale and how to reverse them |
| [open-items.md](open-items.md) | Unverified assumptions and external work still required |
- [camera.md](camera.md) — camera bring-up: what was fixed, what remains, how to diagnose it
| [build-server.md](build-server.md) | Where and how builds run, and how blobs are regenerated |

## Reference sources

Three sources were used, in this order of authority:

1. **Live device over ADB** — a Frogger IND unit, build `2606301839`
   (Nothing OS 4.1, Android 16, vendor SPL 2026-04-05). Authoritative for
   properties, display geometry, sysfs layout and framework state.
2. **Factory images** at `~/sources/android/downloads/firmwares/frogger/`,
   build `2603091830`. Used for file inventories, because the live device is
   unrooted and SELinux denies `shell` access to most of `/vendor`.
   Extracted to `.../frogger/extracted/` with `fsck.erofs --extract`.
3. **OEM kernel source** at `~/sources/android/kernels/frogger/`. Used to
   confirm driver families and Kconfig names.

Where the two builds disagree the live device wins; the one case where it
mattered is noted in [decisions.md](decisions.md).

## Important caveat

The reference unit is an **IND** SKU, which has **no NFC hardware**. Anything
NFC-related in this tree is derived from stock configuration files rather than
observed behaviour. See [open-items.md](open-items.md).
