#!/bin/bash
# Sync the devicetrees repo and rebuild only the dtbo image.
# Resolved through any symlink: ~/bin entries point here, and dirname on the
# link would give the link's directory, making $top / -- where cd succeeds and
# the failure only surfaces later, somewhere else.
here="$(cd "$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")" && pwd)"
top="${ANDROID_BUILD_TOP:-$(cd "$here/../../../.." && pwd)}"
[ -f "$top/build/envsetup.sh" ] || { echo "not an Android tree: $top" >&2; exit 1; }
logs="${FROGGER_LOGS:-$HOME/android/logs}"
mkdir -p "$logs"
cd "$top" || exit 1
rm -f "$logs/dtbo.status" "$logs/dtbo.log"
{
    repo sync -c --no-clone-bundle --no-tags --force-sync \
        kernel/nothing/sm7635-devicetrees device/nothing/frogger
    source build/envsetup.sh
    breakfast frogger
    mka dtboimage
} > "$logs/dtbo.log" 2>&1
echo "DTBO_EXIT=$?" > "$logs/dtbo.status"
