# Build server

Builds moved off the laptop after `soong_build` was OOM-killed there
(`ninja failed with: exit status 137`, maxrss 11861 MB against 12412 MB
available). Soong exposes no `GOGC`/`GOMEMLIMIT` knob, so there was nothing to
tune — the analysis phase simply needs more RAM than the laptop has.

## Access and layout

`android-builder@build.ssiyad.com` — Ubuntu 24.04.4, 12 cores, 62 GB RAM,
32 GB swap. The user has passwordless sudo and the same SSH key.

| Path | What |
|---|---|
| `~/android/lineage` | source tree |
| `~/android/downloads/frogger` | firmware zip and extraction log |
| `~/android/logs` | `brunch.status` — holds `BRUNCH_EXIT=<code>` |

`systemd-oomd` is **inactive**, so the kernel OOM killer is the only reaper.
With 62 GB against Soong's ~12 GB peak there is ample headroom; the laptop's
failure does not recur.

### Storage

The two SSDs shipped as a RAID1 mirror. Split into two independent filesystems
to get the full capacity: 412 GB `/` and 436 GB `/home/android-builder`. The
array was left to finish its initial resync before `nvme0n1p3` was removed, and
the removed member was verified not to be the disk carrying live root.

## Workflow

**All edits happen on the laptop.** The server only syncs, builds, and gets host
packages installed. Editing source there would split the source of truth and the
change would vanish on the next sync.

```
laptop: edit → commit → push
server: repo sync <project> → brunch
```

## Running a build

Everything long-running goes in `screen`, so it survives a dropped SSH
connection and can be watched directly:

```sh
screen -dmS frogger bash -lc '
  cd ~/android/lineage &&
  source build/envsetup.sh &&
  breakfast frogger && brunch frogger
  echo BRUNCH_EXIT=$? > ~/android/logs/brunch.status
  exec bash
'
```

Attach with `ssh -t android-builder@build.ssiyad.com screen -r frogger`,
detach with `Ctrl-A d`. Poll `~/android/logs/brunch.status` for completion, and
read `out/error.log` on failure.

Do not pipe the build through `tail` or append `echo` — that masks the exit
code and has twice made a failed build look successful.

## Blobs are regenerated on the server

Do **not** rsync `vendor/nothing/frogger` from the laptop. 1.8 GB over a home
uplink stalled at ~300 MB. The server pulls the firmware itself:

```sh
curl -L --retry 5 -C - -o Frogger_B4.1-260309-1830.zip \
  https://archive.org/download/nothing-archive/spike0en/fullota/frogger/Frogger_B4.1-260309-1830.zip
```

4 991 575 494 bytes, build `2603091830` — the same build the blob list was
validated against. It is a payload.bin OTA, which `extract-utils` unpacks
natively, so it can be fed to `extract-files.py` directly:

```sh
cd ~/android/lineage/device/nothing/frogger
PYTHONPATH=../../../tools/extract-utils python3 extract-files.py --keep-dump \
  ~/android/downloads/frogger/Frogger_B4.1-260309-1830.zip
```

Result: 1616 blobs, 22 firmware images, 1.8 GB — identical to the laptop copy,
with zero unresolved entries and all blob fixups applied. `Partition recovery
not extracted` is expected; this is an A/B device and recovery lives in
`vendor_boot`.

### Host prerequisites

`fsck.erofs` is invoked from `$PATH`, not from `prebuilts/`, and Ubuntu does not
ship it by default:

```sh
sudo apt-get install -y erofs-utils
```

Everything else `extract-utils` needs (`ota_extractor`, `brotli`, `patchelf`,
`llvm-objdump`, jdk21, `unpack_bootimg.py`) comes from the tree.

## Downloading built zips

<https://build.ssiyad.com> serves a plain directory listing of finished ROM
zips over automatic HTTPS (Caddy, Let's Encrypt). No authentication — anyone
with the hostname can download.

It serves **only** ROM zips. Two independent mechanisms enforce that:

1. `/home/android-builder/builds` is managed solely by
   `~/bin/publish-builds.sh`, which hardlinks `lineage-*.zip` (plus checksum
   sidecars) out of `out/target/product/frogger` and **deletes anything else it
   finds there**. Hardlinks cost no extra disk — same filesystem — and keep a
   published zip alive after the next build wipes `out/`.
2. The Caddyfile matches `/ *.zip *.zip.md5sum *.zip.sha256sum` and returns 404
   for everything else.

Both are needed. The path matcher alone blocks *downloads* of a stray file but
Caddy's directory listing still prints its **name**, so the prune is what
actually keeps non-build files from being disclosed. `hide` is a blocklist and
cannot express "only these", hence the allowlist-by-pruning approach.

`publish-builds.timer` runs the script every two minutes, so a zip shows up
shortly after the build writes it. To publish immediately:

```sh
sudo systemctl start publish-builds.service
```

### Gotcha: `caddy validate` provisions the config

`sudo caddy validate --config /etc/caddy/Caddyfile` does not merely parse — it
provisions, which **creates the access log as root**. The service then fails to
start with a confusing `permission denied` on a directory the `caddy` user
demonstrably owns:

```
open /var/log/caddy/access.log: permission denied
```

The directory is fine; the file inside it is `root:root 0600`. Fix:

```sh
sudo chown caddy:caddy /var/log/caddy/access.log
```

Run `caddy validate` as the `caddy` user, or clean up after it.

## Gotcha: verify the sync is complete

The first `brunch` here failed in Soong with:

```
packages/modules/adb/Android.bp:148:9: failed to open "system/apex/Android.bp":
  no such file or directory
```

`system/apex` had silently failed to sync — 1 project missing out of 1169. A
`repo sync` that prints "finished successfully" is not proof every project
landed. To check:

```sh
cd ~/android/lineage
repo list -p | while read -r p; do [ -d "$p" ] || echo "MISSING: $p"; done
```
