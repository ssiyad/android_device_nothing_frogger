# Write missing allow rules

Denials whose target type is already correct need a rule in
`sepolicy/vendor/<domain>.te`.

## Outstanding

| Domain | Target | Access |
|---|---|---|
| `vendor_qccvendor` | `vendor_sysfs_soc_sensitive` | `/sys/devices/soc0/serial_number` |
| `hal_fingerprint_default` | `default_prop`, `overlay_prop` | `persist.vendor.overlay.fp_serial` |
| `mediametrics` | `same_process_hal_file` | `/vendor/lib64/libutils.so` |
| `vendor_qtelephony` | `default_android_service` | `nothing.radio.ntphone` |

## Denials that must stay denied

`system/sepolicy/private/domain.te` carries, for every domain with no exception:

```
neverallow domain sysfs_type:dir
    { add_name create link remove_name rename reparent rmdir write };
```

Three denials match that shape and are benign probing rather than missing
permission:

| Domain | Node |
|---|---|
| `hal_thermal_default` | `trip_point_1_temp`, `trip_point_1_hyst` |
| `vendor_nicmd` | `rps_cpus` |
| `vendor_qti_init_shell` | `defrag` |

`create` on an existing file is the **`O_CREAT` signature**: `fopen(path, "w")`
and a shell `> file` redirect both pass `O_CREAT|O_TRUNC`, so the kernel runs the
`create` and `add_name` checks even though nothing is created. The device has 72
thermal zones and only 54 expose `trip_point_1_temp`; the HAL walks all of them.

Nothing breaks by leaving these denied — the nodes that exist are written
normally, and only `create` is ever refused, never `file write`. The three `.te`
files are kept containing this explanation so the rules are not re-added.

## Upstream, not device policy

Leave alone: `cgroup_v2` creates by `init`/`system_server`/`zygote`,
`{ noatsecure }`, `netd` on `proc_net`, `dex2oat` searching app data, `kernel`
capabilities, and app-level noise from `untrusted_app`, `isolated_app` and
`gmscore_app`.
