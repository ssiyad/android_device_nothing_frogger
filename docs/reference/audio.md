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
| AHAL | built from source, `hardware/qcom-caf/sm8650/audio/primary-hal` |

There is no WSA883x. Only the WCD9378 is on SoundWire:

```
/sys/bus/soundwire/devices/  →  swr1  swr2  wcd9378-slave.…  wcd9378-slave.…
```

The `vi-feedback` mixer path drives `WSA_AIF_VI Mixer WSA_SPKR_VI_2` and
`WSA_RX1 Digital Volume`, controls for an amp this device does not have. Stock
has the same apparent mismatch.

### The source build is the same code as stock's blobs

Stock ships `vendor/lib64/libar-pal.so` and
`vendor/lib64/hw/audio.primary.volcano.so` prebuilt; both are built from source
here, and neither is in `proprietary-files.txt`. They are not meaningfully
different code, so swapping either for the stock blob is not a lead worth
chasing.

Comparing demangled function symbols against the LineageOS sources:

| Blob | Stock-only functions |
|---|---|
| `libar-pal.so` | `ResourceManager::isModeInCall` |
| `audio.primary.volcano.so` | `StreamInPrimary::LvacfsSimpleUpdateProfileID` |

One each, across the whole of both libraries. The differences otherwise run the
other way — LineageOS carries `isLvacfsEnabled`, `setLvacfsEnableParam`, the
soc-peripheral callbacks and the secure-zone code that stock's older CAF drop
does not.

The limit of this method is worth stating: it compares which functions exist, not
what they do. A changed constant inside a function body is invisible to it.

## LVACFS mic processing

LVACFS is Nothing's Goodix mic-processing library. It selects a tuning profile by
`DeviceId`, and those IDs are AOSP `audio_source_t` values. The libraries
(`lib_lvacfs.so`, `liblvacfs_wrapper.so`) and all three `lvacfs_params` sets are
shipped here.

**The AP-side path is off here, and stock almost certainly runs it.**
`record_use_ap_lvacfs` is the only key that sets
`ResourceManager::isLvacfsEnabled`, which gates `Lvacfs::init()` and every
per-stream call. It arrived from the Asteroids port and was removed.

That key is a LineageOS construction, not a stock one, so stock's
`resourcemanager_volcano_qrd.xml` not carrying it says nothing about what stock
does. Stock's `libar-pal.so` holds no LVACFS symbol or string whatsoever — no
`PAL_PARAM_ID_LVACFS`, no `isLvacfsEnabled` — so its PAL could not read the key
if it were there. Stock reaches LVACFS from the AHAL instead: `lib_lvacfs.so` and
`liblvacfs_wrapper.so` are `NEEDED` entries of `audio.primary.volcano.so` rather
than `dlopen`ed, and it implements more of the path than the LineageOS source
does — `LvacfsSimpleUpdateProfileID`, `lvacfs_need_restart`,
`lvacfs_wrapper_SetProfile`, `LvacfsAudioZoom`. The only disable path visible in
its strings is a per-stream audio-format mismatch, and no property gates it
(`vendor.audio.feature.audiozoom.enable=false` is in stock's `build.prop`, but
that string does not appear in the AHAL binary).

This is the one known capture-path difference from stock that has not been
measured. What it might be worth against the capture level is weighed in
[tasks/capture-gain-deficit.md](../tasks/capture-gain-deficit.md).

### Turning the AP path back on needs a HAL fix first

The LineageOS HAL picks the profile from `StreamInPrimary::source_`, which is
assigned once in the constructor and never updated. The framework does report the
real source on every recording start, but `in_update_sink_metadata_v7` files it
under `btSinkMetadata`, for Bluetooth, and `SetAggregateSinkMetadata` forwards
only that to `PAL_PARAM_ID_SET_SINK_METADATA`.

That would be harmless if the stream object were rebuilt per recording. It is
not: `adev_open_input_stream` calls `InGetStream(handle)` first and constructs
only when the lookup misses, so a `StreamInPrimary` is reused across apps for as
long as AudioFlinger keeps the input handle alive. Whatever source created the
object is the profile every later app gets. Observed on one io handle across
three consecutive recordings by two apps: every one reported source 1
(`AUDIO_SOURCE_MIC`) and every one was handed profile 5 (`AUDIO_SOURCE_CAMCORDER`).
The same stale value also puts PAL on the `camcorder_landscape` custom key, which
is not in the resourcemanager and falls back to the no-custom-config entry.

The cost is character, not level: `LVIMFS_Parameter_xxxx_ID5_MIC_Normal.txt` from
the 2mic set is the camcorder tuning, meant for a device held at arm's length.

Stock's `LvacfsSimpleUpdateProfileID` is exactly this fix, and is the only
Nothing-specific function in either audio blob. Landing an equivalent means
forking `LineageOS/android_hardware_qcom_audio-ar`, and needs the LVACFS instance
recreated when the source changes, since the profile is bound at
`create_instance` time.

### Profile mapping

