# Camera diagnostics

The stack fails in layers and each layer names its own failure.

```sh
adb logcat -c && adb logcat -b crash -c
# exercise the camera
adb logcat -b crash -d | grep -aE "Executable|signal|#0[0-6] pc"
adb logcat -d | grep -aE " E (CamX|ChiX)" | grep -aviE "PreLoadLiberary|PopulateFuseId"
```

**Every grep over a logcat dump or a vendor blob needs `-a`.** Both contain NUL
bytes, and this grep prints *nothing at all* for a binary match without it — not
even "Binary file matches". A search that comes back empty is meaningless unless
`-a` was passed. This produces a convincing false negative and has cost several
investigations real time.

## Which layer

| Framework error | Meaning |
|---|---|
| `Number of camera devices: 0` | sensors did not probe — kernel, devicetree or regulator |
| `Function not implemented (-38)` | HAL rejected the config — usually a missing node or blob |
| `Broken pipe (-32)` | HAL died — get the tombstone, it names the library |

## State checks, cheapest first

```sh
adb shell 'lsmod | grep -E "sgm38120|wr1241"'
adb shell 'cat /sys/class/regulator/*/name | grep -E "SGM|WR_"'
adb shell 'ls /sys/bus/platform/devices/*cam-sensor0/waiting_for_supplier'   # should not exist
adb shell 'ls /dev/v4l-subdev* | wc -l'                                      # expect 25
adb shell 'dumpsys media.camera | grep "Number of camera"'                   # expect 5
adb shell 'ls /sys/bus/platform/drivers/qcom,camera/'
```

`waiting_for_supplier` present means a regulator never registered and
`fw_devlink` is blocking probe indefinitely.

Sensor nodes live under the CCI nodes, not `/soc`:

```sh
adb shell "ls '/sys/firmware/devicetree/base/soc/qcom,cci0@ac15000/'"
adb shell "ls '/sys/firmware/devicetree/base/soc/qcom,cci1@ac16000/'"
```

Looking under `/soc/qcom,cam-sensor*` finds nothing and resembles an overlay that
failed to apply.

`camxoverridesettings.txt` raises CamX logging via `chiLogInfoMask`, commented
out in stock.

## Which cameras an app actually reached

`dumpsys media.camera` keeps a service events log, most recent first, recording
every client connection by package and camera id:

```
08-28 12:01:06 : CONNECT device 3 client for package org.codeaurora.snapcam (PID 13072)
08-28 12:01:09 : DISCONNECT device 3 client for package org.codeaurora.snapcam (PID 0)
```

Five devices are added at boot and two of them are normal, visible to API1. The
log is per-boot, so a fresh boot makes any single session unambiguous, and it
settles whether a camera an app will not offer is one the service never
published to it — without inspecting a single view.

**A death in that log is not evidence of a crash.** `DIED client ... Binder died
unexpectedly` here, and `Process ... has died: fg TOP` in the main log, are how
a process that simply exited is recorded. Check the `START` line immediately
before it, and the absence of both a fresh tombstone and a `FATAL EXCEPTION` in
`logcat -b crash`: an app leaving one activity for another produces exactly this
shape, and reads as a crash if only the camera log is consulted.

## Blob-absence theories, and where they still apply

Within `vendor/lib64/camera/` and the CamX/CHI core the blob set is complete and
byte-identical to stock — 161 libraries there, plus the whole of
`vendor/etc/camera/` including `camxoverridesettings.txt`,
`ntcamoverridesettings.txt`, `nothing_pipeline.bin`, `nothing_node.bin` and
`decision.json`. The only file missing from that set is
`vendor/lib64/camera/node/com.nothing.node.filtereditor.so`, with three colour-LUT
directories under `vendor/etc/camera/filter/`, all belonging to the Nothing
camera app's filter editor and unreachable from Aperture or GCam.

**That completeness does not extend past those directories**, and two real gaps
were found outside them. Scope any "the blobs are fine" claim to the directories
actually checked:

