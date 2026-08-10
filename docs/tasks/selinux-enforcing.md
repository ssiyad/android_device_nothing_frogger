# Switch SELinux to enforcing

Remove `androidboot.selinux=permissive` from `BOARD_BOOTCONFIG` in
`BoardConfig.mk` and resolve what breaks.

## Before flipping it

Take a clean collection first — see
[collect SELinux denials](selinux-denial-collection.md). Permissive hides
domain-transition bugs completely, because nothing is blocked and only the
attribution of denials is wrong, so the reading has to come from the build that
carries the current policy rather than from an older accumulated log.

`SELINUX_IGNORE_NEVERALLOWS` is a separate switch and is already `false`. A
neverallow violation means the policy is wrong rather than incomplete, so it is
checked at build time and independent of the runtime mode. `sepolicy-build.sh`
on the build server runs that check in a few minutes.

## Verification

```sh
getenforce
ps -A -Z -o LABEL,NAME | grep -v '^u:r:untrusted_app'
```

Every process should sit in its own domain. A subsystem left in `zygote` points
at certificate matching in `plat_mac_permissions.xml` rather than a missing
`allow`.

Exercise the paths whose denials were deliberately left in place, since those
are the ones an enforcing kernel will actually block:
[selinux.md](../reference/selinux.md) lists them. Thermal mitigation, network
config, module loading, the vibrator and the fingerprint sensor all touch one.

## Root interaction

Magisk patches the live policy at boot to add its own domain. Any mechanism that
reloads a policy recompiled from the shipped CIL removes those types and leaves
`magiskd` and `su` unlabeled, which under enforcing breaks root and presents as
an enforcing bug.
