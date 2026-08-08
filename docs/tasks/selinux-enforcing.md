# Switch SELinux to enforcing

Remove `androidboot.selinux=permissive` from `BOARD_BOOTCONFIG` in
`BoardConfig.mk` and resolve what breaks.

## Order

Labelling and allow-rule work comes first: permissive hides domain-transition
bugs completely, because nothing is blocked and only the attribution of denials
is wrong.

`SELINUX_IGNORE_NEVERALLOWS` is a separate switch and is already `false`. A
neverallow violation means the policy is wrong rather than incomplete, so it is
checked at build time and independent of the runtime mode.

## Verification

```sh
getenforce
ps -A -Z -o LABEL,NAME | grep -v '^u:r:untrusted_app'
```

Every process should sit in its own domain. A subsystem left in `zygote` points
at certificate matching in `plat_mac_permissions.xml` rather than a missing
`allow`.

## Root interaction

Magisk patches the live policy at boot to add its own domain. Any mechanism that
reloads a policy recompiled from the shipped CIL removes those types and leaves
`magiskd` and `su` unlabeled, which under enforcing breaks root and presents as
an enforcing bug. See [collect SELinux denials](selinux-denial-collection.md).
