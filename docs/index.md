# Frogger

`device/nothing/frogger` — Nothing Phone (4a), `A069`.

Status and blockers live here; task files describe only the work.

## Tasks

### SELinux

| Task | Status | Blocked by |
|---|---|---|
| [Collect denials](tasks/selinux-denial-collection.md) | open | — |
| [Label sysfs nodes](tasks/selinux-sysfs-labels.md) | open | collect denials |
| [Write missing allow rules](tasks/selinux-allow-rules.md) | open | collect denials |
| [Switch to enforcing](tasks/selinux-enforcing.md) | blocked | label sysfs nodes, write allow rules |

### Thermal

| Task | Status | Blocked by |
|---|---|---|
| [Replace the source-built thermal HAL](tasks/thermal-hal.md) | deferred | — |
| [Resolve the duplicate `battery` zone](tasks/thermal-battery-zones.md) | open | — |

### Audio

| Task | Status | Blocked by |
|---|---|---|
| [Capture runs ~25 dB below other handsets](tasks/capture-gain-deficit.md) | open | — |
| [Make LVACFS follow the recording source](tasks/lvacfs-source-tracking.md) | open | — |

### Build and packaging

| Task | Status | Blocked by |
|---|---|---|
| [Version the extracted vendor blob tree](tasks/vendor-blob-tree.md) | open | — |
| [Sign vbmeta and decide on re-locking](tasks/avb-verified-boot.md) | open | — |
| [Make GApps survive a ROM flash](tasks/gapps-persistence.md) | open | — |
| [Clear bring-up switches before release](tasks/release-switches.md) | blocked | switch SELinux to enforcing |

### Features and cleanup

| Task | Status | Blocked by |
|---|---|---|
| [Enable Glyph LEDs](tasks/glyph-leds.md) | blocked | no upstream `ParanoidGlyphPhone4a` target |
| [Re-enable DeviceExtras](tasks/device-extras.md) | open | — |
| [Evaluate vendor property groups](tasks/vendor-properties.md) | open | — |
| [Express deferred devicetree items as overrides](tasks/devicetree-overrides.md) | open | — |
| [Reconcile the kernel module load lists](tasks/kernel-module-list.md) | open | — |
| [Remove the `ui_status` writes](tasks/fingerprint-ui-status.md) | open | — |
| [Add the Goodix touch panel driver](tasks/touchscreen-goodix.md) | blocked | no Goodix-panel unit |
| [Drop inert configuration](tasks/inert-config.md) | open | — |

## Reference

| Document | Contents |
|---|---|
| [hardware.md](reference/hardware.md) | Device-specific values: identity, display, sensors, SKUs |
| [audio.md](reference/audio.md) | Card layout, LVACFS, volume configuration |
| [display.md](reference/display.md) | Colour pipeline, DFPS constraints, panel feature attributes |
| [devicetree.md](reference/devicetree.md) | Overlay model, board-id matching, verification |
| [camera-diagnostics.md](reference/camera-diagnostics.md) | Reading camera failures by layer, and the errors a healthy session logs |
| [vendor-blobs.md](reference/vendor-blobs.md) | Blob list method, fixup scoping, inventory |
| [repositories.md](reference/repositories.md) | Forks, branches, signing keys |

## Data

| File | Contents |
|---|---|
| [data/vendor-missing.txt](data/vendor-missing.txt) | Stock vendor files not shipped here |
| [data/vendor-props-missing.txt](data/vendor-props-missing.txt) | Stock vendor properties not set here |
