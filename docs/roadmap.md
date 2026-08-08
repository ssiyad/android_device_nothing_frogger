# Roadmap

Current order, agreed 2026-08-08: **finish SELinux, then camera.** Glyph is last
and deliberately not being worked on.

| # | Workstream | State |
|---|---|---|
| 1 | **SELinux enforcing** | **in progress** — collection done, policy being written. See [selinux.md](selinux.md) |
| 2 | **Camera** | next — parked since 2026-08-05. See [camera.md](camera.md) |
| 3 | Glyph LEDs | last priority, not started. Policy is now correct; the 20 `ro.vendor.glyph.*` props are almost certainly inert |
| — | Signing and keys | done and verified |
| — | Magisk / Zygisk | Magisk 30.7 running; PlayIntegrityFork downloaded, never enabled |
| — | GApps survive a flash | **unproven** — reflash MindTheGapps after every ROM sideload |
| — | Screen flicker | **fixed** — non-seamless 30Hz DFPS entry, devicetrees `726bba1c` |
| — | Speaker protection | closed dead end, see [audio.md](audio.md) |

## Where SELinux actually stands

Collection is **finished** and the data is trustworthy for the first time — both
the Bluetooth domain bug and the stale-policy bug that corrupted earlier sets are
fixed.

```
still permissive     androidboot.selinux=permissive   BoardConfig.mk:141
neverallows ignored  SELINUX_IGNORE_NEVERALLOWS       BoardConfig.mk:273
2701 denials         dontaudit stripped, recompiled each boot
   2243 (83%)        two processes walking /dev/__properties__
    111              device-relevant
      2              fixed and verified on device
```

Remaining, in the order they should be done:

1. **Labelling gaps** — `genfs_contexts` entries, no new allow rules. UFS
   `nr_tags` for `init`, `/sys/block` and `sda2` for `vold`, `read_ahead_kb`.
   `/sys/devices/virtual` for `hal_nt_charger` needs thought: it is a very large
   subtree and must not be labelled wholesale to satisfy one directory read.
2. **Missing allow rules** — target type already correct, so `.te` work:
   `vendor_nicmd`, `netutils_wrapper`, `vendor_qccvendor`,
   `hal_fingerprint_default`, `mediametrics`, `vendor_qtelephony`.
3. **Decide on the property enumeration.** 2243 of 2701 denials are `zygote` and
   `hal_camera_default` doing `getattr`/`map`/`open` across ~200 property types.
   That is one behaviour, not 2243 problems. Understand why before writing a rule
   that broadly grants property access.
4. **Drop `SELINUX_IGNORE_NEVERALLOWS`.** Separate from flipping enforcing and
   usually harder: a neverallow violation means the policy is *wrong*, not merely
   incomplete.
5. **Flip to enforcing** and fix what breaks.

Do not write `hal_thermal_default → sysfs_thermal` rules yet. It asks for
`add_name`/`create` on sysfs, which is impossible, so it more likely indicates a
missing thermal node than a policy gap.

Camera work will add a large denial surface and force some of this to be
revisited — which is an argument for getting to enforcing on the current surface
first, not for delaying camera.

---

## Reference: GApps and a ROM flash

**Wired up, but not proven to work.** `POSTINSTALL_PATH_system` points at
`backuptool_postinstall.sh` (`09c8a6d`), and MindTheGapps installs
`/system/addon.d/30-gapps.sh` with `ADDOND_VERSION=3` and a normal `list_files()`
covering `product/priv-app/GmsCore`, `system_ext/priv-app/GoogleServicesFramework`
and the rest. It references only `/tmp/backuptool.functions` — no `/sdcard`
paths, which is what made NikGApps fail here.

**The one flash where GApps were not reflashed, addon.d did not restore them.**
The system copies were gone, `30-gapps.sh` itself was gone, and GMS was left as
an ordinary `/data/app` package with no `SYSTEM`/`UPDATED_SYSTEM_APP` flag and no
privileged permissions. Reflashing MindTheGapps restored all of it.

