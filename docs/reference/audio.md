# Audio

## Layout

| | |
|---|---|
| Card | `volcano-qrd-wsa883x-snd-card` — the name is inherited from a QCOM template and does not describe this hardware |
| Codec | WCD9378 over SoundWire |
| Speaker amps | 2× Awinic AW882xx, I2C `13-0034` / `13-0035`, primary MI2S |
| Speaker backend | `MI2S-LPAIF-RX-PRIMARY` (PCM `00-21`) |
| VI feedback backend | `MI2S-LPAIF-TX-PRIMARY` (PCM `00-22`), configured but unused |
| PAL | built from source, `hardware/qcom-caf/sm8650/audio/pal` |

There is no WSA883x. Only the WCD9378 is on SoundWire:

```
/sys/bus/soundwire/devices/  →  swr1  swr2  wcd9378-slave.…  wcd9378-slave.…
```

The `vi-feedback` mixer path drives `WSA_AIF_VI Mixer WSA_SPKR_VI_2` and
`WSA_RX1 Digital Volume`, controls for an amp this device does not have. Stock
has the same apparent mismatch.

## LVACFS mic processing

LVACFS is Nothing's Goodix mic-processing library. It selects a tuning profile by
`DeviceId`, and those IDs are AOSP `audio_source_t` values.

**The AP-side path is off, as on stock.** `record_use_ap_lvacfs` is the only key
that sets `ResourceManager::isLvacfsEnabled`, which gates `Lvacfs::init()` and
every per-stream call. Stock's `resourcemanager_volcano_qrd.xml` does not carry
it; it arrived here from the Asteroids port and was removed to match stock. The
profile the HAL picks comes from a stale `source_` on a reused stream object, so
the profile is not the one the app asked for — see
[tasks/lvacfs-source-tracking.md](../tasks/lvacfs-source-tracking.md). This is
*not* why capture is quiet; that is
[tasks/capture-gain-deficit.md](../tasks/capture-gain-deficit.md).
The paragraphs below describe the config as stock ships it, and apply if that
path is ever turned back on.

Stock ships **no** `DeviceId 1` entry in the 1-mic or 2-mic configs, so a plain
`AUDIO_SOURCE_MIC` recording matches no profile and LVACFS leaves it alone. The
3-mic config is the exception and does ship a genuine source-1 profile,
`LVIMFS_Parameter_xxxx_ID1_MIC_HDR_standard.txt`.

Remapping a profile onto source 1 hands `AUDIO_SOURCE_MIC` a parameter set
intended for another source and garbles capture. Recordings from
`AUDIO_SOURCE_MIC` are meant to get no LVACFS processing.
`VOICE_COMMUNICATION` (7), `VOICE_RECOGNITION` (6) and `UNPROCESSED` (9) match
genuine stock profiles.

## Effects

`audio_effects.xml` installs to `etc/audio/sku_volcano/`. The effects HAL is
HIDL (`android.hardware.audio.effect@7.0-impl`), so the loader is
`frameworks/av/media/libeffects`, which reads `audio_effects.xml` — not the AIDL
factory, which reads `audio_effects_config.xml` and is not used here.

**A library that fails to load costs only its own entry.** `loadLibraries()`
pushes the failure onto `libFailedList` and continues, and effects naming a
library that never loaded are skipped the same way. So a missing `soundfx` blob
cannot silently take the `voice_communication` preprocess chain — `aec` and `ns`
from `libqcomvoiceprocessing` — with it.

### The AAC effect is deliberately absent

Stock declares an `aac` effect backed by `vendor/lib64/soundfx/libAACeffect_NT.so`.
It is not shipped here, and the declaration is dropped along with it.

Despite the name it is not a codec. The blob is a 28 KB shim over the AOSP effect
ABI that forwards to **Dirac** — its `NEEDED` list names
`vendor/lib64/libMobileAPI_SHARED.so` (5.6 MB) and every DSP symbol it imports
carries the `@dirac` version tag. "AAC" is AAC Technologies, whose Dirac tuning
Nothing licenses. Presets are `MUSIC`, `MOVIE`, `SPEECH` and `EXT_1`–`EXT_4`,
with separate speaker and headset handles, bypass on Bluetooth, and tuning data
in `vendor/etc/mobile/mobile.config`.

Two reasons not to carry it:

- It is three files and ~5.7 MB, not one. The shim alone is useless — the loader
  dlopens with `RTLD_NOW`, so a missing `libMobileAPI_SHARED.so` fails the load
  just the same. It also wants four `persist.vendor.newAAC.*` properties and
  their `vendor_property_contexts` labels.
- **Nothing instantiates it, including on stock.** No `<postprocess>` or
  `<preprocess>` stanza applies it in either stock copy, and its UUID
  `ae737c63-f2c0-5457-909e-1e940c91b67b` and symbol names appear nowhere in any
  APK or jar across `system`, `system_ext`, `product`, `vendor` or `odm`. Stock
  loads the library at boot and never gives it a frame.

Shipping it would trade 5.7 MB of vendor partition for one fewer boot log line
and no audible change. If a Nothing audio app that creates the effect by UUID
ever ships, all three files plus the properties come back together.

## Volume configuration

`audio_policy_configuration.xml` installs to `etc/audio/sku_volcano/` and pulls
three files in with `<xi:include href="…">`, which resolves **relative to the
including file**:

```
r_submix_audio_policy_configuration.xml
audio_policy_volumes.xml
default_volume_tables.xml
```

All three must sit alongside it in each sku directory, which is why stock ships a
copy per directory. An unresolved `xi:include` is silent in normal logs and
presents as volume keys doing nothing.
