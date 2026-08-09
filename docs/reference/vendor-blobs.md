# Vendor blobs

`proprietary-files.txt` is hand-maintained. Full list of files present in the
stock image and absent here: [data/vendor-missing.txt](../data/vendor-missing.txt).

## Compare against the device, not the blob tree

Diffing `vendor/nothing/frogger/proprietary` reports files that ship from the
device tree instead, which inflates the count without meaning. Compare a running
image against the stock dump.

## Recurring failure mode

A HAL built from source, missing a device-specific data file that stock ships:

| Subsystem | File |
|---|---|
| audio | LVACFS profile mapping |
| audio | `aw882xx_pid_2329_monitor.bin` — absent from stock too |
| display | QDCM panel calibration |

Diff whole directories against stock rather than chasing one filename at a time.

**Camera is exhausted.** Every camera blob shipped here is byte-identical to
stock — 85 libraries in `vendor/lib64`, 161 in `vendor/lib64/camera/`, and all of
`vendor/etc/camera/`. What stock has and this build does not is
`camera/node/com.nothing.node.filtereditor.so` with the three colour-LUT
directories under `etc/camera/filter/` it reads, the ArcSoft and Morpho libraries
the Nothing camera app links, and the AIDL/NDK interface libraries LineageOS
builds from source. All belong to the Nothing camera app and are unreachable from
Aperture or GCam. A missing camera blob is no longer a live hypothesis for a
camera fault.

**`grep -r` silently misses matches inside these blobs** — they contain NUL bytes
and this grep prints nothing at all for a binary match without `-a`. A negative
result from a non-`-a` grep over `extracted/` or `proprietary/` is worthless.

## Groups deliberately not shipped

```
richtapresources/        RichTap haptic effects for Nothing UI events
customringtone/          Nothing ringtones
init/                    rc files for services not run here
whisper/                 OpenCC Chinese conversion data
res/images/charger/      stock charger animation
camera/                  stock camera app colour filters
vintf/                   manifests this build generates
permissions/             LineageOS ships its own
nt_performance/          read by components not shipped
audio/sku_volcano_qssi/  an unused sku directory
```

## Inventory method

An unrooted device under-reports with `find -type f` — SELinux denies `stat`, so
`/vendor/lib64` yields 84 of 1142 files and `/vendor/firmware` is entirely
invisible. Use `ls -R`, or work from the extracted factory images.

Adding every unlisted file is not the goal. Most of the ~6500 unlisted files are
built from source or genuinely unneeded, and bulk-adding them produces an
unbuildable tree.

## `blob_fixup` scoping

`blob_fixups` is keyed by path and applies to one blob. `lib_fixups` is keyed by
library **name** and applies globally, so removing a `shared_libs` entry there
alters every blob that links it. Prefer the path-keyed mechanism when breaking a
dependency edge for a single blob.

`fix_soname()` handles a `DT_SONAME` that does not match the filename.

## Reference sources

| Source | Use |
|---|---|
| Factory images, build `2603091830` | file inventories and blob extraction |
| OEM kernel source | driver families and Kconfig names |

Blobs and the fingerprint both describe `2603091830`, so the shipped image
advertises the build whose vendor files it contains.
