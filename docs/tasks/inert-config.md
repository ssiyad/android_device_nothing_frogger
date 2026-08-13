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

## `ro.boot.pbid` consumers

`nothing-fwk`'s `NtFeaturesUtils` reads
`ro.vendor.nothing.feature.diff.plus.<device>` and `ro.boot.pbid`. Frogger has
neither, so the path is inert.
