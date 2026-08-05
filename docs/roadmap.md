# Roadmap

Planned work after camera was sidelined (2026-08-05). Ordered by dependency, not
by appeal — the sequencing matters more than usual here because one item forces a
factory reset and another is passive data collection that should start now.

| # | Workstream | State |
|---|---|---|
| 0 | GApps survive a flash | implemented, awaiting one test |
| 1 | Signing and keys | **keys generated**, first signed build pending |
| 2 | Magisk / Zygisk / Play Integrity | not started |
| 3 | SELinux enforcing | collection should start now |

---

## 0. GApps survive a ROM flash

**Done, needs one confirmation.** `POSTINSTALL_PATH_system` points at
`backuptool_postinstall.sh` (`09c8a6d`), and MindTheGapps installs
`/system/addon.d/30-gapps.sh` with `ADDOND_VERSION=3` and a normal `list_files()`
covering `product/priv-app/GmsCore`, `system_ext/priv-app/GoogleServicesFramework`
and the rest. It references only `/tmp/backuptool.functions` — no `/sdcard`
paths, which is what made NikGApps fail here.

**Test:** flash a ROM and do *not* reflash GApps. Play Store should survive.

Remember the A/B slot rule when flashing GApps at all — sideload ROM, **reboot
recovery**, then sideload GApps. See [open-items.md](open-items.md).

---

## 1. Signing and keys — do this first

**Why first:** re-signing changes every APK signature, so system app data no
longer matches and a **factory reset is required**. Every day of daily driving
before this is state you will lose. It also invalidates any patched boot image,
so doing it after Magisk means redoing Magisk.

**Current state is incoherent:**

```
ro.build.tags        test-keys
ro.build.fingerprint …/2603091830:user/release-keys
```

Every fingerprint claims `release-keys` while the build is signed with AOSP's
public test keys — which are, by definition, known to everyone.

**Keys generated 2026-08-05.** Eight RSA-2048 keys — `releasekey`, `platform`,
`shared`, `media`, `networkstack`, `nfc`, `sdk_sandbox`, `bluetooth` — no
passphrase, subject `/CN=frogger/O=ssiyad/emailAddress=hello@ssiyad.com`, valid
to 2053. Private key verified to match cert for all eight.

**The location is load-bearing.** `build/make/core/config.mk:1364` decides
`BUILD_KEYS` from *where* `DEFAULT_SYSTEM_DEV_CERTIFICATE` points:

```
build/make/target/product/security/testkey  -> test-keys
vendor/lineage-priv/%                       -> release-keys
anything else                               -> dev-keys
```

Keys in the commonly-suggested `~/.android-certs` would build as **dev-keys**,
not release-keys. They live in `vendor/lineage-priv/keys/` on both the laptop and
the build server, wired by `vendor/lineage/config/common.mk:304`
(`-include vendor/lineage-priv/keys/keys.mk`) with no device-tree change.

`vendor/lineage-priv` is inside no git repo and is not a repo project, so the
keys cannot be committed or pushed by accident. **They are not backed up by
anything automatic** — no passphrase means the files are the entire secret.

**Remaining:**

1. Build with them. Every APK is re-signed.
2. **Factory reset**, flash, set the device up again.
3. Confirm `ro.build.tags=release-keys` and that the fingerprint no longer
   contradicts it.

**Also worth deciding at the same time:** AVB. We currently build with
`BOARD_AVB_MAKE_VBMETA_IMAGE_ARGS += --flags 3`, which disables verification
entirely. With our own keys we could sign `vbmeta` properly and, if the
bootloader allows a custom AVB key, re-lock to get yellow verified boot instead
of orange. That is a genuine security improvement. **It does not buy Play
Integrity** — see below.

---

## 2. Magisk, Zygisk, Play Integrity

**Do after signing.** Both Magisk and KernelSU patch or replace boot artefacts;
re-signing invalidates them.

### Root

| Option | Fit here |
|---|---|
| **Magisk** | Patches `init_boot.img` on Android 13+. Re-patch after every ROM flash. Required if you want **Zygisk**, which most integrity modules depend on |
| **KernelSU** | Root in the kernel, no boot patching, and we already build the kernel from source — so it survives builds without manual steps. Needs ZygiskNext for Zygisk-style modules |
| **APatch** | Kernel-based but patches a prebuilt kernel; little advantage when we compile our own |

