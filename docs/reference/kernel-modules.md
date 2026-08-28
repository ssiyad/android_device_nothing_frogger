# Kernel modules

## The load lists are honest

Every module named in `modules.load.recovery`, `modules.load.system_dlkm`,
`modules.load.vendor_boot` and `modules.load.vendor_dlkm` is produced by this
kernel — 418 distinct names against 442 built, nothing listed that is not built.

Check it against a real build rather than against stock:

```sh
find out/target/product/frogger/obj/PACKAGING/kernel_modules_intermediates \
    -name '*.ko' -printf '%f\n' | sort -u
```

A comparison against **stock's** module set is misleading, and was the basis for
an earlier belief that 29 entries needed dropping. Those 29 are modules this
kernel builds and stock's does not — stock compiles them in or does not enable
them. Loading a module this kernel produces is not an error because another
kernel handles it differently.

## Built and not loaded

Twenty-four modules are built and appear in no load list. Twenty are kunit test
modules (`kunit*.ko`, `*_test.ko`, `regmap-kunit.ko`) and belong nowhere near a
load list. The rest are `cctrng.ko`, `ipatestm.ko`, `open-dice.ko`,
`qseecom_dlkm.ko`, `video.ko` and `lpass_bt_swr_dlkm.ko`.

Only `lpass_bt_swr_dlkm.ko` is loaded by stock, which makes it the only one worth
a second look. It does not belong here either:

```
lpass-bt-swr ...lpass_bt_swr@31E0000: lpass_bt_swr_probe: swr_gpios handle not provided!
lpass-bt-swr: probe of ...lpass_bt_swr@31E0000 failed with error -22
```

The module inserts cleanly and links against `swr_ctrl_dlkm`, `wcd_core_dlkm`,
`spf_core_dlkm` and `snd_event_dlkm`, but the devicetree node has no
`qcom,bt-swr-gpios`. That property, the SoundWire master port map and the
pinctrl node all live in `volcano-audio-bt-swr.dtsi`, which is included only by
boards carrying a **wcn6450**-class part. Frogger's Bluetooth SoC is `moselle`
(`persist.vendor.qcom.bluetooth.soc`), so the fragment does not apply and neither
does the module.

Stock's `modules.load` is a superset spanning board variants — it lists this
module six times over. Matching it here would load a driver whose probe fails.
