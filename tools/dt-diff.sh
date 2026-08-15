#!/bin/bash
# Systematic diff of our built Frogger overlay against the shipping stock one.
# Both are the final merged overlay for board-id <11 0> / oem-id <1>.
#
# Note: node names must be compared with ALL leading whitespace stripped. The
# two overlays nest the same node at different depths, so comparing with tabs
# intact reports identical nodes as missing.
# Resolved through any symlink: ~/bin entries point here, and dirname on the
# link would give the link's directory, making $top / -- where cd succeeds and
# the failure only surfaces later, somewhere else.
here="$(cd "$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")" && pwd)"
top="${ANDROID_BUILD_TOP:-$(cd "$here/../../../.." && pwd)}"
[ -f "$top/build/envsetup.sh" ] || { echo "not an Android tree: $top" >&2; exit 1; }
logs="${FROGGER_LOGS:-$HOME/android/logs}"
mkdir -p "$logs"
OUT="$logs/dtdiff.txt"
exec > "$OUT" 2>&1

OURS=/tmp/dcheck/e.0.dts
STOCK=/tmp/dtbo/entry.66.dts
W=/tmp/dtdiff
rm -rf "$W"; mkdir -p "$W"

for f in "$OURS" "$STOCK"; do
    [ -f "$f" ] || { echo "MISSING: $f"; exit 1; }
done

syms() {
    sed -n '/__symbols__ {/,/^\t};/p' "$1" \
        | grep -oE '[A-Za-z_][A-Za-z0-9_]* =' \
        | sed 's/ =$//' | sort -u
}
nodes() {
    grep -oE '^[[:space:]]*[A-Za-z0-9_,.+@-]+ \{' "$1" \
        | sed -e 's/^[[:space:]]*//' -e 's/ {$//' \
        | grep -vE '^fragment@' | sort -u
}

syms "$OURS"   > "$W/ours.sym";   syms "$STOCK"  > "$W/stock.sym"
nodes "$OURS"  > "$W/ours.node";  nodes "$STOCK" > "$W/stock.node"

echo "=== size ==="
printf '  ours  : %6s lines, %4s symbols, %4s node names\n' \
    "$(wc -l < "$OURS")" "$(wc -l < "$W/ours.sym")" "$(wc -l < "$W/ours.node")"
printf '  stock : %6s lines, %4s symbols, %4s node names\n' \
    "$(wc -l < "$STOCK")" "$(wc -l < "$W/stock.sym")" "$(wc -l < "$W/stock.node")"

echo
echo "=== NODES IN STOCK BUT NOT OURS (possible gaps) ==="
comm -13 "$W/ours.node" "$W/stock.node" | sed 's/^/  /'

echo
echo "=== SYMBOLS IN STOCK BUT NOT OURS ==="
comm -13 "$W/ours.sym" "$W/stock.sym" | sed 's/^/  /'

echo
echo "=== NODES IN OURS BUT NOT STOCK (expect camera platform) ==="
comm -23 "$W/ours.node" "$W/stock.node" | head -60 | sed 's/^/  /'
echo "  ... $(comm -23 "$W/ours.node" "$W/stock.node" | wc -l) total"
