#!/bin/bash
# Full ROM build. Writes the exit code to logs/brunch.status and, on success,
# the artifact path to logs/brunch.zip.
#
# The OTA zip is removed first because releasetools rewrites it through the
# existing inode, which mutates every dated zip hardlinked onto it.
#
# Out-of-tree patches go on first. repo sync resets those projects, so they come
# off at every sync; apply.sh puts them back and fails loudly if any does not
# apply. A failed apply must stop the build -- an unpatched tree produces an
# image that looks correct and quietly lacks the change.
# Resolved through any symlink: ~/bin entries point here, and dirname on the
# link would give the link's directory, making $top / -- where cd succeeds and
# the failure only surfaces later, somewhere else.
here="$(cd "$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")" && pwd)"
top="${ANDROID_BUILD_TOP:-$(cd "$here/../../../.." && pwd)}"
[ -f "$top/build/envsetup.sh" ] || { echo "not an Android tree: $top" >&2; exit 1; }
logs="${FROGGER_LOGS:-$HOME/android/logs}"
mkdir -p "$logs"
cd "$top" || exit 1
rm -f "$logs/brunch.status" "$logs/brunch.zip"
rm -f out/target/product/frogger/lineage_frogger-ota.zip
{
    "$top/device/nothing/frogger/patches/apply.sh" &&
    "$top/device/nothing/frogger/tools/kernel-config-guard.sh" &&
    {
        source build/envsetup.sh
        breakfast frogger && brunch frogger
    }
} > "$logs/brunch.log" 2>&1
rc=$?
echo "BRUNCH_EXIT=$rc" > "$logs/brunch.status"

if [ "$rc" -eq 0 ]; then
    zip=$(sed -n "s/^Package Complete: //p" "$logs/brunch.log" | tail -1)
    if [ -n "$zip" ] && [ -f "$top/$zip" ]; then
        echo "$top/$zip" > "$logs/brunch.zip"
    fi
fi
