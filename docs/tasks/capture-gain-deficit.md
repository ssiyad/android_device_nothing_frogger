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

## Endfire was tested and is worse

Changing that entry from `0xAD000002` to `0xAD000003` and rebooting does select
the dual-mic graph — confirmed, not inferred:

```
AGM: graph: print_graph_alias: GKV Alias 206 |
  DeviceTX_Handset_Mic_Instance_Instance_1_DevicePP_Tx_Audio_Fluence_Endfire_StreamTX_PCM_Record
```

It makes capture dramatically quieter. Per 0.5 s window:

| Clip | Topology | median | max | min |
|---|---|---|---|---|
| speech | SMECNS | −58.9 dB | −48.8 dB | −74.1 dB |
| speech | Endfire | −76.7 dB | −68.7 dB | −100.5 dB |
| silence | Endfire | −72.3 dB | −64.4 dB | −97.0 dB |

Stretches reach −100 dB, near digital silence — the signature of a beamformer
nulling its input, consistent with assumed mic geometry that does not match where
`SWR_MIC0` and `SWR_MIC4` sit on this device. Caveat: the Endfire speech clip has
no more captured energy than the silent one, so "it cancelled the speech" and
"that clip was also silent" are not fully separated. Neither reading supports
Endfire.

So the single-mic topology is *not* the fault, and forcing the dual-mic key would
make things worse, not better. Do not spend time on the
`USECASE_AUDIO_RECORD_LOW_LATENCY` gate on this evidence.

## Best remaining lead: the record graph asks ACDB with an empty CKV

AGM prints the metadata it sets for the capture device and for device+PP:

```
AGM: metadata: GKV size:2
AGM: metadata: key:0xa3000000, value:0xa3000004
AGM: metadata: key:0xad000000, value:0xad000003
AGM: metadata: CKV count:0
```

The Graph Key Vector is right; the **Calibration** Key Vector is empty. CKV is
what selects which calibration ACDB applies to the graph's modules, so a correct
topology with no calibration would leave its gain at defaults — which is exactly
the shape of this fault, given the codec-side chain is provably linear and
untouched.

`PayloadBuilder::populateDevicePPCkv` (`pal/session/src/PayloadBuilder.cpp:3371`)
switches on the PAL stream type. Recording opens as `PAL_STREAM_DEEP_BUFFER`
(`pal_stream_open: Enter, stream type:2`), which lands in a branch that pushes
CKV entries only for `PAL_DEVICE_OUT_SPEAKER` — nothing for a TX device — and
ends on an unfinished upstream path:

```cpp
/* TBD: Push Channels for these types once Channels are added */
//keyVector.push_back(std::make_pair(CHANNELS,
//                                   dAttr.config.ch_info.channels));
```

`PAL_STREAM_VOICE_UI` does push `CHANNELS`, commented "for FFNS or FFECNS channel
based calibration". The record graph is a channel-dependent ECNS topology as
well, and the device is opened with two channels, so a channel-keyed calibration
entry would not match an empty CKV.

This cannot be tested from the device tree. `resourcemanager`'s
`PAL_DEVICE_IN_HANDSET_MIC` entry carries backend, channels, samplerate and EC
settings only — no gain knob. Testing means building `libar-pal` with the
`CHANNELS` push restored for capture streams and overlaying
`/vendor/lib64/libar-pal.so` with Magisk. Same tree that built the ROM, one
changed function, removable by deleting the file.

Treat it as a hypothesis. Two before it looked at least as good and both were
wrong.

## If that fails

Comparing against a stock boot with the same instrumentation — `tinymix` idle and
during capture, plus the `GKV Alias` and `CKV count` lines — would discriminate
directly rather than by reasoning. Noted only for completeness: flashing stock is
ruled out.

`tinymix` is not in the image. Build it with `mka tinymix` and push it to
`/data/local/tmp`; it is the single most useful instrument found in this
investigation. Raise the log buffer first (`logcat -G 8M`) — the default ~256 KiB
rolls the graph-open lines within about 100 seconds.
