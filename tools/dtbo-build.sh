#!/bin/bash
# Sync the devicetrees repo and rebuild only the dtbo image.
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
top="${ANDROID_BUILD_TOP:-$(cd "$here/../../../.." && pwd)}"
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
