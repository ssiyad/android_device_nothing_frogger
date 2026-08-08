# Capture runs ~25 dB below other handsets

Every recording on this device — WhatsApp voice notes, WhatsApp calls, the
Lineage Recorder, camera video — comes out far quieter than the same speech
recorded on another phone. Cellular voice calls are reportedly fine, which puts
the fault in the AP capture path rather than the mic itself.

## Measurements

Lineage Recorder, WAV, measured with `ffmpeg -af volumedetect`:

| Clip | Condition | mean | max |
|---|---|---|---|
| Normal distance | LVACFS on | −47.6 dB | −24.2 dB |
| Normal distance | LVACFS off | −56.8 dB | −35.2 dB |
| Loud, few cm from mic | LVACFS off | −42.1 dB | −25.8 dB |

Received WhatsApp voice notes, recorded on other handsets, average −25 to −15 dB
mean. Shouting a few centimetres from the mic still peaks at −25.8 dBFS, so
distance and speaking level are not the explanation.

## The gain chain is intact

`TX_DEC1/2 Volume` was stepped with `tinymix` during a live capture while
recording steady room tone (`tinymix` is not in the image; build it with
`mka tinymix` and push it to `/data/local/tmp`):

| `TX_DEC1/2 Volume` | Room-tone RMS |
|---|---|
| 114 | −40 dB |
| 124 | −31.5 dB |
| 84 (driver default) | −72 dB |

+10 steps gave +8.5 dB, −40 steps gave −40.5 dB — a clean ~1 dB per step with
+40 dB of headroom above the default. Nothing clamps, compresses or overrides
the control, and PAL never writes it.

So nothing is *attenuating* the signal. The chain is linear and working; it
simply never receives the gain stock applies somewhere.

## Ruled out

- **LVACFS.** Level is unchanged-to-lower with the AP path off. See
  [lvacfs-source-tracking.md](lvacfs-source-tracking.md). Note that
  `1a828a3`'s commit message claims the camcorder profile cost ~30 dB; that
  claim is wrong and this file supersedes it.
- **Config drift.** `mixer_paths`, `audio_policy_*`, `default_volume_tables`,
  `audio_effects` and `usecaseKvManager` are byte-identical to stock;
  `resourcemanager` differs only by the two deliberate changes.
- **ACDB.** All 33 files under `/vendor/etc/acdbdata` are SHA1-identical to
  stock.
- **Properties.** Of the 81 audio properties in
  [vendor-props-missing.txt](../data/vendor-props-missing.txt), only four are
  read by PAL/AHAL, and stock sets all four to the value the code already
  defaults to.
- **Ignored config.** All 15 `<param key=…>` in `resourcemanager` are
  referenced by our PAL source.
- **ACDB "No calibration found".** 108 of them, but they belong to the
  `VoiceActivation_Mic … DevicePP_Tx_RAW_NLPI` hotword graph and are *more*
  frequent in playback-only windows than during capture.
- **Missing libraries.** Only legacy HIDL audio interfaces and `libagmmixer`.

## The open lead: single-mic topology on a two-mic capture

PAL builds the record graph as `PCM_RECORD` + `HANDSETMIC` +
`DEVICEPP_TX_AUDIO_FLUENCE_SMECNS` (`0xAD000002`) — **single**-mic ECNS. The
device is opened with two channels:

```
PAL: SessionAlsaUtils: setDeviceMediaConfig: CODEC_DMA-LPAIF_RXTX-TX-3 rate ch fmt 48000 2
```

and the mixer confirms two decimators live, `TX SMIC MUX1 = SWR_MIC0` and
`TX SMIC MUX2 = SWR_MIC4`.

`usecaseKvManager.xml` has the dual-mic entry that would select
`DEVICEPP_TX_AUDIO_FLUENCE_ENDFIRE` (`0xAD000003`), keyed on
`CustomConfig="dual-mic"`. That key is only ever set for two usecases
(`AudioStream.cpp:5659`):

```cpp
if (usecase_ == USECASE_AUDIO_RECORD_LOW_LATENCY ||
    usecase_ == USECASE_AUDIO_RECORD_MMAP) {
    if (channels == 2)
        strlcpy(... "dual-mic" ...);
}
```

Ordinary recording is `USECASE_AUDIO_RECORD` (21, `audio-record`), so `dual-mic`
is never set. The only key that *was* set came from the stale `source_` and
missed outright:

```
PayloadBuilder: getSelectorValues: custom config key:camcorder_landscape
PayloadBuilder: retrieveKVs: Fallback to find KVs without custom config
```

leaving the no-custom-config entry, SMECNS.

## Next test

Config-only and reversible: change the plain `HANDSETMIC` +
`PAL_STREAM_DEEP_BUFFER,PAL_STREAM_COMPRESSED` entry in `usecaseKvManager.xml`
from `0xAD000002` to `0xAD000003`, overlay it with Magisk, reboot and re-measure.
If the level jumps, the topology selection is the fault and the real fix is
making the custom key reflect the actual mic count rather than the usecase.

If it does not, the remaining suspect is the gain inside the ACDB topology
itself, which needs comparison against a stock boot rather than static
inspection.
