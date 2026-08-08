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

## The empty CKV was a misreading — tested and dead

AGM prints several metadata blocks per capture. The device-level one
(`GKV size:1`, the backend alone) reads `CKV count:0` and always will — it
carries no calibration vector by design. Reading that block and concluding the
record graph got no calibration was wrong. The record graph's own block, unpatched,
already had one:

```
GKV size:4  key:0xb1000000 value:0xb1000001   (PCM_Record)
            key:0xa3000000 value:0xa3000004   (Handset_Mic)
            key:0xad000000 value:0xad000002   (topology)
CKV count:1 key:0xa4000000 value:0xf
```

It was tested anyway. `libar-pal` built with `CHANNELS` pushed for capture
devices, overlaid with Magisk, verified live by both the added log line
(`CKV CHANNELS 2 for in device 27`) and the CKV going 1 → 2. Level did not move:

| Clip | mean | max |
|---|---|---|
| patched | −58.5 dB | −37.1 dB |
| patched | −57.9 dB | −40.6 dB |
| unpatched, same config | −56.8 dB | −35.2 dB |

Note also that this file is shared by every sm8650 device in the tree, so a fault
here would not be Frogger-specific. That argument alone should have outweighed
the log reading.

## Mic routing is also ruled out

`TX SMIC MUX1` was swept across SoundWire ports during a live capture with
continuous speech. Median level per port over the window each was active:

| Port | median | p75 |
|---|---|---|
| `SWR_MIC4` (in use) | −54.1 dB | −49.2 dB |
| `SWR_MIC0` (in use) | −57.0 dB | −53.8 dB |
| `SWR_MIC5` | −59.7 dB | −55.8 dB |
| `SWR_MIC7` | −60.4 dB | −53.6 dB |
| `SWR_MIC6` | −69.5 dB | −59.2 dB |

The two ports already in use are the loudest. No port offers anything like the
missing 25 dB, and a win that size would be unmissable against this spread.
`SWR_MIC1`–`MIC3` were not covered — the sweep script fired early because
`TX DEC1 MUX` keeps reading `SWR_MIC` after a capture ends, so detect capture
some other way if repeating this.

## What is left

Nothing cheap. Every device-specific candidate reachable without a stock
reference has been eliminated: config, ACDB contents, properties, topology
selection, mic routing, and the codec gain chain. What remains is the gain inside
the ACDB topology, and discriminating that means running the same instrumentation
on a stock boot — `tinymix` idle and during capture, plus the `GKV Alias` and
`CKV count` lines — so the two can be compared directly. Flashing stock is ruled
out, so this is parked rather than blocked on a next step.

Method notes for whoever picks it up: `tinymix` is not in the image, build it with
`mka tinymix`; raise the log buffer with `logcat -G 8M` or the graph lines roll
out inside 100 seconds; the Lineage Recorder names files by **start** time; and
`ffmpeg -af volumedetect` over a whole clip hides everything — window it, and
confirm speech is actually present before comparing two clips.

`tinymix` is not in the image. Build it with `mka tinymix` and push it to
`/data/local/tmp`; it is the single most useful instrument found in this
investigation. Raise the log buffer first (`logcat -G 8M`) — the default ~256 KiB
rolls the graph-open lines within about 100 seconds.
