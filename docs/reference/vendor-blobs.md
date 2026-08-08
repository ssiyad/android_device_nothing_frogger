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
| camera | Vega face-detect models, Morpho and ArcSoft nodes |

Diff whole directories against stock rather than chasing one filename at a time.

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
