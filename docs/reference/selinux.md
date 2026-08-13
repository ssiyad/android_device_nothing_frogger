# SELinux

The device runs enforcing. How policy is decided: where a rule belongs, and
which denials are meant to stay denied.

## Checking the mode still holds

```sh
getenforce
cat /proc/bootconfig | grep selinux     # nothing: enforcing
ps -A -Z -o LABEL,NAME | grep unlabeled # nothing
```

Every process should sit in its own domain. A subsystem left in `zygote` points
at certificate matching in `plat_mac_permissions.xml` rather than a missing
`allow`. Any `unlabeled` process means something reloaded a policy recompiled
from the shipped CIL and stripped Magisk's types out — see
[selinux-collection.md](selinux-collection.md), and expect root to
break rather than merely look odd, now that nothing runs permissive.

Denials that matter carry `permissive=0`. Filtering on that separates real
blocks from the domains qcom declares permissive individually, such as
`qti-testscripts` on userdebug.

## Where a label comes from

| Filesystem | Labelled by |
|---|---|
| Real filesystems (`/vendor`, `/dev`, `/data`) | `file_contexts` |
| Virtual filesystems (`sysfs`, `proc`, `debugfs`) | `genfs_contexts` |

A `file_contexts` line for a `/sys` path is not useless, but it only takes
effect once init restorecons `/sys`, which is late. Anything that touches the
node before that sees the generic `sysfs` type, and the denial names `sysfs`
even though the node ends up correctly labelled minutes later. Check the live
label with `ls -Z` before treating such a denial as a labelling gap — early-boot
access needs `genfscon`, not `file_contexts`.

`genfscon` matches by prefix, so labelling a subtree to satisfy one directory
read relabels everything beneath it that is not more specifically labelled,
changing what other domains see. Where the goal is only to traverse a generic
parent, a narrow `dir`-only allow rule is the better trade;
`sepolicy/vendor/hal_nt_charger.te` is the worked example.

## Why not `audit2allow`

`audit2allow` writes the rule against the type in the denial, which for a
labelling gap is the generic one:

```
allow hal_nt_charger sysfs:dir { open read };
```

That grants access to all unlabelled sysfs. Where AOSP already permits the
correctly-labelled type — `system/sepolicy/private/system_server.te` carries
`r_dir_file(system_server, sysfs_extcon)`, for instance — labelling alone
resolves the denial and no `.te` change is needed.

## Denials that are meant to stay denied

The first three have a `.te` file in `sepolicy/vendor/` holding nothing but the
explanation, so the rule is not added by the next person to read the log. The
rest name no domain of ours and have nowhere to put such a file.

**The `O_CREAT` signature.** `system/sepolicy/private/domain.te` carries, for
every domain with no exception:

```
neverallow domain sysfs_type:dir
    { add_name create link remove_name rename reparent rmdir write };
```

`fopen(path, "w")` and a shell `> file` redirect both pass `O_CREAT|O_TRUNC`, so
the kernel runs the `create` and `add_name` checks against an existing file and
denies them. Nothing breaks: the write itself is never refused. It appears for
`hal_thermal_default` on thermal trip points, `vendor_nicmd` on `rps_cpus`,
`vendor_qti_init_shell` on `defrag`, `vendor_init` on `shutdown_wlan`, and
`vold` on `gc_urgent`.

**Sensitive identifiers.** `vendor_qccvendor` reads
`/sys/devices/soc0/serial_number`. `device/qcom/sepolicy_vndr` declares that
node's type and grants it to no domain at all; stock does not label the node and
never reaches the type.

**Priority requests.** `vendor_modprobe` asks for `sys_nice` and `setsched`.
Stock grants modprobe `sys_module` and nothing else while running enforcing, so
the failure costs boot time and nothing else.

**Writes where stock grants only reads.** `netutils_wrapper` writes
`/proc/sys/net/ipv4/ip_local_reserved_ports` through its `ip6tables` entry
point. Stock's plat policy carries
`allow netutils_wrapper proc_net_type:file { read getattr open }` and no write,
so the same call is denied there.

**Kernel thread capabilities.** `krfcommd` asks for `net_bind_service` in the
`kernel` domain during early boot. Stock grants `kernel` only `sys_nice` and
`sys_resource`, so this is denied on stock as well and Bluetooth works either
way.

**A vendor library reached from a coredomain.** `mediametrics` and `adbroot`
read, map and execute `/vendor/lib64/libutils.so` and `libbase.so`, landing on
`same_process_hal_file`. `system/sepolicy/private/domain.te` grants that type to
every domain **except** coredomain, "access is explicitly granted to individual
coredomains", because a coredomain loading the vendor copy of libutils would
hold two of them. The denial is a loader bug wherever it appears, not a policy
gap, and both sightings here accompany an `adb root` session running `dumpsys`.

**A service nothing registers.** `vendor_qtelephony` looks up
`nothing.radio.ntphone` and lands on `default_android_service` because no
`service_contexts` entry names it. Stock has no entry either, and `service list`
does not show it: the Nothing telephony app that would publish it does not ship
here. A label for a service that never appears buys nothing.

**Upstream `dontaudit` is an answer.** QTI ships `dontaudit` rules for
`vendor_hal_qspmhal_service` finds from `surfaceflinger`, `bootanim`,
`mediacodec`, `vendor_qtelephony` and `appdomain`. Stripping `dontaudit` for a
collection run surfaces them; the rule that was stripped is upstream saying the
probe is expected to fail. Grant nothing.

## Denials that are not the device's

- **Property enumeration.** `getattr`/`map`/`open`/`read` on
  `/dev/__properties__/u:object_r:*:s0` is the designed behaviour of bionic's
  `GetPropAreaForName()`, which opens the context file without a prior
  `access()` check specifically so that a non-permitted read generates an audit
  (`bionic/libc/system_properties/contexts_serialized.cpp`). The `foreach()`
  path does check first and is covered by `dontaudit`. The only rule that
  silences these is `get_prop(domain, property_type)`, which grants read on
  every property on the device. Filter, never allow.
- **Root.** Denials naming `srawcon="u:r:magisk:s0"`, `/debug_ramdisk/.magisk/`
  paths, or `scontext=u:object_r:unlabeled:s0` come from Magisk, as do domains
  reaching `/vendor/etc/*` through a `dev="dm-45"` bind mount.
- **adb.** `scontext=u:r:shell:s0` and `u:r:adbd:s0` denials come from the
  investigation, not from the device doing anything. `adbroot` likewise.
- **Apps.** `untrusted_app`, `isolated_app`, `gmscore_app` and friends probing
  `/proc`, `/sys` and `service_manager` are upstream's problem.

## Asking what stock does

The stock vendor policy ships as CIL and is readable directly:

```sh
extracted/vendor/etc/selinux/vendor_sepolicy.cil
extracted/vendor/etc/selinux/vendor_file_contexts
```

Both answer "does stock grant this" and "does stock label this node" in one
grep, and stock runs enforcing, so a denial stock also has is a denial that
costs nothing. This settled the SoC serial, modprobe's priority request and the
UFS `bsg` nodes — of which stock labels only `/dev/0:0:0:4` and
`/dev/0:0:0:49476`, leaving `qseecomd`'s probe of `/dev/0:0:0:0` denied there
too.

## Reading a surprising `scontext`

A process appearing in an unexpected domain is more often a domain-transition
bug than tooling interference. `seinfo` comes from certificate matching in
`plat_mac_permissions.xml`; a certificate mismatch leaves a whole subsystem in
the launching domain and its denials attributed there.
