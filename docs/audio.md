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

### Calibration is already done — do not re-run it

```
/dev/aw882xx_smartpa                    crw------- 10,111
/mnt/vendor/persist/audio/aw_cali.bin   20 bytes, factory data
```

`aw882xx_calib.c` reads exactly that path, so the coil resistance the protection
algorithm needs is already stored. Nothing is missing; nothing was enabling the
algorithm that uses it.

**Do not add an init service for `aw882xx_cali`.** It is a calibration utility,
not a monitor daemon:

```
./aw882xx_cali [back_end_name] [dev_name] cali [cali_re_time(ms)]
./aw882xx_cali [back_end_name] [dev_name] get_re_range
```

Its `cali` verb *measures* coil resistance and overwrites `aw_cali.bin`. Run at
boot — with playback active, or the phone in a pocket — it would replace good
factory data with a bad measurement and make protection worse than absent. If it
is ever needed, run it by hand on a quiet speaker, deliberately.

### Fix applied: kernel-side monitoring

`monitor-mode` changed from `hal_monitor` to `kernel_monitor` on both amp nodes
in `frogger-common.dtsi` (devicetrees `503b2916`). `aw882xx_monitor.c:1395`
parses the property, and `AW_MON_KERNEL_MODE` makes the driver run its own
monitoring loop with no HAL involvement. Config comes from the
`aw882xx_acf.bin` already shipped.

**Untested.** After flashing, verify:

```sh
adb shell 'cat /sys/bus/i2c/drivers/aw882xx_smartpa/13-0034/monitor'    # want enabled
adb shell 'cat /sys/bus/i2c/drivers/aw882xx_smartpa/13-0034/dsp_re'     # want non-zero
adb shell 'cat /sys/bus/i2c/drivers/aw882xx_smartpa/13-0034/algo_state' # want a state, not an error
```

If `dsp_re` stays 0, the driver is not loading `aw_cali.bin` and that is the next
thread — not a reason to re-calibrate.

### Remaining option if that fails

**Ship stock's `libar-pal.so`** instead of building PAL from source. Closest to
what stock actually does, and the only route to `hal_monitor` working — but PAL
is the core of the audio HAL, and swapping a source build for a vendor blob
risks everything that currently works. Not a casual change.

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
