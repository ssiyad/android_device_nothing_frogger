#!/bin/bash
# Compare our built camera device tree against the shipping stock overlay.
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
top="${ANDROID_BUILD_TOP:-$(cd "$here/../../../.." && pwd)}"
logs="${FROGGER_LOGS:-$HOME/android/logs}"
mkdir -p "$logs"
OUT="$logs/camcmp.txt"
exec > "$OUT" 2>&1

OURS=/tmp/dcheck/e.0.dts
STOCK=/tmp/dtbo/entry.66.dts

for f in "$OURS" "$STOCK"; do
    [ -f "$f" ] || { echo "MISSING: $f"; exit 1; }
done

summarise() {
    local f=$1 label=$2
    echo "=== $label ==="
    echo -n "  sensor nodes:  "; grep -cE '^\s+qcom,cam-sensor[0-9]+ \{' "$f"
    echo -n "  eeprom nodes:  "; grep -cE '^\s+qcom,eeprom[0-9]+ \{' "$f"
    echo -n "  actuator nodes:"; grep -cE '^\s+qcom,actuator[0-9]+ \{' "$f"
    echo -n "  flash nodes:   "; grep -cE '^\s+qcom,camera-flash[0-9]+ \{' "$f"
    echo -n "  cci nodes:     "; grep -cE '^\s+qcom,cci[0-9]+@' "$f"
    echo -n "  csiphy nodes:  "; grep -cE '^\s+qcom,csiphy[0-9]+@' "$f"
    echo "  csiphy-sd-index per sensor:"
    awk '/^[[:space:]]+qcom,cam-sensor[0-9]+ \{/{n=$1}
         /csiphy-sd-index/{printf "    %s %s\n", n, $0}' "$f" | tr -s ' '
    echo
}

summarise "$OURS"  "OURS (built dtbo.img entry 0)"
summarise "$STOCK" "STOCK (dtbo entry.66)"