- `com.qualcomm.mcx.*` are in `vendor/lib64`, not `vendor/lib64/camera/`, and
  they `dlopen` their own dependencies. See `camera-image-quality.md`.
- `system_ext/` carries camera code too — the CameraX extender jar and its JNI
  libraries.

The cheap check is a reference sweep rather than a `DT_NEEDED` walk: take the
basename of every file in `data/vendor-missing.txt` and grep for it across every
shipped binary. Anything that hits is a file some shipped blob names, which is
what `dlopen` needs.

CHI nodes `dlopen` their libraries, so absence from `DT_NEEDED` proves nothing
about whether a node requires one. The converse check is cheap and comes back
clean:

```sh
cd vendor/nothing/frogger/proprietary/vendor
for f in $(find lib64/camera lib64/libcamx* lib64/libchi* -name "*.so"); do
    readelf -d "$f" | sed -n 's/.*NEEDED.*\[\(.*\)\]/\1/p'
done | sort -u > /tmp/needed.txt
adb shell 'ls /vendor/lib64/ /vendor/lib64/camera/components/ /system/lib64/' |
    sort -u > /tmp/ondevice.txt
comm -23 /tmp/needed.txt /tmp/ondevice.txt
```

**A component `.so` being mapped in the provider proves nothing either.** CHI
probes and `dlopen`s essentially all of `/vendor/lib64/camera/components/` at
init — `/proc/<pid>/maps` lists `com.qti.node.dummysat.so` and ~75 other
obviously unused components. Binding must be established from the usecase table.

## `dlopen` failures hide outside the CamX tags

`libQnnHtpV73Stub.so` was missing while every `CamX`/`ChiX` grep looked healthy,
because the QNN runtime logs the failure under its own `QnnDsp` tag as a
**warning**, and only the downstream `Failed to load skel` is an error. The DSP
half of the pair (`vendor/lib/rfsa/adsp/libQnnHtpV73Skel.so`) shipped from the
start, which is what made the gap easy to miss.

Grep a session for `dlopen`, `not found` and `No such file` without filtering by
tag before concluding the blob set is complete.

## Reading the blobs

The CamX HAL is **split across two libraries**, and a message's source file name
tells you which to open:

| Library | Holds |
|---|---|
| `vendor/lib64/hw/camera.qcom.so` | core and CHI — `camxnodelegacy.cpp`, `camxsession.cpp`, `camxhwinterface.cpp`, `camxmetabuffer.cpp`, `Node::*`. Carries Nothing's patches. |
| `vendor/lib64/hw/camera.qcom.milos.so` | per-hardware node implementations — `camxtfenode.cpp`, `camxipenode.cpp`, `camxbpsnode.cpp`, `camxifenode.cpp`. Imports core symbols. |

Both are mapped at runtime, so there is no "wrong HAL variant" failure mode to
chase. Grep both; the Nothing-specific code is in the former.

**GNU `objdump -d` silently emits nothing on these blobs** — their `.text`
carries the OS-specific SHF flag, and system binutils has no aarch64 support
here anyway. Use the tree's clang:

```sh
prebuilts/clang/host/linux-x86/clang-r574158/bin/llvm-objdump -d --no-show-raw-insn
```

Two things make call sites findable despite the stripping:

- **String file offset equals vaddr in these ELFs**, so `strings -a -t x` offsets
  match `adrp X, 0xNNN000` + `add X, X, #0xMMM` directly. Resolving the string
  constants inside a function is how you identify it.
- `camera.qcom.so` carries a `.gnu_debugdata` mini-symtab (LZMA at the section
  offset) giving ~11k function names.

Do not trust `objdump`'s enclosing-symbol label — coverage is sparse and it is
routinely thousands of bytes stale.

## Is it actually an error?

Most `E CamX`/`E ChiX` lines on this device are not failures. Qualcomm logs
traces at ERROR severity, and some of the messages are actively misleading.
Establish which before spending a day on one:

