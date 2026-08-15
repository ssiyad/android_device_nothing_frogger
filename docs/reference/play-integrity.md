# Play Integrity and root

How this device roots, and what it takes to pass Play Integrity. The kernel side
ships and is confirmed on-device; the device-side attestation stack is flashed,
not built.

## Verdict tiers, and what reaches them

- **BASIC** is the software evaluation over `Build` fields. A spoofed fingerprint
  feeds it.
- **DEVICE** is evaluated on the keystore attestation certificate chain, not on
  `Build` fields — Google moved it there in 2024-2025. Forging that chain needs a
  kernel-level keystore hook, which needs root, so props alone cannot reach it and
  a fully rootless device cannot either.
- **STRONG** additionally needs a hardware-grade keybox and a recent patch level
  reported through TrickyStore. It is reachable by the same keybox path when the
  keybox is hardware-backed, not by anything in the build, and it leans hardest on
  keybox freshness. It was not the goal here — DEVICE is enough for banking.

## Root: KernelSU-Next built into the kernel

Root is compiled into the kernel, so it rides in the boot image: a ROM flash
keeps it and there is no boot-patch step. Kernel string is
`6.6.114-android15-8-4k-g<kernel-sha>`.

- The driver is vendored as ordinary files at
  `kernel/nothing/sm7635/drivers/kernelsu`, from KernelSU-Next `v3.3.0`
  (upstream `3b18216f`). `drivers/Kconfig` sources its `Kconfig` and
  `drivers/Makefile` has `obj-$(CONFIG_KSU) += kernelsu/`. `repo sync` therefore
  produces a complete tree with nothing to run afterwards.
- **The version is pinned in `drivers/kernelsu/Kbuild`, and has to be.** Upstream
  computes `KSU_VERSION = 30000 + git rev-list --count HEAD` and takes the tag
  from `git describe`, but only when the driver's git root differs from the
  kernel's — a guard that exists because vendoring breaks it. Vendored, that test
  fails and the build falls back to version `1` / tag `v0.0.1`, which the manager
  rejects without saying why. The pin is `33214` / `v3.3.0`, matching the manager
  apk, and a KernelSU bump means editing it.
- **`CONFIG_KSU` defaults to `n`**, changed from upstream's `y`. With the upstream
  default, `vendor/ksu.config` looked like the switch while controlling nothing:
  removing the fragment left KernelSU compiled in anyway. Off by default makes
  the fragment the only thing that enables it, so taking it out is a real test.
- `vendor/ksu.config` carries `CONFIG_KSU=y` and is added to
  `TARGET_KERNEL_CONFIG` in `BoardConfig.mk`. This version is kprobe-only —
  `config KSU depends on KPROBES && EXT4_FS`, both already set in `gki_defconfig`
  — so there is no hook-select symbol and no kernel source patch.
- Changing `TARGET_KERNEL_CONFIG` does **not** rebuild the kernel on its own; see
  [build-config.md](build-config.md).

## The manager

Modules install through the KernelSU-Next manager, not recovery, and the manager
holds the root-grant allowlist. The kernel trusts only the manager whose APK
signature matches `KSU_NEXT_MANAGER_HASH`, left at the upstream default, so the
official KernelSU-Next manager is the only one accepted — matched to the kernel at
version `33214`. Use the standard release APK, not the `-spoofed` variant, which
carries a different signature the kernel will not recognise.

## The device-side attestation stack

Flashed through the manager, in order, rebooting after the provider: **ReZygisk**
(the FOSS Zygisk provider; Zygisk Next went closed), then **PlayIntegrityFix** (a
Zygisk module, so ReZygisk must be present first; config is `pif.prop`), then
**TrickyStore**. These live on `/data/adb`, surviving a dirty flash and lost only
to a userdata wipe. Third-party module names rotate; the roles are what is
durable.

TrickyStore decides DEVICE. Its config in `/data/adb/tricky_store/` is read live:
`keybox.xml` is the attestation key, `target.txt` lists the apps (bare package
auto-detects, `?` forces leaf-hack, `!` forces generate), and `security_patch.txt`
optionally reports a patch level. Leaf-hack edits the certificate the TEE returns
and is the mode for a working TEE like Frogger's; generate fabricates the chain
only when the TEE is broken, so it is no substitute for a real keybox here.

TrickyStore fabricates the whole chain, including a locked-bootloader and
green-verified-boot report, so the bootloader can stay unlocked and DEVICE is
independent of [signing vbmeta and re-locking](../tasks/avb-verified-boot.md).

## The keybox is the recurring cost

TrickyStore's keybox private key must chain to Google's attestation root and be
unrevoked. There is no legitimate individual source — real keyboxes are
hardware-provisioned to OEMs. Leaked ones circulate and are revoked in batches, so
DEVICE drops until a fresh one is sourced. Check a keybox against Google's
attestation CRL (`android.googleapis.com/attestation/status`) before relying on
it; a revoked cert fails DEVICE silently. The AOSP test keybox is public but
blocklisted, useful only to prove the pipeline intercepts keystore.

## SUSFS, if a bank's own detection defeats this

SUSFS hides the mounts and files an app's own root detection scans for, separate
from Play Integrity. It needs manual syscall hooks — patches to `fs/exec.c`,
`fs/open.c`, `fs/read_write.c`, `fs/stat.c`, `kernel/reboot.c` — and a matching
module, and so a fork of KernelSU-Next under `ssiyad/` to carry them. Reach for it
only if the plain kprobe build is defeated by app-side detection.
