# Frogger

`device/nothing/frogger` — Nothing Phone (4a), `A069`.

Status and blockers live here; task files describe only the work.

## Tasks

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
| [Verify the Glyph indicators](tasks/glyph-indicators.md) | open | — |
| [Further Glyph indicators](tasks/glyph-ideas.md) | open | — |
| [Re-enable DeviceExtras](tasks/device-extras.md) | open | — |
| [Evaluate vendor property groups](tasks/vendor-properties.md) | open | — |
| [Express deferred devicetree items as overrides](tasks/devicetree-overrides.md) | open | — |
| [Reconcile the kernel module load lists](tasks/kernel-module-list.md) | open | — |
| [Remove the `ui_status` writes](tasks/fingerprint-ui-status.md) | open | — |
| [Add the Goodix touch panel driver](tasks/touchscreen-goodix.md) | blocked | no Goodix-panel unit |
| [Drop inert configuration](tasks/inert-config.md) | open | — |
| [The `navigationBars` inset is empty](tasks/navigation-bar-inset.md) | parked | upstream; no device-tree lever |

## Reference

| Document | Contents |
|---|---|
| [hardware.md](reference/hardware.md) | Device-specific values: identity, display, sensors, SKUs |
| [selinux.md](reference/selinux.md) | Where a label comes from, and which denials stay denied |
| [selinux-collection.md](reference/selinux-collection.md) | Stripping `dontaudit`, the collector, and reading a log honestly |
| [audio.md](reference/audio.md) | Card layout, LVACFS, volume configuration |
| [thermal.md](reference/thermal.md) | HAL provenance, zone lookup, what mitigates |
| [display.md](reference/display.md) | Colour pipeline, DFPS constraints, panel feature attributes |
| [devicetree.md](reference/devicetree.md) | Overlay model, board-id matching, verification |
| [camera-diagnostics.md](reference/camera-diagnostics.md) | Reading camera failures by layer, and the errors a healthy session logs |
| [camera-image-quality.md](reference/camera-image-quality.md) | Where the picture quality lives, which levers exist, and which are dead ends |
| [vendor-blobs.md](reference/vendor-blobs.md) | Blob list method, fixup scoping, inventory |
| [build-config.md](reference/build-config.md) | API levels, SELinux mode, switches that must not be tidied away |
| [repositories.md](reference/repositories.md) | Forks, branches, signing keys |

## Data

| File | Contents |
|---|---|
| [data/vendor-missing.txt](data/vendor-missing.txt) | Stock vendor files not shipped here |
| [data/vendor-props-missing.txt](data/vendor-props-missing.txt) | Stock vendor properties not set here |
