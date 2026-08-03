#!/bin/bash
# Publish finished Frogger ROM zips to the Caddy-served directory.
#
# COPIES, deliberately, not hardlinks. The packaging step rewrites the zip
# through the existing inode (verified: builds/ and out/ shared inode 9481229
# while the content changed under it), so a hardlink is not a snapshot -- the
# next build silently mutates an already-published ROM and the old one is
# unrecoverable. A copy costs ~1.6 GB per build, which is cheap against the
# free space here.
#
# NOTE: this directory is managed entirely by this script. Anything in it that
# is not a lineage-*.zip (or a checksum sidecar) is DELETED, because the Caddy
# directory listing would otherwise show the file name even though the path
# matcher refuses to serve its contents. Do not park anything here by hand.
set -u

SRC=/home/android-builder/android/lineage/out/target/product/frogger
DST=/home/android-builder/builds

mkdir -p "$DST"
shopt -s nullglob

sha_of() { [ -f "$1" ] && awk '{print $1}' "$1"; }

for f in "$SRC"/lineage-*.zip; do
    sum="$f.sha256sum"

    # The build writes the zip first and its checksum a few seconds later, so a
    # missing sidecar means the zip is still being written. Publishing it now
    # would serve a truncated download.
    [ -f "$sum" ] || continue

    dst="$DST/$(basename "$f")"

    # Already published -- compare checksums rather than recopy 1.6 GB every
    # time the timer fires.
    [ "$(sha_of "$dst.sha256sum")" = "$(sha_of "$sum")" ] && continue

    if [ -e "$dst" ]; then
        # Same name, different build. The zip name carries only a date, so any
        # rebuild on the same day collides. Keep the older ROM.
        ts=$(date -r "$dst" +%Y%m%d-%H%M%S)
        base=$(basename "$f" .zip)
        mv -n "$dst" "$DST/$base-$ts.zip"
        [ -e "$dst.sha256sum" ] && mv -n "$dst.sha256sum" "$DST/$base-$ts.zip.sha256sum"
    fi

    # Copy to a temp name and rename, so a half-copied zip is never served.
    tmp="$DST/.incoming.$$"
    if cp -f "$f" "$tmp"; then
        mv -f "$tmp" "$dst"
        cp -f "$sum" "$dst.sha256sum"
        chmod 644 "$dst" "$dst.sha256sum"
    else
        rm -f "$tmp"
    fi
done

# Prune anything that is not a published zip, so the listing cannot leak names.
# This also clears a .incoming.* left behind by an interrupted copy.
for f in "$DST"/* "$DST"/.[!.]*; do
    case "${f##*/}" in
        lineage-*.zip|lineage-*.zip.md5sum|lineage-*.zip.sha256sum) ;;
        *) rm -rf -- "$f" ;;
    esac
done
