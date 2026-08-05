# Roadmap

Planned work after camera was sidelined (2026-08-05). Signing is done; Magisk is
next, and SELinux collection should run in parallel with it.

| # | Workstream | State |
|---|---|---|
| 0 | GApps survive a flash | **unproven** — see below |
| 1 | Signing and keys | **done and verified on device** |
| 2 | Magisk / Zygisk / Play Integrity | Magisk 30.7 installed; PIF not enabled |
| 3 | SELinux enforcing | collecting — see [selinux.md](selinux.md) |

---

## 0. GApps survive a ROM flash

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

## 1. Signing and keys — done and verified

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

`vendor/lineage-priv` is inside no git repo and is not a repo project, so the
keys cannot be committed or pushed by accident. **They are not backed up by
anything automatic** — no passphrase means the files are the entire secret.

**Remaining:** back the nine keys up somewhere off both the laptop and the build
server. Losing them means no future build can be installed over an existing one
without a factory reset.

**Still open:** AVB. We currently build with
`BOARD_AVB_MAKE_VBMETA_IMAGE_ARGS += --flags 3`, which disables verification
entirely. With our own keys we could sign `vbmeta` properly and, if the
bootloader allows a custom AVB key, re-lock to get yellow verified boot instead
of orange. That is a genuine security improvement. **It does not buy Play
Integrity** — see below.

---

## 2. Magisk, Zygisk, Play Integrity

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

## 3. SELinux enforcing — see [selinux.md](selinux.md)

Collection is running. `selinux.md` holds the detail; the short version:

- A denial **collector** runs from Magisk `service.d` and accumulates a
  deduplicated set across reboots.
- **Magisk pollutes the data.** Zygisk attributes roughly a quarter of denials to
  `zygote` that belong elsewhere. Filter before running anything over the log.
- Collection found a real bug — **`com.android.bluetooth` runs in the `zygote`
  domain**, because our Bluetooth signing key did not match the certificate in
  `plat_mac_permissions.xml`. Fixed in `keys.mk`, not yet built. Permissive hid
  it entirely.
- **NFC is not applicable** — Frogger has no NFC hardware.

Still to do: exercise GPS, telephony, camera, tethering and USB; rebuild with
`dontaudit` stripped; then write policy by hand, `genfs_contexts` first.

---

## Where things stand

```
0. GApps             addon.d unproven — reflash GApps after every ROM sideload
1. Signing + keys    done, verified on device, no wipe required
2. Magisk / PIF      Magisk 30.7 rooted and verified; PIFork downloaded, not enabled
3. SELinux           collector running; Bluetooth domain bug found and fixed
```

SELinux collection runs in parallel with everything else; it costs nothing and
the data only accumulates.