**Working hypothesis for the failure, unconfirmed:** `backuptool_ab.sh` does
`export S=/system` and reads `/system/addon.d/`. During a **recovery sideload**
there is no running system — the new image is at `/postinstall` — so
`preserve_addon_d()` likely finds nothing. If that is right, addon.d only works
for OTAs applied from a running system, and never for the sideload flow we use.
Confirming it needs `/data/misc/recovery/last_log`, which requires root.

**Until proven otherwise, assume every ROM flash needs GApps reflashed.**
Remember the A/B slot rule: sideload ROM, **reboot recovery**, then sideload
GApps. See [open-items.md](open-items.md).

---

## Reference: signing and keys

**Verified on device 2026-08-05:**

```
ro.build.tags        release-keys
ro.build.fingerprint …/2603091830:user/release-keys
```

Before this, `ro.build.tags` read `test-keys` while every fingerprint claimed
`release-keys` — the build was signed with AOSP's public test keys, which are by
definition known to everyone. That contradiction is gone, and platform apps are
signed with our platform key.

**No factory reset was needed.** The plan assumed re-signing every APK would
break system app data and force a wipe. It did not — the device was flashed
without wiping and reported zero signature-mismatch errors. Worth remembering
before planning a wipe around a signing change again.

**Nine RSA-2048 keys**, generated 2026-08-05 — `releasekey`, `platform`,
`shared`, `media`, `networkstack`, `nfc`, `sdk_sandbox`, `bluetooth`, `testkey`
— no passphrase, subject `/CN=frogger/O=ssiyad/emailAddress=hello@ssiyad.com`,
valid to 2053. Private key verified to match cert for all nine.

The ninth, `testkey`, is not vestigial paranoia: `system/sepolicy/private/keys.conf`
has a `[@RELEASE]` stanza requiring `$DEFAULT_SYSTEM_DEV_CERTIFICATE/testkey.x509.pem`,
and without it `//system/sepolicy/mac_permissions` fails the build outright. It
is deliberately a *different* key from `releasekey`; nothing is signed with it.

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

`vendor/lineage-priv` is now a checkout of that private repo rather than an
untracked directory, so the keys survive a machine loss and stay identical on
both machines.

**Backed up 2026-08-08** to the private repo `ssiyad/android-private-keys`,
cloned to `vendor/lineage-priv/` on both machines. Verified private before the
push (unauthenticated api.github.com and github.com both returned 404). The
`.pk8` files have no passphrase, so that repo being private is the only
protection: if it is ever made public, regenerate all nine.

**Still open:** AVB. We currently build with
`BOARD_AVB_MAKE_VBMETA_IMAGE_ARGS += --flags 3`, which disables verification
entirely. With our own keys we could sign `vbmeta` properly and, if the
bootloader allows a custom AVB key, re-lock to get yellow verified boot instead
of orange. That is a genuine security improvement. **It does not buy Play
Integrity** — see below.

---

## Reference: Magisk, Zygisk, Play Integrity

**Unblocked** — signing is done, so a patched boot image will not be invalidated
out from under it. Both Magisk and KernelSU patch or replace boot artefacts, and
doing that before signing would have meant redoing it.

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

**One thing in our favour:** the build reports a real stock fingerprint, so much
of what a spoofing module does is already true, and the `test-keys` contradiction
is gone since item 1.

That was only half true until 2026-08-05. The fingerprint read
`Frogger:14/UKQ1.250915.001/…` — Android 14 build IDs on an Android 16 system
reporting SDK 36 and a 2026-07 patch level, and a fingerprint no Nothing build
has ever had. It was inherited from the Asteroids tree and never corrected.
Verified against the stock `B4.1-260309-1830` release the blobs come from and
fixed to:

```
Nothing/Frogger/Frogger:16/BQ2A.250913.001-BP2A.250605.031.A3/2603091830:user/release-keys
```

**Do not "modernise" this value.** It has to match a real Nothing build Google
knows about. An invented-but-consistent fingerprint is worse than a stale one.

**Expect banking apps and Google Wallet to keep failing.** Plan around that
rather than chasing it.

---

## Where things stand

```
SELinux    in progress -- collection done, policy being written, still permissive
Camera     next
Glyph      last, deliberately parked
```

Everything else is either done, closed as a dead end, or a standing tax
(reflash GApps and re-patch Magisk after every ROM flash).
