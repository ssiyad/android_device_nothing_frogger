# Explain the errors a healthy camera session still logs

Preview opens, streams and tears down, and every `DT_NEEDED` of every camera blob
resolves on the device. These are what a working session logs anyway. None is
known to break a user-visible feature; none is known to be harmless either.

Captured from a GCam preview session on the rear camera (`cam_1`).

| Error | Source | Reading |
|---|---|---|
| `Failed to open /proc/reserve_pool/inpool_pid_0` | `chxextensionmodule.cpp:2796` | The Nothing kernel's camera memory reserve pool. No `reserve_pool` anywhere in `kernel/nothing/sm7635`, so the node cannot exist. 54 occurrences in one session. |
| `Unsupported stats FD mode value: 128` | `camxfdmanagernode.cpp:5291` | Logged once per frame. `ANDROID_STATISTICS_FACE_DETECT_MODE` is 0, 1 or 2, so 128 is an OEM extension the shipped FD node rejects. Confirm whether face detection works before treating it as noise. |
| `Invalid FD processing type: 3` | `camxfdutils.cpp:1363` | Same subsystem, once per session. |
| `PDLibSensorType is Invalid` | `camxtfenode.cpp:11483` | All four `pdlib` components ship. Points at sensor module data rather than a missing blob. PDAF may be degraded. |
| `Invalid pointer pHwCfgWrapper 0x0` | `camxnodelegacy.cpp:23305` | `GetOEMFeatureTypeMask()` on the ZSL preview pipeline. |
| `CheckMctfTransformCondition Failed` | `ica32setting.cpp:394` | MCTF transform, with alignment matrix and grid both disabled. |
| `StoreNothingMeta() Get package name faled result -2` | `camxhwinterface.cpp:342` | Nothing per-package camera metadata; `-2` is `ENOENT`. |
| `releasePNC failed` | `camxchinodeswpnc.cpp:2441` | Teardown only. `com.qti.node.swpnc` consumes OIS samples, but its failure is in `LoadLib()` and is independent of the SOIS masks. |

## Not on this list

`PreLoadLiberary` and `PopulateFuseId` are established noise. So is
`Couldn't find file vendor.qti.camera.provider-service_64.farf` — a FastRPC
debug-logging config that is absent by design.

## Method

```sh
adb logcat -c && adb shell 'am start -a android.media.action.STILL_IMAGE_CAMERA'
timeout 30 adb logcat > cam.log
grep -E " E (CamX|ChiX)" cam.log | grep -viE "PreLoadLiberary|PopulateFuseId"
```

The screen must be awake — a dozing device accepts the intent and never starts
the HAL, which looks like a clean log.
