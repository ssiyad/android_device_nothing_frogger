# Build configuration

Values in `BoardConfig.mk` and `lineage_frogger.mk` that read like bring-up
leftovers but are load-bearing, and the reasoning that keeps them from being
tidied away.

## API levels

`BOARD_API_LEVEL_PROP_OVERRIDE := 34` reads as a hack because the build warns it
is test-only, but 34 is what stock reports for `ro.board.api_level` and
`ro.board.first_api_level`. The vendor partition was built at API 34 while the
system is Android 16. Removing it lets the build derive 36 and misdescribe the
vendor image, and it is why the vendor and odm fingerprints legitimately read
`:14/`.

`PRODUCT_SHIPPING_API_LEVEL` stays at **35**. It is a compliance switch
selecting which launch requirements the build must meet, not a property. At 36
it enables the 16 KB page-size check, which rejects `adpl` and `ATFWD-daemon` on
a `CONFIG_ARM64_4K_PAGES` kernel, and makes `host_init_verifier` fatal on stock
init scripts that declare no user, such as `vendor.nicmd`. Stock reporting 36 is
not a reason to claim it.

## Privileged permissions are a boot requirement, not a grant

A package in `priv-app` that requests a permission at `signature|privileged`
must appear in a `etc/permissions` allowlist, or
`PermissionManagerService.onSystemReady()` throws and takes `system_server` with
it. The device then loops on the boot logo, and the only clue is one line in the
crash buffer naming the package and permission.

**Being platform-signed does not exempt it.** Signing decides whether the
permission can be granted; the allowlist is a separate consistency check over
what a privileged package is allowed to ask for, and it fires either way.

Two permissions have cost a boot here, and neither was needed, because the
service behind each exempts the system uid before it ever looks at a permission:

| Permission | Level | Why it was unnecessary |
|---|---|---|
| `SCHEDULE_EXACT_ALARM` | `signature\|privileged\|appop` | `UserHandle.isCore` exempts the caller from the exact-alarm check |
| `MEDIA_CONTENT_CONTROL` | `signature\|privileged` | `MediaSessionService.hasMediaControlPermission` returns early on `uid == Process.SYSTEM_UID` |

Both look like ordinary permissions at the call site, which is what makes this
recur. So the rule is to check `protectionLevel` in
`frameworks/base/core/res/AndroidManifest.xml` before adding any permission to a
privileged package, then read the service's own enforcement — an app running as
`android.uid.system` is usually already past it — and to prefer dropping the
permission over adding an allowlist entry for one that buys nothing.

Dropping `privileged: true` would sidestep the check entirely, but it is not
available to `Glyph`: `sepolicy/private/seapp_contexts` matches it on
`isPrivApp=true` to put it in the `glyph_app` domain, and without that domain it
loses the `sysfs_leds` access the strip depends on.

The failure is quiet in the worst way. `PermissionManagerService.onSystemReady()`
throws, `system_server` dies, and the device loops on the boot animation while
`adb` still reports it as `device` — the one line naming the package and the
permission is in `logcat -b crash`, not in any build output.

## A kernel config change does not rebuild the kernel

`TARGET_KERNEL_CONFIG` is a list of defconfig fragments, and editing it — adding
one, removing one, changing what one contains — leaves
`out/target/product/frogger/obj/KERNEL_OBJ` alone. The fragments are merged onto
the `.config` that is already there, so a symbol that was set stays set even when
the fragment that set it is gone, and `arch/arm64/boot/Image` is not recompiled
at all.

The result is a full build that succeeds, produces a boot image, and carries the
*old* kernel. Nothing warns. `/proc/version` on the device shows a build
timestamp older than the ROM, which is the only tell.

```sh
rm -rf out/target/product/frogger/obj/KERNEL_OBJ
```

Verify against `KERNEL_OBJ/.config` and `KERNEL_OBJ/arch/arm64/boot/Image`, not
`out/target/product/frogger/kernel` — that one is regenerated on every build
whether or not the kernel was rebuilt, so it looks fresh either way. `strings`
on it proves nothing either, because it is compressed.

## SELinux

The runtime mode is bootconfig, and a permissive build is one that *adds*
`androidboot.selinux=permissive` to `BOARD_BOOTCONFIG`. Enforcing is the absence
of the line, so there is nothing to grep for when checking which mode a tree is
in — check `/proc/bootconfig` on the device instead.

`SELINUX_IGNORE_NEVERALLOWS := false` is independent of the runtime mode. It
makes neverallow violations fail the build, which is where they belong: a
violation means the policy is wrong rather than incomplete.

## If `WITH_ADB_INSECURE` is ever needed again

It also leaves `ro.debuggable=1`, by skipping
`PRODUCT_NOT_DEBUGGABLE_IN_USERDEBUG`.

It belongs in `lineage_frogger.mk` above the `vendor/lineage` inherit; nowhere
else works, because board config is evaluated after product config.

Do **not** set `ro.adb.secure=0` directly — `vendor/lineage/config/common.mk`
already assigns it and Soong fails on duplicate sysprop assignments.

## Out-of-tree patches

`patches/` holds changes this device needs in projects it does not own, with
`patches/apply.sh` to put them back. It currently holds none; the machinery is
kept because the next one is easier than rebuilding it.

**`apply.sh` must run after every `repo sync` and before every build.** `repo
sync` runs with the force flag and resets those projects, so the patches come off
each time; the script re-applies them, treats an already-patched project as
success, and exits non-zero the moment anything fails. Nothing may build past a
non-zero exit — an unpatched tree produces an image that looks correct and
quietly lacks the change.

A patch is preferred to forking the project when the change is small. A fork of a
repository that moves every month costs a rebase in perpetuity, and the change
stops being reviewable in this tree. The trade is that upstream movement turns
into a failed apply and a stopped build, which is the right way round.
