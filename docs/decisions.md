# Decisions and rationale

## 1. Frogger only, no FroggerPro

**Decision:** this tree targets the Phone (4a) alone.

The factory images ship SKUs EEA/IND/JPN/TUR/ROW and contain no Pro variant.
`ro.vendor.nothing.feature.diff.device.FroggerPro` exists in the feature masks,
so a Pro model exists, but nothing in the dump describes its panel, cameras or
NFC. Carrying Asteroids' Pro scaffolding forward would mean shipping config
copied from a different phone under a Pro name.

**To reverse:** re-add `sku/build_Pro*.prop`, the two Pro RRO overlays and their
`device.mk` package entries, then derive real values from a FroggerPro dump.

## 2. Props from the live device, not the factory images

The two sources disagree — the images are build `2603091830` (vendor SPL
2025-09-05), the phone runs `2606301839` (vendor SPL 2026-04-05). They also
disagree on `ro.media.xml_variant.codecs` (`_volcano_v1` in the older dump,
`_volcano_v0` live) and on the NT feature masks, which grew between builds.

**Decision:** the live device wins for every property, fingerprint and security
patch. The images are used only for file inventories, where the live device is
unusable because SELinux denies `shell` access to most of `/vendor`.

**Consequence:** the blob list is validated against a slightly older build than
the one blobs will be pulled from. Every entry was checked against the union of
both inventories, so an entry could in principle exist in the older build and
have been dropped in the newer one. Running `extract-files.py` against the phone
will surface any such case immediately.

## 3. NFC gated by permissions, not by vintf manifest

Stock gates NFC per SKU, and the IND reference unit genuinely has no NFC.
Reproducing that needs the framework feature to be absent on IND.

The obvious approach — per-SKU odm vintf manifests declaring
`android.hardware.nfc`, as stock does — **does not work here**. LineageOS builds
the HAL from `hardware/st/nfc`, whose soong module
`android.hardware.nfc-service.st` installs its own vintf fragment
(`nfc-service-default.xml`) declaring `INfc/default` unconditionally. Adding the
same HAL again in per-SKU manifests would double-declare it.

**Decision:** leave `ODM_MANIFEST_SKUS := JPN` (JPN alone needs extra HALs, for
the eSE), and gate NFC through the odm feature permission XMLs instead —
`android.hardware.nfc{,.hce,.hcef}.xml` installed to
`odm/etc/permissions/sku_{EEA,JPN,ROW,TUR}/`, with no `sku_IND`.

**Effect:** on IND the HAL is declared in VINTF but the framework never
advertises or starts NFC, which is the user-visible behaviour that matters. On
the other SKUs NFC works as before. The residual wart is a declared-but-idle HAL
on IND, which is a VTS nicety rather than a functional problem.

## 4. Display config taken from stock rather than hand-tuned

Asteroids ships a LineageOS-authored brightness curve. Writing the equivalent for
Frogger needs photometric measurements this bring-up does not have.

**Decision:** ship stock Frogger's own config for display
`4630947107087237506` verbatim. It is the OEM's calibration for this exact panel,
so it is correct even if less opinionated than Asteroids'.

Note this file is also delivered by the vendor blobs; the `PRODUCT_COPY_FILES`
entry deliberately overrides it, keeping the device tree the single source of
truth and preserving the hook for a future tuned curve.

## 5. `spunvm` left as stock has it

Stock Frogger's `fstab.default` has the `spunvm` mount **commented out** even
though the partition exists. Asteroids mounts it. The fstab is otherwise
byte-identical between the two.

**Decision:** not changed in this pass — the tree still carries Asteroids' fstab
with `spunvm` enabled. This is flagged in [open-items.md](open-items.md) rather
than silently "fixed", because mounting a partition the OEM chose not to mount is
the kind of change that should be made deliberately after seeing whether it
causes a first-stage mount failure.

## 6. Media profiles reduced to the v0 Base variant

Stock ships v0/v1 × Base/Pro. The live device reports `_volcano_v0`, and on
Frogger v0_Base and v1_Base are byte-identical. With no Pro in scope, only
`media_profiles_volcano_v0_Base.xml` is installed.

**Risk accepted:** if some blob opens the `_Pro` path unconditionally it will now
fail. Nothing in `vendor/etc/init` or `vendor/bin` references these filenames, so
selection appears to happen inside a closed-source component; if camera or
recording misbehaves, restoring `media_profiles_volcano_v0_Pro.xml` is the first
thing to try.

## 7. Blob list rebuilt by validation, not by regeneration

`proprietary-files.txt` is hand-maintained upstream, so it was adapted rather
than regenerated:

1. Built a complete Frogger inventory from the extracted factory images (8169
   paths) unioned with an `ls -R` listing of the live device (9264 paths).
   `ls -R` was needed because `find -type f` silently under-reports on an
   unrooted device — SELinux denies `stat`, so `/vendor/lib64` yielded 84 of
   1142 files, and `/vendor/firmware` was entirely invisible.
2. Dropped the 60 entries that exist on neither.
3. Added Frogger's counterparts in the categories that changed — camera sensor
   modules (`frogger_*` for `arcanine_*`), the AW882xx amplifier, and the ST54L
   NFC files.
4. Re-validated: all 1624 entries resolve.

Deliberately **not** done: adding every Frogger file absent from the list. Most
of the ~6500 unlisted files are built from source or genuinely unneeded, and
bulk-adding them would produce an unbuildable tree.

Both OIS modules (`com.qti.ois.dw9784.so`, `com.qti.ois.bu63169gwz.so`) are kept.
No camera config names them, so the provider evidently discovers them by scanning
the directory; keeping both matches stock.
