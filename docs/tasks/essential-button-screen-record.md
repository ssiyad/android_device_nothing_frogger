# Screen recording from the Essential Button

The one action from the original list the button cannot reach. Everything about
the button itself is in [essential-button.md](../reference/essential-button.md);
this is only about the missing action.

## Why it does not ship

Screen recording needs a `MediaProjection`, and the only thing that mints one is
the consent flow. `RecordingService.ACTION_START` takes the result of that flow
as an extra, so it cannot be started cold.

Nothing exposes the flow from outside SystemUI:

- Every SystemUI component is `exported="false"`, and the recorder has no
  broadcast action at all.
- `ScreenRecordUxController.onScreenRecordQsTileClick()` is what the quick
  settings tile calls, and it is internal.
- `StatusBarManager` has no tile-click API — `clickTile` does not exist here, and
  the screen recorder is an internal `QSTile` rather than a `TileService`, so it
  would not reach it even if it did.

Same-UID callers can reach a non-exported component, and SystemUI shares
`android.uid.system` with us, so `ACTION_STOP` is reachable. Starting is not.

## The two ways out

**A receiver inside SystemUI.** About thirty lines: a non-exported receiver that
calls `onScreenRecordQsTileClick()`. It reuses AOSP's recorder, its notification
and its stop affordance, and it is the smallest correct answer. The cost is the
first `frameworks/base` patch this tree carries — `patches/apply.sh` has the
machinery and no patch directories yet — to be rebased on every sync.

**A recorder of our own.** The app is platform-signed, so it can hold
`CAPTURE_VIDEO_OUTPUT` and drive `MediaProjection` with no consent dialog at all.
No patches, but it means reimplementing the `VirtualDisplay` and `MediaRecorder`
plumbing, the ongoing notification, the stop control and the `MediaStore` insert
that SystemUI already has.

## Meanwhile

*Open application* reaches any recorder that is installed, which is the escape
hatch the action list is built around.