1. `strings -a` the blob for the format string; find the xref; disassemble.
2. **Does the log site sit on a conditional branch, or in the prologue?**
   `camxchinodeswpnc.cpp:198` logs unconditionally before doing any work — a
   function-entry trace hardcoded at ERROR.
3. **Does it alter the function's return register?** If the log block leaves the
   return value untouched, nothing downstream can react to it.

**CamX format strings on this build are not trustworthy.**
`chifeature2baserequestflow.cpp:140` prints a result code through a `%d`
labelled "session"; `camxmetabuffer.cpp:2893` prints "Maptype mismatch" for what
is really a pointer-equality bail-out. Read the disassembly before believing the
noun.

**`[ INFO]` is masked on this build.** Only `[CORE_CFG]`, `[REQMAP]` and
`[ DUMP]` verbosity classes appear. The absence of an INFO-level success line
proves nothing — several errors have an INFO counterpart you cannot see without
raising `chiLogInfoMask`.

## Errors a healthy session logs

All of the following are stock behaviour, confirmed against the extracted stock
partitions and the shipped blobs. None indicates a defect in this port.

| Error | Source | Why it is noise |
|---|---|---|
| `PreLoadLiberary`, `PopulateFuseId` | various | long-established noise |
| `Couldn't find file vendor.qti.camera.provider-service_64.farf` | FastRPC | debug-logging config, absent by design |
| `NcLib Assertion failed: …IcaGridExtrapolationCorners` | `NcLibWarpInternal.cpp:2109` | The capability is a **compile-time constant folded to false** in this per-SoC camx-lib build; the supporting arm is dead-code-eliminated. No setter symbol, no config, no property. Fires once per ArcSoft SAT warp grid — 30/s for the whole of any logical-camera preview — and takes a working fallback grid conversion. |
| `CheckMCTFTransformCondition Failed` | `ica32setting.cpp:394` | First frame only, one pair per pipeline creation. Always preceded by a GME/MCGME `previous 0, cannot do alignment`. With no previous frame there is no alignment matrix or grid, so MCTF is skipped for frame 1 and runs normally after. Not a sign MCTF is off. |
| `Failed to open /proc/reserve_pool/inpool_pid_0` | `chxextensionmodule.cpp:2796` | `CONFIG_QCOM_DMABUF_RESERVE_POOL`, absent from our kernel — see below. No fallback path exists; the function's only job is to publish a PID. |
| `Unsupported stats FD mode value: 128` | `camxfdmanagernode.cpp:5291` | GCam requests `faceDetectMode=128`, outside the advertised `availableFaceDetectModes [0 1]`. The HAL publishes zero face tags to that app in response. Not reproducible with Aperture; not fixable from the device tree. |
| `Invalid FD processing type: 3` | `camxfdutils.cpp:1363` | Type 3 is **SWFD**, the Vega engine this device uses. `GetMinFaceSize()` implements only CVPFD and DLFD; the result feeds two log statements and nothing else. |
| `PDLibSensorType is Invalid` + 18x `PDAF HW Configuration is NULL` | `camxtfenode.cpp:11483`, `:2723` | Once per non-PDAF sensor per session — the ultrawide (`cam2`) and front (`cam1`), both fixed-focus. "Invalid" is the correct type for a sensor with no PDAF. |
| `Invalid pointer pHwCfgWrapper 0x0` | `camxnodelegacy.cpp:23305` | Every TFE instance that reaches pre-finalization logs it exactly twice; no non-TFE node ever does. Wrappers are gated on a static node property containing `hwcfg`, and the shipped usecase table names only `com.qti.hwcfg.bps` and `com.qti.hwcfg.ipe`. BPS and IPE nodes have live wrappers in the same session. |
| `StoreNothingMeta() Get package name faled result -2` | `camxhwinterface.cpp:342` | The vendor tag `com.nothing.device.package_name` is absent — see below. Once per `configure_streams()`. |
| `releasePNC failed` | `camxchinodeswpnc.cpp:2441` | `com.qti.node.swpnc` is wired with **0 input and 0 output ports** in every Frogger topology, so it never receives a request and never creates a context; the destructor releases a NULL handle. Fires once per pipeline destruction — every lens switch, not just app exit. Leak-free: `LibUnmap` runs on both paths. |
| `SWPNCNodePrepareStreamOn() PNC prepare stream on: pInfo:` | `camxchinodeswpnc.cpp:198` | Unconditional function-entry trace hardcoded at ERROR. Always paired 1:1 with a later `releasePNC failed`. |
| `Sensor[3]: Current DAC Ratio N is not equal to last DAC Ratio M` | `camxsensornode.cpp:4225` | Nothing debug output at ERROR, gated on `slot == 3` and `GetNtFeature(131)`, fired once per tele-actuator move to trigger a PDAF window re-push. Both values are successive samples of `com.nothing.camera.dacvalue`; the "last" is CamX's own copy, written three instructions *after* the log. Sits on the success path. |
| `Realtime NodeCommon*JobFamily<ptr> JobPriority::Critical` | `camxsession.cpp:3362`, `:3379` | Prints the name template and priority passed to `ThreadManager::RegisterJobFamily`; `%p` is the Session object. Exactly two lines per realtime session. Registration failures have separate strings. |
| `Copy() Maptype mismatch 0 0` | `camxmetabuffer.cpp:2893` | Two conditions share one log. The printed maptypes are **equal**, so the branch taken is `dst == src` — a self-copy, which is a no-op. Returns `ENotImplemented`. Three per still capture, one per MFNR stage. |
| `AddPipelineDescriptor() … pruneipe:N`, `OnPruneUsecaseDescriptor() motionEnable N …` | `chxusecaseutils.cpp`, `chifeature2realtime*.cpp` | Hardcoded `CHX_LOG_ERROR` descriptor dumps. `motionEnable 0` (no EIS) prunes the two IPE nodes out of the realtime pipeline, which is why IPE runs offline. Built once per provider lifetime. |
| `OnInitializeStream() pTargetName:…,fmt:0x0` | `chifeature2memcpy.cpp:668` | GCam/`MemcpyZSLYUV` only, three per `configure_streams`. Default arm of a target-name dispatch; returns the base-class result unmodified. `fmt:0x0` is the framework sink target before its format is bound — assigned on the next line. |

