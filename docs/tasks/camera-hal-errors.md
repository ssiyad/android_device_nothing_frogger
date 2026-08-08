# Explain the errors a healthy camera session still logs

Preview opens, streams and tears down, and every `DT_NEEDED` of every camera blob
resolves on the device. These are what a working session logs anyway. None is
known to break a user-visible feature; none is known to be harmless either.

Captured from preview sessions on the rear camera (`cam_1`, GCam) and the tele
(`cam_3`, Aperture). Occurrence counts are from the `cam_1` session; counts do
not carry across sensors or apps.

| Error | Source | Reading |
|---|---|---|
| `Failed to open /proc/reserve_pool/inpool_pid_0` | `chxextensionmodule.cpp:2796` | The Nothing kernel's camera memory reserve pool. No `reserve_pool` anywhere in `kernel/nothing/sm7635`, so the node cannot exist. 54 occurrences in one session. |
| `Unsupported stats FD mode value: 128` | `camxfdmanagernode.cpp:5291` | Logged once per frame. `ANDROID_STATISTICS_FACE_DETECT_MODE` is 0, 1 or 2, so 128 is an OEM extension the shipped FD node rejects. Confirm whether face detection works before treating it as noise. |
| `Invalid FD processing type: 3` | `camxfdutils.cpp:1363` | Same subsystem, once per session. |
| `PDLibSensorType is Invalid` | `camxtfenode.cpp:11483` | All four `pdlib` components ship. Points at sensor module data rather than a missing blob. PDAF may be degraded. |
| `Invalid pointer pHwCfgWrapper 0x0` | `camxnodelegacy.cpp:23305` | `GetOEMFeatureTypeMask()` on the ZSL preview pipeline. |
| `CheckMctfTransformCondition Failed` | `ica32setting.cpp:394` | MCTF transform, with alignment matrix and grid both disabled. |
| `StoreNothingMeta() Get package name faled result -2` | `camxhwinterface.cpp:342` | Nothing per-package camera metadata; `-2` is `ENOENT`. |
| `releasePNC failed` | `camxchinodeswpnc.cpp:2441` | Teardown only. `com.qti.node.swpnc` consumes OIS samples, but its failure is in `LoadLib()` and is independent of the SOIS masks — it persists with the masks at their stock `0x9`. |
| `Sensor[3]: Current DAC Ratio N is not equal to last DAC Ratio M` | `camxsensornode.cpp:4225` | Once per AF step on the tele, ~160 in a short preview. Slot 3 has an actuator but no `nt_sois-supply`, so nothing stabilises it. Establish whether this is normal AF chatter before treating it as a fault. |

## Not on this list

`PreLoadLiberary` and `PopulateFuseId` are established noise. So is
`Couldn't find file vendor.qti.camera.provider-service_64.farf` — a FastRPC
debug-logging config that is absent by design.

## Method

```sh
adb logcat -c
adb shell 'input keyevent KEYCODE_WAKEUP'
adb shell 'am start -n org.lineageos.aperture/.CameraLauncher'
adb shell 'dumpsys window | grep mCurrentFocus'   # confirm the camera came up
timeout 30 adb logcat > cam.log
grep -E " E (CamX|ChiX)" cam.log | grep -viE "PreLoadLiberary|PopulateFuseId"
```

Name the component. The `android.media.action.STILL_IMAGE_CAMERA` action hits a
chooser and lands on a gallery, which looks like a clean log — see
[camera-diagnostics.md](../reference/camera-diagnostics.md).

The screen must be awake for the same reason: a dozing device accepts the intent
and never starts the HAL.
