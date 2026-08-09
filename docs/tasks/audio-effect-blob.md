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
`<preprocess>`, so it is never instantiated and the cost is a load failure at
effect-factory init rather than a missing feature. Either add the blob, or drop
the `<library>` and `<effect>` entries as inert config.

Not related to capture level. That was the analog PGA, ahead of the ADC, while
effects run last in AudioFlinger; and the paths that were quiet include
`AUDIO_SOURCE_MIC`, which has no `<preprocess>` entry and so carries no effect at
all.

## The question actually worth answering

Does the failed load take the rest of the config with it? If it does, the
`voice_communication` preprocess — `aec` and `ns` from `libqcomvoiceprocessing` —
is silently absent, which would show up as echo and background noise on calls,
never as level. The parser is meant to skip a bad library and continue, and the
effects factory does come up, so probably not. Unconfirmed: it needs boot logs,
and the default log buffer rolls them within about 100 seconds.

## Check

```sh
adb shell 'find /vendor/lib64 /odm/lib64 -name libAACeffect_NT.so'

# for the parse question, before rebooting:
adb shell su -c 'setprop persist.logd.size 4M'
# then after boot
adb logcat -d -b all | grep -iE 'effectsConfig|libAACeffect|skipped'
```
