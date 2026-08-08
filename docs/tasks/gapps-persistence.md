# Make GApps survive a ROM flash

A payload OTA rewrites `system`, `product` and `system_ext` whole. `addon.d` is
the mechanism that puts add-on packages back.

`POSTINSTALL_PATH_system` points at `backuptool_postinstall.sh`, which is what
runs the `addon.d` scripts. MindTheGapps installs `/system/addon.d/30-gapps.sh`
with `ADDOND_VERSION=3`, a standard `list_files()` and no `/sdcard` paths.

## Suspected limitation

`backuptool_ab.sh` does `export S=/system` and reads `/system/addon.d/`. During a
recovery sideload there is no running system — the incoming image is mounted at
`/postinstall` — so `preserve_addon_d()` most likely finds nothing. If that
holds, `addon.d` restores only for OTAs applied from a running system and never
for a sideload.

Confirming it requires `/data/misc/recovery/last_log`.

## Trade already accepted

`otapreopt_script` no longer runs after an OTA, so the first boot after an update
is slower. Official LineageOS A/B devices make the same trade.
