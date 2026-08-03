#!/bin/bash
# Publish finished Frogger ROM zips to the Caddy-served directory.
#
# Hardlinks rather than copies: out/ and builds/ are on the same filesystem, so
# this costs no extra disk. It also means a published zip survives the next
# build wiping out/ -- the inode stays alive until builds/ drops it too.
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

# Publish
for f in "$SRC"/lineage-*.zip "$SRC"/lineage-*.zip.md5sum "$SRC"/lineage-*.zip.sha256sum; do
    dst="$DST/$(basename "$f")"
    if [ -e "$dst" ] && [ "$dst" -ef "$f" ]; then
        continue
    fi
    ln -f "$f" "$dst" 2>/dev/null || cp -f "$f" "$dst"
    chmod 644 "$dst"
done

# Prune anything that is not a published zip, so the listing cannot leak names.
for f in "$DST"/* "$DST"/.[!.]*; do
    case "${f##*/}" in
        lineage-*.zip|lineage-*.zip.md5sum|lineage-*.zip.sha256sum)
            ;;
        *)
            rm -rf -- "$f"
            ;;
    esac
done
