# Label sysfs nodes

Denials with `tcontext=u:object_r:sysfs:s0` and a specific path are labelling
gaps, not missing permissions. The fix is a `genfs_contexts` entry in
`sepolicy/vendor/`, with no new allow rule.

Virtual filesystems cannot be labelled from `file_contexts`.

## Why not `audit2allow`

`audit2allow` on a `sysfs` denial emits a rule against the generic type:

```
allow hal_nt_charger sysfs:dir { open read };
```

That grants access to **all** unlabeled sysfs. Where AOSP already permits the
correctly-labelled type — for example
`system/sepolicy/private/system_server.te` carrying
`r_dir_file(system_server, sysfs_extcon)` — labelling alone resolves the denial
and no `.te` change is needed.

## Outstanding paths

| Path | Domain | Access |
|---|---|---|
| `/sys/devices/platform/soc/1d84000.ufshc/.../mq/0/nr_tags` | `init` | open |
| `/sys/block`, `sda2`, `read_ahead_kb`, `gc_urgent` | `vold`, `vendor_qti_init_shell` | open |

## When labelling is the wrong tool

`genfscon` matches by prefix, so labelling a large subtree to satisfy one
directory read relabels everything beneath it that is not more specifically
labelled, changing what other domains see. A narrow `dir`-only allow rule is the
better trade there; `sepolicy/vendor/hal_nt_charger.te` is the worked example.
