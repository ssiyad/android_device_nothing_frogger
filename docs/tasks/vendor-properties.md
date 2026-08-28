# Evaluate the remaining vendor property groups

The built `vendor/build.prop` carries 212 properties against stock's 451. The
gap and stock's values are listed in
[data/vendor-props-missing.txt](../data/vendor-props-missing.txt), which is
generated — regenerate it before trusting it, since a property adopted since the
last run still shows as missing.

Treat that file as a lead list, never a patch.

## Find the reader first

```sh
grep -rl "<prop>" --include=*.c --include=*.cpp --include=*.h --include=*.rc \
    hardware/ vendor/nothing/ device/ frameworks/
strings -a <shipped blob> | grep <prop>
```

Every group examined so far has been inert: the consumers are Nothing's
proprietary framework and HAL components this tree does not ship, or legacy QCOM
HALs for other SoCs it does not build. Copying such a property achieves nothing
except the appearance of configuration.

| Group | Count | Consumer |
|---|---|---|
| audio | 81 | legacy `audio_extn` HAL; this tree builds the PAL/AGM stack |
| display | 28 | `nt-services.jar`, not shipped |
| glyph | 20 | Nothing's Glyph framework, not shipped |
| `ro.vendor.nothing.*` | 65 | settled, adopt none — see below |
| sys, sf, newAAC, qcom, product | 17 | unexamined |
| bt | 7 | settled, adopt none — see below |
| `ro.product.*_for_attestation` | 3 | inert twice over: stock's values are empty, and `SystemProperties.get` returns the default for an empty value, so `Build.getVendorDeviceIdProperty` falls through to `ro.product.vendor.*` exactly as it does when they are absent |
| `persist.sys.zram*` | 7 | inert: the only readers in the tree are goldfish and cuttlefish. Frogger's zram comes from `swapon_all /odm/etc/fstab.zram` in `init.frogger.rc`, which consults none of them |

Split any adoption into one commit per subsystem, so a regression bisects to a
group rather than to a single large change.

## `ro.vendor.nothing.feature.*` is unreachable by construction

All 65 are `feature.*`, and they are the masks for the whole Nothing lineup —
Asteroids, Contra, Galaga, Metroid, Pacman, Pong, Spacewar, Tetris, and the
Frogger and FroggerPro families with their regional SKUs.

`NtFeaturesUtils` builds the property name from this device's own identity:

```java
"ro.vendor.nothing.feature.diff.device."  + Build.DEVICE    // Frogger
"ro.vendor.nothing.feature.diff.product." + Build.PRODUCT   // FroggerIND
```

So another device's entry cannot be looked up here however carefully it is
copied. Only three names are reachable on a given unit, and this tree already
sets all of them — `feature.base` and `diff.device.Frogger` in `odm.prop`,
`diff.product.Frogger<SKU>` in each `sku/build_<SKU>.prop`. They appear in the
generated list only because they are set from odm rather than from vendor.

`FroggerPro` is a separate `Build.DEVICE`, not a variant reachable from this
build, and these trees build Frogger and nothing else.

Worth recording alongside it: `diff.plus.*` exists for **Asteroids only**. That
is the `ro.boot.pbid` path, and its absence for Frogger is why that branch came
out of `NtFeaturesUtils`.

## The Bluetooth group: our name is the one that is read

Stock declares the A2DP offload capability under two names this build does not
set, `persist.bluetooth.a2dp_offload.cap` and `persist.vendor.bt.a2dp_offload_cap`,
which reads like a gap. It is the opposite.

The consumer is `vendor.qti.hardware.btconfigstore@{1,2}.0-impl.so`, both of
which this tree ships, and both read a third name —
`persist.vendor.qcom.bluetooth.a2dp_offload_cap` — which `vendor.prop` sets to a
superset of stock's codec list, `aptxadaptiver2` included. Neither of stock's two
names appears in any stock vendor blob.

`persist.bluetooth.a2dp_offload.cap` does have a source-side constant,
`Property::kA2dpOffloadCap` in the Bluetooth HAL's `hal_types.h`. It is declared
and never used: the declaration is the only occurrence in the tree.

The five codec-control properties beside it —
`persist.vendor.bt.aac_frm_ctl.enabled`, `aac_vbr_frm_ctl.enabled`,
`persist.vendor.qcom.bluetooth.aac_vbr_ctl.enabled`,
`dualmode_transport_support` and `lossless_aptx_adaptive_le.enabled` — have no
reader in any shipped blob either.

## Values that differ and are deliberate

| Property | Reason to keep |
|---|---|
| `vendor.display.enable_rounded_corner=1` | Read by the display HAL (`include/display_properties.h`). Stock pairs `0` with `enable_ic_hw_roundedcorner`, which this HAL does not read, so matching stock removes rounded corners with no hardware fallback |
| `ro.vendor.build.version.sdk=36` | Vendor is legitimately built against an older API under Treble |
| `ro.product.first_api_level=35` | See [build-config.md](../reference/build-config.md) |
| `ro.bionic.cpu_variant`, `dalvik.vm.isa.arm64.variant` | `cortex-a76` against stock's `kryo300`. Checked against the silicon and settled: see [build-config.md](../reference/build-config.md). Stock's value costs ART four instruction-set features the cores have |