**Recommendation:** Magisk, specifically because Zygisk is the ecosystem the
integrity modules target. KernelSU is the more elegant fit for our build setup
and worth revisiting if root is wanted *without* the integrity work.

### Play Integrity — be clear about the ceiling

| Verdict | Requires | Achievable |
|---|---|---|
| `MEETS_BASIC_INTEGRITY` | Play-installed app, plausible device | yes |
| `MEETS_DEVICE_INTEGRITY` | locked bootloader, green verified boot, Google-signed OS | only by spoofing, and eroding |
| `MEETS_STRONG_INTEGRITY` | hardware key attestation, TEE cert chain | **no** |

`STRONG` is rooted in keys fused into the SoC and signed by Google or the OEM.
Nothing in userspace fabricates it. Modules claiming otherwise ship **leaked
keyboxes** from real devices, which Google revokes in batches — it works until it
abruptly does not, and it is not something to build a daily driver around.

`DEVICE` is where PlayIntegrityFix-style modules operate, spoofing fingerprint
and props so the server-side check sees a plausible stock device. Google has been
moving `DEVICE` onto hardware attestation too, so treat this as a maintenance
burden rather than a fix.

**One thing in our favour:** the build already reports a real stock fingerprint
(`Nothing/Frogger/Frogger:14/UKQ1.250915.001/2603091830:user/release-keys`), so
much of what a spoofing module does is already true. After item 1 the `test-keys`
contradiction goes away too, which removes an obvious tell.

**Expect banking apps and Google Wallet to keep failing.** Plan around that
rather than chasing it.

---

## 3. SELinux enforcing — start collecting now

**Passive work that should begin immediately**, because the only way to learn the
real denial set is to run the device permissive and harvest.

**Current bring-up state:**

```
androidboot.selinux=permissive     BOARD_BOOTCONFIG
SELINUX_IGNORE_NEVERALLOWS := true BoardConfig.mk
```

**Two traps, both already bitten once in the numbers quoted so far:**

- A boot showed **5 denials**, and later **0**. Neither is the real count.
  Permissive only logs paths that actually ran, and camera, NFC, Bluetooth and
  GPS were untouched in those boots.
- **`dontaudit` suppresses logging even in permissive.** Any count taken without
  stripping those rules understates the problem. Rebuild policy with `dontaudit`
  removed before trusting a number.

**Phases:**

1. **Collect.** Harvest denials continuously while daily driving; a small script
   appending deduplicated `avc: denied` lines to a file, since logcat rotates.
2. **Widen.** Rebuild policy without `dontaudit` and collect again. Exercise
   every subsystem deliberately — calls, Bluetooth, GPS, NFC, camera when it
   returns.
3. **Write policy.** Most denials are **labelling** bugs, not missing `allow`
   rules. `audit2allow` output should never be pasted verbatim: given a HAL
   denied access to an `unlabeled` sysfs node it emits
   `allow hal_x unlabeled:file rw`, which grants access to *everything*
   unlabeled. The right fix is usually one `genfs_contexts` line. Virtual
   filesystems cannot be labelled by `file_contexts`.
4. **Drop `SELINUX_IGNORE_NEVERALLOWS`.** Separate from flipping enforcing and
   often harder — a neverallow violation means the policy is wrong, not merely
   incomplete.
5. **Flip to enforcing** and fix what breaks.

**Known already:** `hal_nt_charger` denied `open`/`read` on
`/sys/devices/virtual`, and `init` denied `open` on a UFS `nr_tags` sysfs node.

**Camera being sidelined helps here** — it is a large denial surface that will
not move under us while policy is written. But policy written now must be
revisited when camera returns.

---

## Ordering rationale

```
0. GApps test        free, next flash
1. Signing + keys    forces a wipe — do before accumulating state
2. Magisk / PIF      after signing, or it must be redone
3. SELinux           collection starts now, flip whenever ready
```

SELinux collection runs in parallel with everything else; it costs nothing and
the data only accumulates.
