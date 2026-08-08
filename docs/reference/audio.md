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
