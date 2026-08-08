# Make LVACFS follow the recording source

AP-side LVACFS is off (`record_use_ap_lvacfs` removed from
`resourcemanager_volcano_qrd.xml`, matching stock). Turning it back on requires
fixing how the HAL decides which tuning profile to apply, because the value it
uses is not the source the app asked for.

## The defect

`Lvacfs::startInputStream` picks the profile from `StreamInPrimary::source_`:

```cpp
int ret = wrapper_ops_->create_instance(in->lvacfs_handle, in->source_, ...);
```

`source_` is assigned once, in the constructor (`AudioStream.cpp:5603`). Nothing
ever updates it. The framework does report the real source on every recording
start, but `in_update_sink_metadata_v7` files it under `btSinkMetadata`, for
Bluetooth, and `SetAggregateSinkMetadata` only forwards that to
`PAL_PARAM_ID_SET_SINK_METADATA`.

That would be harmless if the stream object were rebuilt per recording. It is
not: `adev_open_input_stream` calls `InGetStream(handle)` first and only
constructs when the lookup misses, so a `StreamInPrimary` is reused across apps
for as long as AudioFlinger keeps the input handle alive. Whatever source
created the object is the profile every later app gets.

Observed on one io handle across three consecutive recordings by two apps:

```
AudioDevice: InGetStream: 1342: Found existing stream associated with iohandle 142
AudioStream: in_update_sink_metadata_v7: 1661: Sink metadata source:1
goodix_lvacfs: lvacfs_wrapper_CreateLibraryInstance: Entry ProfileID=5 ...
```

Every recording reported source 1 (`AUDIO_SOURCE_MIC`) and every one was handed
profile 5 (`AUDIO_SOURCE_CAMCORDER`). The same stale value also puts PAL on the
`camcorder_landscape` custom key, which is not in the resourcemanager and falls
back:

```
PayloadBuilder: getSelectorValues: 2937: custom config key:camcorder_landscape
PayloadBuilder: retrieveKVs: 2769: Fallback to find KVs without custom config
```

## What this did not cause

The low capture level. Turning the AP path off left recordings unchanged-to-
quieter, so the profile mismatch is a correctness bug, not the source of the
deficit. See [capture-gain-deficit.md](capture-gain-deficit.md), which supersedes
the causal claim in `1a828a3`'s commit message.

## Why it looked like it mattered

`LVIMFS_Parameter_xxxx_ID5_MIC_Normal.txt` from the 2mic set is the camcorder
tuning, and recordings were quiet, so the profile looked like the attenuator. It
was not: disabling the AP path did not raise the level. The wrong profile is
still wrong — it hands every recording a tuning meant for a device held at arm's
length — but the cost is character, not level.

This is also why [the earlier fix](../reference/audio.md) did not settle it. That
change removed a `DeviceId 1` mapping so that `AUDIO_SOURCE_MIC` would match no
profile and be left alone. It is correct as far as it goes, but `source_` never
arrives as 1 — it arrives as whatever stale value the reused stream carries, and
5 matches a genuine stock profile.

## What a fix needs

1. Update `source_` from `track->base.source` in `in_update_sink_metadata_v7`.
2. Recreate the LVACFS instance when the source changes mid-stream, since the
   profile is bound at `create_instance` time.
3. Re-evaluate the `camcorder_landscape` custom key on the same signal.

## Constraint

This is `hardware/qcom-caf/sm8650/audio`, which is **not** in
`local_manifests/frogger.xml` and not forked under `ssiyad/`. Landing it means
forking that repo or carrying the change upstream. Weigh that against the fact
that stock does not enable the AP LVACFS path at all, so the device gives up
nothing it has today by leaving this alone.
