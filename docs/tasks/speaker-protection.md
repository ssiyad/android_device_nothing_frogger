# Restore speaker protection

`speaker_protection_enabled` is `0` in `audio/resourcemanager_volcano_qrd.xml`.
At stock's `1` the speaker is silent: protection opens a VI feedback capture
stream alongside playback, the TX side never opens, and it takes the Rx session
down with it.

```
PAL: SpeakerProtection: viTxSetupThreadLoop: txPcm open not ready
AGM: Backend:24 <-> Frontend:121 Connect failed error:-22
PAL: StreamPCM: start: Rx session start is failed with status -22
```

## Kernel-side monitoring is a dead end

`aw882xx_monitor_start()` requires `monitor_cfg->monitor_status == AW_MON_CFG_OK`.
That struct is populated only by `monitor_update_store()`
(`aw882xx_monitor.c`), a sysfs write handler that loads
`aw882xx_pid_2329_monitor.bin`. That file is absent from this tree, from the
device, and from the stock vendor image.

Nothing writes `monitor_update`, so `monitor_status` stays 0 and the monitor
never starts in either `hal_monitor` or `kernel_monitor` mode. `monitor-mode` is
set to `kernel_monitor` as the more honest of two non-working settings.

`aw882xx_acf.bin` does not fill the gap. It identifies as Awinic reference tuning
for `aw88271` — not the `0x2329` the driver detects — carries a data type absent
from `aw882xx_bin_parse.h`, and has no `vcalb`, the constant that scales raw
readings into impedance. It holds register profiles and nothing else.

## The only remaining route

Ship stock's `/vendor/lib64/libar-pal.so` instead of building PAL from source.
Stock's PAL has Awinic support built in and is the only thing in the stock image
that references `/vendor/bin/aw882xx_cali`; it drives the amps' `hal_monitor`
path.

`extract-files.py` lists `libar-pal` under `lib_fixup_remove`, so this is a
deliberate reversal. PAL is the core of the audio HAL and swapping a source build
for a vendor blob risks everything on the audio path.

## Do not re-run calibration

```
/dev/aw882xx_smartpa                    crw------- 10,111
/mnt/vendor/persist/audio/aw_cali.bin   20 bytes, factory data
```

`aw882xx_calib.c` reads exactly that path, so the coil resistance the algorithm
needs is already stored.

Do **not** add an init service for `aw882xx_cali`. It is a calibration utility,
not a monitor daemon, and its `cali` verb *measures* coil resistance and
overwrites `aw_cali.bin`. Run at boot, with playback active or the device in a
pocket, it replaces good factory data with a bad measurement.

```
./aw882xx_cali [back_end_name] [dev_name] cali [cali_re_time(ms)]
./aw882xx_cali [back_end_name] [dev_name] get_re_range
```

## Consequence of leaving it off

No speaker protection of any kind — neither QCOM's VI path, which targets WSA
hardware this device does not have, nor the AW882xx's internal algorithm, which
nothing enables. Safe at normal levels; sustained maximum volume is not.
