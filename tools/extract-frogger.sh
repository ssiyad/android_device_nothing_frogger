#!/bin/bash
# Re-run extract-files.py against the pinned stock images. Regenerates
# vendor/nothing/frogger, which is not a git or repo project.
#
# The images are the nothing_archive release for the pinned build, unpacked
# into $IMAGES and checked against its sha256 list. No full OTA zip is
# published for it. extract-files.py unpacks a directory source in place and
# deletes each image as it goes, so it is given a fresh copy, never $IMAGES.
# Resolved through any symlink: ~/bin entries point here, and dirname on the
# link would give the link's directory, making $top / -- where cd succeeds and
# the failure only surfaces later, somewhere else.
here="$(cd "$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")" && pwd)"
top="${ANDROID_BUILD_TOP:-$(cd "$here/../../../.." && pwd)}"
[ -f "$top/build/envsetup.sh" ] || { echo "not an Android tree: $top" >&2; exit 1; }
logs="${FROGGER_LOGS:-$HOME/android/logs}"
mkdir -p "$logs"
downloads="${FROGGER_DOWNLOADS:-$HOME/android/downloads}/frogger"
IMAGES="$downloads/260911-0959-images/img"
DUMP="$downloads/260911-0959-dump"
[ -f "$IMAGES/vendor.img" ] || { echo "no stock images in $IMAGES" >&2; exit 1; }
cd "$top/device/nothing/frogger" || exit 1
rm -f "$logs/extract.status"
{
    rm -rf "$DUMP" && mkdir -p "$DUMP" &&
    cp --reflink=auto "$IMAGES"/*.img "$DUMP"/ &&
    PYTHONPATH="$top/tools/extract-utils" python3 extract-files.py "$DUMP"
} > "$logs/extract.log" 2>&1
echo "EXTRACT_EXIT=$?" > "$logs/extract.status"
