# Ship the missing AAC effect library

`audio_effects.xml` declares a library the device does not have:

```xml
<library name="aac" path="libAACeffect_NT.so"/>
...
<effect name="aac" library="aac" uuid="ae737c63-f2c0-5457-909e-1e940c91b67b"/>
```

Stock has it at `vendor/lib64/soundfx/libAACeffect_NT.so`. It is absent from
`proprietary-files.txt`, so it is absent from the image. Every other library in
that file resolves; this is the only one that does not.

The effect is declared but not applied to any stream in `<postprocess>` or
`<preprocess>`, so the cost is a load failure at effect-factory init rather than
a missing feature. Confirm that before deciding: either add the blob, or drop
the `<library>` and `<effect>` entries as inert config.

Do not conflate this with capture level — it was found while chasing the low
recording volume and is unrelated to it. See
[lvacfs-source-tracking.md](lvacfs-source-tracking.md).

## Check

```sh
adb shell 'find /vendor/lib64 /odm/lib64 -name libAACeffect_NT.so'
```
