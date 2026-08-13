# Pass Play Integrity DEVICE, and retire Magisk

Banking needs `MEETS_DEVICE_INTEGRITY`. `MEETS_BASIC_INTEGRITY` alone is not
enough, and `MEETS_STRONG_INTEGRITY` is out of scope — it needs a hardware TEE
keybox this build cannot supply.

## Why props alone do not reach it

DEVICE_INTEGRITY is evaluated on the keystore attestation certificate chain, not
on `Build` fields; Google moved it onto that path in 2024-2025. Editing
`ro.product.*` or the fingerprint feeds only the software evaluation, which
reaches BASIC. Forging the attestation needs a kernel-level keystore hook, which
needs root — so DEVICE and a fully rootless device are mutually exclusive here.
Magisk is being replaced by KernelSU, not removed: the root stays, the patched
boot image and separate manager app go away.

## The stack

| Layer | Reaches | Where it lives |
|---|---|---|
| KernelSU-Next, compiled in | root | kernel, in-tree |
| ReZygisk | Zygisk for KSU | device-side module |
| PlayIntegrityFix | BASIC (fingerprint) | device-side module |
| TrickyStore + keybox | DEVICE (attestation) | device-side data |

Only the kernel row is a build change. The modules and the keybox rotate
constantly, so they are flashed on the device, not baked into the ROM — the same
reasoning that keeps runtime tweaks out of the tree.

## In-tree work: KernelSU-Next into the 6.6 GKI kernel

`kernel/nothing/sm7635` is 6.6.114 GKI, our fork. `gki_defconfig` already sets
`CONFIG_KPROBES` and `CONFIG_KALLSYMS_ALL`, so the kprobe hook path needs no
kernel source patch — only the driver import and `CONFIG_KSU=y`. That is the
reason to start on kprobe hooks: no patched syscalls, no rebase burden.

- KernelSU-Next is carried as a git submodule at `kernel/nothing/sm7635/
  KernelSU-Next`, pinned to the `v3.3.0` commit on upstream
  `KernelSU-Next/KernelSU-Next`. `setup.sh` symlinks `drivers/kernelsu` into it
  and wires `drivers/Kconfig` and `drivers/Makefile`. The kernel project in the
  local manifest carries `sync-s="true"` so the server fetches the submodule.
  Pinning to the SHA removes the force-sync drift risk that the fork-everything
  rule exists for, so upstream is pointed at directly. Fork under `ssiyad/` only
  when SUSFS lands: its manual syscall hooks patch KSU source, which needs a
  repo we can push to.
- The submodule must stay its own git repo, not vendored files: `Kbuild`
  derives `KSU_VERSION = 30000 + git rev-list --count` only when KernelSU-Next
  is a separate git root from the kernel. At `v3.3.0` that is `33214`, which the
  v3.3.0 manager expects. Flatten it into the kernel tree and the version falls
  back to `1`/`v0.0.1` and the manager rejects the kernel.
- `vendor/ksu.config` carries `CONFIG_KSU=y` and is added to
  `TARGET_KERNEL_CONFIG` in `BoardConfig.mk`. This KSU version is kprobe-only —
  `config KSU depends on KPROBES && EXT4_FS`, both already set — so there is no
  hook-select symbol and no source patch.
- `bootimage` build to confirm KSU compiles into `Image.gz`, then a full brunch.

TrickyStore fabricates the whole attestation chain, including a locked-bootloader
and green verified-boot report, so the bootloader can stay unlocked and this is
independent of [Sign vbmeta and decide on re-locking](avb-verified-boot.md).

## SUSFS is deferred

SUSFS hides the mounts and files an app's own root detection scans for, separate
from Play Integrity. It needs manual syscall hooks — patches to `fs/exec.c`,
`fs/open.c`, `fs/read_write.c`, `fs/stat.c`, `kernel/reboot.c` — and a matching
module. Add it only if the bank's own detection defeats the kprobe build; do not
pay the patch cost up front.

## The keybox is the recurring cost

TrickyStore's keybox private key must chain to Google's attestation root and be
unrevoked. Leaked keyboxes circulate and are revoked in batches, so DEVICE drops
until a fresh one is sourced. This is maintenance, not a one-time setup.