### The two that are real divergences from stock, and cost nothing

**`/proc/reserve_pool` does not exist here.** It is
`CONFIG_QCOM_DMABUF_RESERVE_POOL`, a QCOM dma-buf reserve page pool. Stock's
`qcom_dma_heaps.ko` compiles it in and creates the node; ours has zero
reserve-pool symbols. The reason is generational: **our kernel is
6.6.114-android15 and stock's is 6.1.134-android14**. `kernel/nothing/sm7635` is
QCOM's 6.6 pineapple drop with the Frogger port on top, *not* a backport of the
OEM 6.1 tree at `~/sources/android/kernels/frogger`, and the 6.6 line does not
carry this feature — there is no config symbol to enable. Porting it means
forward-porting ~1170 lines of mm-adjacent code, not lifting a file.

Stock holds ~128 MB of pooled pages preferentially for cameraserver; we use the
ordinary pool, which still background-refills. No session has ever logged an
allocation failure. Revisit only if camera-launch latency under memory pressure
becomes a symptom.

Any "the OEM kernel has X, why don't we" question must account for that version
gap first.

**Nothing patches AOSP `system/bin/cameraserver`, not just the vendor blobs.**
Stock's cameraserver unconditionally injects three vendor tags into every
client's session parameters before `configureStreamsLocked`:

```
com.nothing.device.package_name
com.nothing.device.previous_package_name
com.nothing.device.activity_name
```

