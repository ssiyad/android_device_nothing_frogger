#!/bin/bash
# Targeted sepolicy build. Catches neverallow violations without a full ROM build.
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
top="${ANDROID_BUILD_TOP:-$(cd "$here/../../../.." && pwd)}"
logs="${FROGGER_LOGS:-$HOME/android/logs}"
mkdir -p "$logs"
cd "$top" || exit 1
rm -f "$logs/sepolicy.status"
{
    source build/envsetup.sh
    breakfast frogger && mka selinux_policy
} > "$logs/sepolicy.log" 2>&1
echo "SEPOLICY_EXIT=$?" > "$logs/sepolicy.status"
