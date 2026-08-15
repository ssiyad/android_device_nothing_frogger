#!/bin/bash
# Gather everything about the built dtbo in one pass, so a flaky link only has
# to survive one round trip.
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
top="${ANDROID_BUILD_TOP:-$(cd "$here/../../../.." && pwd)}"
logs="${FROGGER_LOGS:-$HOME/android/logs}"
mkdir -p "$logs"
OUT="$logs/dtbocheck.txt"
P="$top/out/target/product/frogger"
exec > "$OUT" 2>&1

echo "=== dtbo artifacts, newest first ==="
find "$P" -name '*.dtbo' -printf '%TH:%TM %s %p\n' 2>/dev/null | sort -r | head -10

echo
echo "=== dtbo.img ==="
ls -l --time-style=+%H:%M "$P/dtbo.img"

echo
echo "=== entries in the built dtbo.img and their camera content ==="
rm -rf /tmp/dcheck && mkdir -p /tmp/dcheck && cd /tmp/dcheck || exit
python3 "$top/system/libufdt/utils/src/mkdtboimg.py" \
    dump "$P/dtbo.img" -b e >/dev/null 2>&1
for f in e.*; do
    [ -f "$f" ] || continue
    dtc -I dtb -O dts "$f" 2>/dev/null > "$f.dts"
    sensors=$(grep -c 'qcom,cam-sensor[0-9]' "$f.dts")
    cci=$(grep -c 'cam_cci' "$f.dts")
    bid=$(grep -m1 -oE 'qcom,board-id = <[^>]*>' "$f.dts")
    mid=$(grep -m1 -oE 'qcom,msm-id = <[^>]*>' "$f.dts")
    model=$(grep -m1 -oE 'model = "[^"]*"' "$f.dts")
    printf '%-6s sensors=%-3s cci=%-3s %s %s %s\n' \
        "$f" "$sensors" "$cci" "$bid" "$model" "$mid"
done
