# Frogger

`device/nothing/frogger` — Nothing Phone (4a), `A069`.

Status and blockers live here; task files describe only the work.

## Tasks

### Reliability

| Task | Status | Blocked by |
|---|---|---|
| [Catch the reboot to the crash dump screen](tasks/crash-dump-reboot.md) | open | — |

### Audio

| Task | Status | Blocked by |
|---|---|---|
| [Capture runs ~25 dB below other handsets](tasks/capture-gain-deficit.md) | parked | no stock reference |

### Camera

| Task | Status | Blocked by |
|---|---|---|
| [Get the best achievable stills](tasks/camera-image-quality.md) | open | — |

### Build and packaging

| Task | Status | Blocked by |
|---|---|---|
| [Version the extracted vendor blob tree](tasks/vendor-blob-tree.md) | open | — |
| [Sign vbmeta and decide on re-locking](tasks/avb-verified-boot.md) | open | — |
| [Make GApps survive a ROM flash](tasks/gapps-persistence.md) | open | — |

### Features and cleanup

| Task | Status | Blocked by |
|---|---|---|
| [Ship a way to calibrate the proximity part](tasks/proximity-calibration.md) | open | — |
| [Verify the Glyph indicators](tasks/glyph-indicators.md) | open | — |
| [Is SFDC f0 tracking actually running?](tasks/haptic-sfdc.md) | open | — |
| [Screen recording from the Essential Button](tasks/essential-button-screen-record.md) | parked | needs a SystemUI patch or a recorder of our own |
| [Further Glyph indicators](tasks/glyph-ideas.md) | open | — |
| [Features worth taking from elsewhere](tasks/ported-features.md) | open | — |
| [Re-enable DeviceExtras](tasks/device-extras.md) | open | — |
| [Express deferred devicetree items as overrides](tasks/devicetree-overrides.md) | open | — |
| [Add the Goodix touch panel driver](tasks/touchscreen-goodix.md) | blocked | no Goodix-panel unit |
| [The lock screen clock falls back to Roboto](tasks/lockscreen-clock-font.md) | parked | needs a Lineage-side file; no cheap mechanism |
| [The biometric prompt's indicator runs off the bottom](tasks/biometric-prompt-indicator.md) | parked | needs a SystemUI layout copy; not worth the drift |

## Reference

| Document | Contents |
|---|---|
| [hardware.md](reference/hardware.md) | Device-specific values: identity, display, sensors, SKUs |
| [kernel-modules.md](reference/kernel-modules.md) | Why the load lists are correct, and what is built but not loaded |
| [selinux.md](reference/selinux.md) | Where a label comes from, and which denials stay denied |
| [selinux-collection.md](reference/selinux-collection.md) | Stripping `dontaudit`, the collector, and reading a log honestly |
| [system-bars.md](reference/system-bars.md) | Status bar height, shade header alignment, and what drives the bottom inset |
| [audio.md](reference/audio.md) | Card layout, LVACFS, volume configuration |
| [thermal.md](reference/thermal.md) | HAL provenance, zone lookup, what mitigates |
| [display.md](reference/display.md) | Colour pipeline, DFPS constraints, panel feature attributes |
| [proximity.md](reference/proximity.md) | Where the part runs, the calibration that outlives a flash, reading the in-call path |
| [glyph.md](reference/glyph.md) | The strip, the gate, what drives each indicator, and the dead ends |
| [essential-button.md](reference/essential-button.md) | The button, why the keycode is `MACRO_1`, the gestures and the actions |
| [devicetree.md](reference/devicetree.md) | Overlay model, board-id matching, verification |
| [camera-diagnostics.md](reference/camera-diagnostics.md) | Reading camera failures by layer, and the errors a healthy session logs |
| [camera-image-quality.md](reference/camera-image-quality.md) | Where the picture quality lives, which levers exist, and which are dead ends |
| [vendor-blobs.md](reference/vendor-blobs.md) | Blob list method, fixup scoping, inventory |
| [vendor-properties.md](reference/vendor-properties.md) | Why none of the stock property gap is worth adopting, and how to check |
| [build-config.md](reference/build-config.md) | API levels, SELinux mode, switches that must not be tidied away |
| [play-integrity.md](reference/play-integrity.md) | Root via KernelSU-Next, the attestation stack, and the keybox reality |
| [repositories.md](reference/repositories.md) | Forks, branches, signing keys |

## Data

| File | Contents |
|---|---|
| [data/vendor-missing.txt](data/vendor-missing.txt) | Stock vendor files not shipped here |
| [data/vendor-props-missing.txt](data/vendor-props-missing.txt) | Stock vendor properties not set here |
