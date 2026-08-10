# SELinux

How policy for this device is decided: where a rule belongs, and which denials
are meant to stay denied.

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

Each of these has a `.te` file in `sepolicy/vendor/` holding nothing but the
explanation, so the rule is not added by the next person to read the log.

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
