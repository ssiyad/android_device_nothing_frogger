#!/bin/bash
# Re-run extract-files.py against the pinned stock OTA. Regenerates
# vendor/nothing/frogger, which is not a git or repo project.
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
top="${ANDROID_BUILD_TOP:-$(cd "$here/../../../.." && pwd)}"
logs="${FROGGER_LOGS:-$HOME/android/logs}"
mkdir -p "$logs"
ZIP="${FROGGER_DOWNLOADS:-$HOME/android/downloads}/frogger/Frogger_B4.1-260309-1830.zip"
cd "$top/device/nothing/frogger" || exit 1
rm -f "$logs/extract.status"
{
    export PYTHONPATH="$top/tools/extract-utils"
    python3 extract-files.py "$ZIP"
} > "$logs/extract.log" 2>&1
echo "EXTRACT_EXIT=$?" > "$logs/extract.status"
