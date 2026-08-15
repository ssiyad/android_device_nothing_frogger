#!/bin/bash
# Publish the ROM zip named in logs/brunch.zip, and its images zip, to the
# Caddy-served directory. Runs from publish-builds.timer every two minutes.
#
# Copies rather than hardlinks: a hardlink here is not a snapshot, because the
# next build rewrites the shared inode.
#
# This directory is managed entirely by this script. Anything in it that is not
# a lineage-*.zip or a checksum sidecar is deleted -- the Caddy listing prints
# a file name even when the path matcher refuses to serve it.
# Resolved through any symlink: ~/bin entries point here, and dirname on the
# link would give the link's directory, making $top / -- where cd succeeds and
# the failure only surfaces later, somewhere else.
here="$(cd "$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")" && pwd)"
top="${ANDROID_BUILD_TOP:-$(cd "$here/../../../.." && pwd)}"
[ -f "$top/build/envsetup.sh" ] || { echo "not an Android tree: $top" >&2; exit 1; }
logs="${FROGGER_LOGS:-$HOME/android/logs}"
mkdir -p "$logs"
set -u

STAMP=${STAMP:-$logs/brunch.zip}
DST=${DST:-${FROGGER_PUBLISH:-$HOME/builds}}

mkdir -p "$DST"
shopt -s nullglob

sha_of() { [ -f "$1" ] && awk "{print \$1}" "$1"; }

publish() {
    src=$1
    sum="$src.sha256sum"

    # A missing sidecar means the zip is still being written.
    [ -f "$src" ] && [ -f "$sum" ] || return 0

    dst="$DST/$(basename "$src")"
    [ "$(sha_of "$dst.sha256sum")" = "$(sha_of "$sum")" ] && return 0

    # Same name, different content: keep the older one.
    if [ -e "$dst" ]; then
        ts=$(date -r "$dst" +%Y%m%d-%H%M%S)
        base=$(basename "$src" .zip)
        mv -n "$dst" "$DST/$base-$ts.zip"
        [ -e "$dst.sha256sum" ] && mv -n "$dst.sha256sum" "$DST/$base-$ts.zip.sha256sum"
    fi

    tmp="$DST/.incoming.$$"
    if cp -f "$src" "$tmp"; then
        mv -f "$tmp" "$dst"
        cp -f "$sum" "$dst.sha256sum"
        chmod 644 "$dst" "$dst.sha256sum"
    else
        rm -f "$tmp"
    fi
}

if [ -f "$STAMP" ]; then
    rom=$(cat "$STAMP")
    publish "$rom"
    publish "${rom%.zip}-images.zip"
fi

for f in "$DST"/* "$DST"/.[!.]*; do
    case "${f##*/}" in
        lineage-*.zip|lineage-*.zip.md5sum|lineage-*.zip.sha256sum) ;;
        *) rm -rf -- "$f" ;;
    esac
done
