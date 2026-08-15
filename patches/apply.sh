#!/bin/bash
#
# Applies the out-of-tree patches this device needs, against $ANDROID_BUILD_TOP.
# Run after every `repo sync` and before every build.
#
# `repo sync` runs with the force flag and resets these projects, so the patches
# have to go back on each time. That also means this script never has to undo
# anything: a synced project is always at its upstream state.
#
# Exits non-zero the moment anything fails to apply. A build must not proceed
# past that -- a silently unpatched tree produces an image that looks fine and
# is missing the change.

set -euo pipefail

top="${ANDROID_BUILD_TOP:-}"
if [ -z "$top" ]; then
    top="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
fi

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
rc=0

for dir in "$here"/*/; do
    # No patch directories at all: the glob stays literal and there is nothing to do.
    [ -d "$dir" ] || continue
    project="$(basename "$dir")"
    project="${project//_//}"
    if [ ! -d "$top/$project" ]; then
        echo "MISSING PROJECT: $project" >&2
        rc=1
        continue
    fi
    for patch in "$dir"*.patch; do
        [ -e "$patch" ] || continue
        name="$(basename "$patch")"
        if git -C "$top/$project" apply --check "$patch" 2>/dev/null; then
            git -C "$top/$project" apply "$patch"
            echo "applied   $project  $name"
        elif git -C "$top/$project" apply --reverse --check "$patch" 2>/dev/null; then
            echo "already   $project  $name"
        else
            echo "FAILED    $project  $name" >&2
            rc=1
        fi
    done
done

if [ "$rc" -ne 0 ]; then
    echo >&2
    echo "One or more patches did not apply. Do not build." >&2
    exit 1
fi
