# Devicetree

## Overlay model

`frogger-base-overlay.dts` is `/plugin/`, a true dtbo overlay. The merge script
assembles the base as `volcano.dtb` plus its platform dtbos, which exports 1330
symbols including `cam_cci0/1`, `cam_csiphy0-3`, `camcc`,
`cam_cc_camss_top_gdsc`, the `cam_sensor_*` pinctrl entries and the `pmxr2230`
flash/torch/switch labels. Overlays resolve these through `__fixups__`.

Consequences:

- **An overlay cannot delete a node from the base DTB.** `/delete-node/` entries
  are silent no-ops, so a base node survives alongside any replacement.
- **A replacement must not redefine the labels of the node it shadows.**
  Phandle references resolve to the last definition, so a stolen label points
  the original's users at the copy's nodes. A thermal zone that lost its trip
  labels this way failed every cooling-device bind with `-ENXIO`.
- The platform camera block must not be re-added to the overlay; a sensors-only
  include is correct.
- The merge script **globs `DTB_OBJ` for `*.dtbo`** rather than reading the
  makefile. Removing an overlay from the build leaves the stale `.dtbo` on disk
  and it keeps being merged. Delete stale artifacts before concluding a gate
  failed.

## Board-id collisions

Overlays are matched to a board by `qcom,board-id` and `qcom,oem-id`. Two
overlays claiming the same pair are both applied, and the failure is quiet:

```
ERROR: ufdt_overlay_do_fixups():Couldn't find '<label>' symbol in main dtb
ERROR: ufdt_overlay_apply():failed to perform fixups in overlay
```

`brunch` still exits 0. The message is not harmless — nodes from the unintended
overlay are merged despite it. Gate board-specific overlays behind
`CONFIG_NOTHING_IS_FROGGER`.

## Shared `qcom/` files carry other boards' hardware

Anything under `qcom/` that touches a board-level peripheral is suspect until
checked — speaker amplifier, display panel and camera sensor nodes have all been
found declaring the wrong part. Board choices belong in
`noth/<board>-common.dtsi`.

## Stock's overlay as ground truth

Stock `dtbo.img` holds 71 overlays.

```sh
ota_extractor --payload payload.bin --partitions dtbo --output_dir .
python3 system/libufdt/utils/src/mkdtboimg.py dump dtbo.img -b entry
for f in entry*; do dtc -I dtb -O dts $f 2>/dev/null | grep -m1 'qcom,board-id'; done
```

`entry.65` and `entry.66` both carry `qcom,board-id = <0x0b 0x00>` and
`qcom,oem-id = <0x01>` and are structurally identical. Disambiguate on
`qcom,msm-id`:

| Entry | `qcom,msm-id` |
|---|---|
| `entry.66` | `0x280`, `0x281`, `0x27c` — 640, 641, **636 (volcano)** |
| `entry.65` | `0x2c8` — 712 (volcanop) |

`entry.66` matches `frogger-base-overlay.dts`, which declares
`qcom,msm-id = <636 0x10000>`.

Nothing left QTI's `model = "Qualcomm Technologies, Inc. Volcano QRD"` string in
place, so match on board-id and oem-id, never on model name.

## Verifying a built overlay

Two dtbo paths exist. `out/…/obj/KERNEL_OBJ/…/*.dtbo` is a leftover from an older
build layout and is never regenerated. The live artifact is
`out/…/obj/DTB_OBJ/…/*.dtbo`, and `dtbo.img` is built from that. Decompiling the
stale copy shows changes as absent when they landed.

`mka dtboimage` does not rebuild the kernel devicetree from a `.dtsi` change on
its own — check the timestamp of the blob being inspected.

## Include dependencies

- `<dt-bindings/msm-camera.h>` ships in **camera-kernel**, not the kernel.
  `KBUILD_DTC_INCLUDE` in `TARGET_KERNEL_ADDITIONAL_FLAGS` makes dtc find it.
- `volcano-qrd.dtsi` does not pull in `qcom,camcc-volcano.h`. Include it directly,
  as QCOM's standalone camera `.dts` files do.
- `volcano-camera.dtsi` needs `GIC_SPI`, the gcc/camcc clock ids, interconnect ids
  and rpmh regulator levels.

## Comparing node lists

Strip **all** leading whitespace before comparing node names — two overlays nest
the same node at different depths, so comparing with tabs intact reports
identical nodes as missing. `tr -d ' ='` does not strip tabs; use
`sed -E 's/^[[:space:]]*//'`.
