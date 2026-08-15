#!/bin/bash
# Targeted vendor_dlkm build. Used to compile-check kernel module changes
# without paying for a full ROM build.
# Resolved through any symlink: ~/bin entries point here, and dirname on the
# link would give the link's directory, making $top / -- where cd succeeds and
# the failure only surfaces later, somewhere else.
here="$(cd "$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")" && pwd)"
top="${ANDROID_BUILD_TOP:-$(cd "$here/../../../.." && pwd)}"
[ -f "$top/build/envsetup.sh" ] || { echo "not an Android tree: $top" >&2; exit 1; }
logs="${FROGGER_LOGS:-$HOME/android/logs}"
mkdir -p "$logs"
cd "$top" || exit 1
rm -f "$logs/vendordlkm.status"
{
    source build/envsetup.sh
    breakfast frogger && mka vendor_dlkmimage
} > "$logs/vendordlkm.log" 2>&1
echo "VENDORDLKM_EXIT=$?" > "$logs/vendordlkm.status"