LineageOS's does not (`grep -c com.nothing.device.package_name
/system/bin/cameraserver` → 0). The HAL still *registers* the tags
(`0x814c0000..2` in `dumpsys media.camera`), so only the producer is missing —
which is why every app fails the lookup identically, platform-signed or
sideloaded. The one real consumer is a WhatsApp/Instagram anti-banding override
in `camxcafdstatsprocessor.cpp`; the CHI-side consumer stores a "third-party
app" flag that is never read. Not fixable from the device tree — the producer is
in `frameworks/av`.

**Check this before chasing any other per-app camera behaviour difference.** Any
vendor code keyed on those tags is inert on this ROM.

## Which usecase graph is running

Aperture and GCam exercise **different HAL usecases**, and their error sets are
disjoint. Neither is a superset of the other, so an error absent under one app is
not evidence of a defect under the other.

| App | Opens | Feature graph |
|---|---|---|
| Aperture, rear | logical camera 0/4 (physical 0/2/3) | `MCXSuperFG` → `MultiCameraBayerSATNoBPSFrogger0`, `MultiCameraReprocessRealtime` |
| Aperture, front | logical camera 1 | `RealtimeFG` |
| GCam | physical ids directly | `RTBayer2YUV`, `RealTimeFeatureZSLPreviewRaw`, `MemcpyZSLYUV` |

```sh
grep -a "Created feature graph" cam.log     # names it in one line
```

Aperture is on the HAL's default path — stock's own camera app occupies the same
slot (`vendor.camera.aux.packagelist`) and drives the same logical-camera SAT
graph. The ArcSoft SAT node only exists there, which is why the ICA grid
assertion is invisible under GCam.

## Pipeline lifecycle explains most "only camera N logs this" patterns

```sh
grep -a SetPipelineStatus cam.log
```

In the multicam usecase the non-active physical cameras' realtime pipelines are
built but never pre-finalized — they stop at `PER_FRAME_RESOURCE_FINALIZED` and
their nodes never reach most code paths. That mimics a per-sensor defect
convincingly. Check which pipelines get past that state before concluding a
sensor is at fault.

Likewise, check a node's port counts before asking whether its error matters:

```sh
grep -a "CreateNodes()" cam.log     # prints numInputPorts/numOutputPorts per node
```

A 0/0 node is disconnected from the DAG and cannot be doing work.
`com.qti.node.swpnc` is the only 0/0 node in any Frogger session; its neighbours
run `memcpy` 1/1, `swhme` 3/1, `smooth_transition` 16/3, IPE 3/3, TFE 2/17.

## Teardown noise

Anything logged between

```
camxhal3.cpp:2045 flush() HalOp: Begin FLUSH
camxsession.cpp:1243 Flush() Flush took N ms
```

is cancellation, not failure. Always bracket an error against that window before
reading it as a defect. A preview-only capture with no teardown is the cheapest
control.

**`28` in any CamX or CHI log means "cancelled by flush"**
(`CamxResultECancelledRequest`) — the single most useful number for triaging
teardown noise. The full enum, recovered from the string table at
`.data.rel.ro+0x1fcf610` in `camera.qcom.so`:

```
0 Success        1 EFailed         2 EUnsupported    3 EInvalidState
4 EInvalidArg    5 EInvalidPointer 6 ENoSuch         7 EOutOfBounds
8 ENoMemory      9 ETimeout       10 ENoMore        11 ENeedMore
12 EExists      13 EPrivLevel     14 EResource      15 EUnableToLoad
16 EInProgress  17 ETryAgain      18 EBusy          19 EReentered
20 EReadOnly    21 EOverflow      22 EOutOfDomain   23 EInterrupted
24 EWouldBlock  25 ETooManyUsers  26 ENotImplemented 27 EDisabled
28 ECancelledRequest              29 ECoreNullMetadata
```

## Nothing feature flags

`GetNtFeature(id)` is `vendor/lib64/libntf.so` reading **`/dev/ntfeature`**, a
152-byte array with one byte per feature. Readable without root:

```sh
adb shell 'od -An -tu1 /dev/ntfeature'      # index by feature number
```

Feature 131 = carry the actuator DAC value into the sensor PDAF update. Use this
for any "is this Nothing path enabled" question.

## Proving a rail is actually driven

A device node existing proves the driver loaded, not that anything uses it. For
anything that ends in a regulator — SOIS, a sensor supply — the rail itself is
the evidence. Snapshot every camera rail, open the camera, snapshot again, diff:

```sh
snap() { adb shell "su -c 'for d in /sys/class/regulator/*/; do
  n=\$(cat \$d/name 2>/dev/null)
  case \$n in SGM*|WR_*) echo \"\$n \$(cat \$d/state)\";; esac
