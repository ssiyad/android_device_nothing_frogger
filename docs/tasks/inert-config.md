# Drop inert configuration

## `spunvm` is the one that was not inert

It mounted. `/dev/block/sde75` was on `/mnt/vendor/spunvm` as vfat, 32 MB, and
vold picked the partition up as **portable storage** for fourteen seconds on
2026-08-05 — `dumpsys mount` still carries the record, `type=PUBLIC
fsUuid=627A-A42B`, created 19:26:28 and last seen 19:26:42 — long enough for
Android to create `Android/data/com.spotify.music` on it. Empty directories on a
vendor partition, and nothing since.

It is commented out now, which is what stock does: the same line appears in both
of stock's fstabs, live in `fstab.emmc` and commented in `fstab.default`. Frogger
boots from UFS and reads `fstab.default`, so stock does not mount it on this
device. No SPU or spss service runs here to want it.

The general point is worth keeping. The task started from "stock comments this
out, so match stock" and the interesting part turned out to be *why* the mount
was not harmless — a formattable vfat partition that nothing owns is exactly the
shape vold goes looking for.
