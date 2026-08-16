# Drop inert configuration

## Dolby

`device.mk` carries `inherit-product-if-exists hardware/dolby/dolby.mk`. Frogger
ships zero Dolby files, so the inherit resolves to nothing.

## `spunvm`

`init/fstab.default` mounts `/dev/block/by-name/spunvm` at
`/mnt/vendor/spunvm`. Stock comments this mount out even though the partition
exists.

Mounting a partition the OEM chose not to mount is a deliberate decision rather
than an oversight. If first-stage mount fails, comment it out to match stock.

## Optical sensor permissions

`init/ueventd.qcom.rc` sets ownership on `ps_adc` and `ps_poll_delay` under
`/sys/devices/virtual/optical_sensors/proximity`. That directory does not exist:
the proximity part is driven from the ADSP by SEE and has no kernel driver. See
[proximity.md](../reference/proximity.md).

## `ro.boot.pbid` consumers

`nothing-fwk`'s `NtFeaturesUtils` reads
`ro.vendor.nothing.feature.diff.plus.<device>` and `ro.boot.pbid`. Frogger has
neither, so the path is inert.
