# Enable Glyph LEDs

The `PRODUCT_PACKAGES` block and the
`packages/apps/{ParanoidGlyph,GlyphAdapter}` soong namespaces are commented out
in `device.mk`.

## Blocker

`ParanoidGlyphPhone4a` does not exist upstream. Frogger's Glyph is 6 channels in
a 6×1 layout driven by `CONFIG_LEDS_AW20036_FROGGER`, so it needs a new target
rather than a rename of an existing one.

NullDebris publishes `packages_apps_ParanoidGlyph` (`lineage-23.1`) and
`packages_apps_GlyphAdapter` (`15`).

## Configuration

The 20 `ro.vendor.glyph.*` properties — channel and segment counts, per-group
brightness tables, music-visualiser limits — are absent from `vendor.prop`. Their
consumer is Nothing's framework, which this tree does not ship, so confirm a
reader exists before adding them. See
[evaluate vendor properties](vendor-properties.md).

## Policy

The `glyph_app` domain ships, and `dev_color` on the `aw20036_led` node is
labelled in `sepolicy/vendor/genfs_contexts` with `hal_light_default` allowed
`sysfs_leds`.
