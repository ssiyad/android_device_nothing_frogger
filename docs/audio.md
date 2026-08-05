# Audio

## Speaker protection — why it is off, and what it would take

**Current state:** `speaker_protection_enabled` is `0` in
`audio/resourcemanager_volcano_qrd.xml`. With it at stock's `1` the speaker was
completely silent, and video stuttered because every playback start failed and
retried:

```
PAL: SpeakerProtection: viTxSetupThreadLoop: txPcm open not ready
AGM: Backend:24 <-> Frontend:121 Connect failed error:-22
PAL: StreamPCM: start: Rx session start is failed with status -22
AHAL: onWriteError: write error -22 usecase(1: low-latency-playback)
```

Speaker protection opens a VI (voltage/current) feedback capture stream alongside
playback. The TX side never opened and brought the Rx session down with it.

### The hardware does not match the config

Frogger drives **two Awinic AW882xx amps** on I2C bus 13 (`13-0034`, `13-0035`),
audio over primary MI2S. There is **no WSA883x**:

```
/sys/bus/soundwire/devices/  →  swr1  swr2  wcd9378-slave.…  wcd9378-slave.…
```

Only the WCD9378 codec is on SoundWire. Yet the card is named
`volcano-qrd-wsa883x-snd-card` and the `vi-feedback` mixer path drives

```
WSA_AIF_VI Mixer WSA_SPKR_VI_2
WSA_RX1 Digital Volume
```

— controls for an amp this device does not have.

### Everything we ship already matches stock

Checked one layer at a time; none of these is the problem:

| Layer | Result |
|---|---|
| `mixer_paths_volcano_qrd.xml` | byte-identical to stock, 1501 lines |
| `resourcemanager_volcano_qrd.xml` | identical apart from this change |
| `card-defs.xml` | identical, 397 lines |
| ACDB data | identical, 33 files, none missing |
| `aw882xx_smartpa@34/@35` DT node | identical to the OEM tree |
| `pri_mi2s_rx` / `pri_mi2s_tx` dailinks | correct, both bind the two AW882xx |
| AW882xx DAI capture support | present — `Speaker_Capture`, S32_LE, 48kHz |
| `MI2S-LPAIF-TX-PRIMARY` | exists on the card as `multicodec-22` |
| `/vendor/bin/aw882xx_cali` | shipped |
| `/vendor/firmware/aw882xx_acf.bin` | shipped |

Stock also sets `speaker_protection_enabled=1`, also has one
`PAL_DEVICE_IN_VI_FEEDBACK`, and its `mixer_paths` has **zero** aw882xx
references. So stock has the same apparent mismatch and works.

### The actual difference: PAL

`aw882xx_cali` is referenced by exactly one thing in the stock image:

```
/vendor/lib64/libar-pal.so
```

**Stock's PAL blob has Awinic support built in.** It is what invokes
`aw882xx_cali` and drives the amps' `hal_monitor` path. We do not ship it —
`extract-files.py` lists `libar-pal` under `lib_fixup_remove` and PAL is built
from source at `hardware/qcom-caf/sm8650/audio/pal`, a generic CAF branch with
no Awinic code.

So nothing ever enables the amps' own protection:

```
algo_state  →  read algo run state failed!
dsp_re      →  0            (should be a measured coil resistance)
monitor     →  monitor enable: 0
```

**There is currently no speaker protection of any kind** — not QCOM's VI path,
which targets absent WSA hardware, and not the AW882xx's internal algorithm,
which nothing turns on. Fine at normal levels; avoid sustained maximum volume.

### Options, in increasing order of effort

1. **`monitor-mode = "kernel_monitor"`** in `frogger-common.dtsi`. The driver
   parses this property (`aw882xx_monitor.c:1395`) and `AW_MON_KERNEL_MODE`
   makes it run its own monitoring loop instead of waiting for the HAL. One
   line, no PAL involvement, and the config it needs is in the
   `aw882xx_acf.bin` we already ship. **Untested.** Calibration (`dsp_re`) may
   still want `aw882xx_cali` to have run once.
2. **Run `aw882xx_cali` from an init service.** The binary is on the device and
   nothing starts it — stock has no init rc for it either, because stock's PAL
   launches it. Worth trying: it may populate calibration and enable the algo
   even under `hal_monitor`.
3. **Ship stock's `libar-pal.so`** instead of building PAL from source. Closest
   to stock behaviour and the only route to what stock actually does — but PAL
   is the core of the audio HAL, and swapping a source build for a vendor blob
   risks everything else that currently works. Not a casual change.

Option 1 is the cheapest real fix and does not depend on any blob. Try it before
considering 3.

---

## Volume controls — fixed

`audio_policy_configuration.xml` installs to `etc/audio/sku_volcano/` and pulls
three files in with `<xi:include href="…">`, which resolves **relative to the
including file**:

```
r_submix_audio_policy_configuration.xml
audio_policy_volumes.xml
default_volume_tables.xml
```

All three were installed only under `etc/`, so none of the includes resolved and
no volume curves loaded at all — the volume keys did nothing. Stock ships a copy
in each sku directory for exactly this reason, and our copies are byte-identical
to stock's; only the install path was wrong. Fixed in `a0edc2b`.

**Trap:** an unresolved `xi:include` is silent in normal logs. If volume
behaviour looks wrong again, check that every `href` in the *installed*
`audio_policy_configuration.xml` has a sibling file next to it.

---

## Layout

| | |
|---|---|
| Card | `volcano-qrd-wsa883x-snd-card` (name is misleading, see above) |
| Codec | WCD9378 over SoundWire |
| Speaker amps | 2× Awinic AW882xx, I2C `13-0034` / `13-0035`, primary MI2S |
| Speaker backend | `MI2S-LPAIF-RX-PRIMARY` (PCM `00-21`) |
| VI feedback backend | `MI2S-LPAIF-TX-PRIMARY` (PCM `00-22`), configured but unused |
| PAL | built from source, `hardware/qcom-caf/sm8650/audio/pal` |