Stock ships **no** `DeviceId 1` entry in the 1-mic or 2-mic configs, so a plain
`AUDIO_SOURCE_MIC` recording matches no profile and LVACFS leaves it alone. The
3-mic config is the exception and does ship a genuine source-1 profile,
`LVIMFS_Parameter_xxxx_ID1_MIC_HDR_standard.txt`.

Remapping a profile onto source 1 hands `AUDIO_SOURCE_MIC` a parameter set
intended for another source and garbles capture. Recordings from
`AUDIO_SOURCE_MIC` are meant to get no LVACFS processing.
`VOICE_COMMUNICATION` (7), `VOICE_RECOGNITION` (6) and `UNPROCESSED` (9) match
genuine stock profiles.

Removing the `DeviceId 1` mapping is not a way around the stale `source_` above.
It makes `AUDIO_SOURCE_MIC` match no profile, but `source_` never arrives as 1 —
it arrives as whatever stale value the reused stream carries, and 5 matches a
genuine stock profile.

## Speaker protection

There is none, and stock has none either. `speaker_protection_enabled` is `0` in
`resourcemanager_volcano_qrd.xml` against stock's `1`, but that flag only picks
which branch PAL takes — it is not the difference between a protected speaker
and an unprotected one.

Setting it to `1` here silences the speaker. The PAL we build carries no Awinic
code at all — nothing under `hardware/qcom-caf/sm8650/audio` matches `awinic` or
`aw882` — so `1` selects QCOM's VI-feedback path, which opens a capture stream
on the VI TX backend alongside playback. That TX side never opens, and it takes
the Rx session down with it:

```
PAL: SpeakerProtection: viTxSetupThreadLoop: txPcm open not ready
AGM: Backend:24 <-> Frontend:121 Connect failed error:-22
PAL: StreamPCM: start: Rx session start is failed with status -22
```

**The Awinic algorithm is compiled out of the driver, on stock too.**
`aw882xx_dsp.h:17` reads `/*#define AW_QCOM_PLATFORM*/`, and no Kbuild or
Makefile in the audio-kernel supplies `-DAW_QCOM_PLATFORM`. So the `#else`
branch compiles and `aw_send_afe_cal_apr()`, `aw_send_afe_rx_module_enable()`,
`aw_send_afe_tx_module_enable()` and `aw_set_port_id()` are stubs returning 0.
Every DSP operation — enabling the SKT protection module `0x10013D02`, pushing
the calibrated `Re`, setting `VMAX` — reports success and does nothing. The OEM
tree that stock's `aw882xx_dlkm.ko` is built from has the same line commented
out, so this is Awinic's shipping configuration rather than a porting loss.

**Neither monitor mode can start, and a vendor PAL would not change that.**
`aw_monitor_work_func()` requires `monitor_cfg->monitor_status ==
AW_MON_CFG_OK`, which only the `monitor_update` sysfs handler sets, by parsing
`aw882xx_pid_2329_monitor.bin`. That file is absent from this tree, from the
device, and from the stock image. `hal_monitor` is not a way around it:
`aw882xx_monitor_hal_work()` calls that same `aw_monitor_work_func()`, so the
HAL kcontrol meets the identical gate and returns `VMAX_NONE`. Shipping stock's
`libar-pal.so` to drive `aw882xx_hal_monitor_time` would get the same answer,
which is why swapping the source PAL for the vendor blob is not a route to
protection.

On device this reads as `monitor-mode` parsed correctly into
`AW_MON_KERNEL_MODE`, with monitor enable `0`, `dsp_re` `0` and `algo_state`
unreadable. `monitor-mode` is set to `kernel_monitor` as the more honest of two
non-working settings; changing it fixes nothing.

`aw882xx_acf.bin` does not fill the gap. It is Awinic reference tuning for
`aw88271` against a detected `0x2329`, carries a data type absent from
`aw882xx_bin_parse.h`, and has no `vcalb` — the constant that scales raw
readings into impedance. It holds register profiles and nothing else.

What remains is static, and identical to stock: the register profile from
`aw882xx_acf.bin`, which we ship byte for byte from the same OTA, including the
boost peak-current limit (`ipeak_desc` → `AW_PID_2329_BSTCTRL1_REG`). The amp
holds the hardware limits its tuning sets. Neither build tracks coil temperature
or excursion, so sustained maximum volume has no adaptive backstop — on stock
either.

### Do not re-run calibration

```
/dev/aw882xx_smartpa                    crw------- 10,111
/mnt/vendor/persist/audio/aw_cali.bin   20 bytes, factory data
```

`aw882xx_calib.c` reads exactly that path, so the coil resistance is already
stored. Do **not** add an init service for `aw882xx_cali`. It is a calibration
utility, not a monitor daemon, and its `cali` verb *measures* coil resistance
and overwrites `aw_cali.bin`. Run at boot, with playback active or the device in
a pocket, it replaces good factory data with a bad measurement.

```
./aw882xx_cali [back_end_name] [dev_name] cali [cali_re_time(ms)]
./aw882xx_cali [back_end_name] [dev_name] get_re_range
```

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
