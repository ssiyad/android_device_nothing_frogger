#!/bin/bash
# Targeted vendor_dlkm build. Used to compile-check kernel module changes
# without paying for a full ROM build.
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
top="${ANDROID_BUILD_TOP:-$(cd "$here/../../../.." && pwd)}"
logs="${FROGGER_LOGS:-$HOME/android/logs}"
mkdir -p "$logs"
cd "$top" || exit 1
rm -f "$logs/vendordlkm.status"
{
    source build/envsetup.sh
    breakfast frogger && mka vendor_dlkmimage
} > "$logs/vendordlkm.log" 2>&1
echo "VENDORDLKM_EXIT=$?" > "$logs/vendordlkm.status"