done'" | tr -d '\r' | sort; }

snap > /tmp/closed.txt
# open the camera, wait ~10s
snap > /tmp/open.txt
diff /tmp/closed.txt /tmp/open.txt
```

Diffing *all* of them is what makes this trustworthy — the rails that obviously
must come on are the control. If none change, the session never started; if only
some change, read which sensor is streaming before concluding anything.

Three traps, each of which reads as a broken driver:

- **`num_users` is 0 even for rails in use.** It is not a consumer count you can
  read this way. `SGM_LDO4` (`cam_vio`) reports 0 with a session live. Use
  `state`.
- **`/sys/kernel/debug/regulator/` does not exist here**, so `regulator_summary`
  is not available to list consumers. Establish the sole consumer from the
  devicetree instead.
- **The rails belong to one sensor.** A wide-sensor rail stays off during a tele
  session, which looks identical to a broken driver. Confirm which camera opened
  (`CameraId-N opened successfully`) before reading the diff.

## Driving the camera from adb

`am start -a android.media.action.STILL_IMAGE_CAMERA` resolves to
`ResolverActivity` — a chooser — because no app is the default handler. The
intent silently lands on a gallery instead, and a rail diff taken around it shows
nothing changing for the obvious reason.

```sh
adb shell 'am start -n org.lineageos.aperture/.CameraLauncher'
adb shell 'dumpsys window | grep mCurrentFocus'   # always confirm what came up
```

The screen must be awake — a dozing device accepts the intent and never starts
the HAL. Confirming the foreground activity immediately after `am start` can
still race the app's own startup; check the log for `Created feature graph`
instead of trusting a single `dumpsys` sample.

**Aperture ignores the `android.intent.extras.CAMERA_FACING` extra** and opens
the rear camera regardless. To reach the front camera, tap the flip button:

```sh
adb shell 'uiautomator dump /sdcard/ui.xml'
adb shell 'cat /sdcard/ui.xml'    # find flipCameraButton bounds
adb shell 'input tap 1038 2368'   # centre of those bounds
```

The GCam fishfood build has no launcher activity and cannot be started from adb
at all — a GCam session needs a human.

`dumpsys media.camera` shows both the app's request and the HAL's result
(`Last request sent` vs `Latest received frame`) for any live session. That is
how to attribute a bad metadata value to the app rather than the HAL without
instrumenting anything.

## Proving face detection ran

The FD node prints its own counters at teardown, at a visible level, in any
normal capture:

```
~FDManagerNode() Node[…_FDManager0_cam0] FD frames processed 269 skipped 251 total 520
```

Inactive physical cameras read `0 0 0`, which makes the measurement
self-validating.

## Stock's first-stage kernel modules

`extracted/` has no `vendor/lib/modules/` at all — the first-stage modules live
in the vendor_boot ramdisk, so a `.ko` missing from `extracted/` proves nothing
about stock. Unpack:

```sh
dd if=vendor_boot.img bs=1 skip=4096 count=$VENDOR_RAMDISK_SIZE | lz4 -d | cpio -idm
```

`vendor_ramdisk_size` comes from the VNDRBOOT v4 header. 330 modules.

## dmesg

Kernel logs rotate in about four minutes on this device. Capture promptly.
