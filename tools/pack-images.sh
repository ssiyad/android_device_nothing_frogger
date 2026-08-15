#!/bin/bash
# Package the fastboot-flashable images for the ROM named in logs/brunch.zip,
# next to it in out/ and under its name, with a checksum sidecar.
# Resolved through any symlink: ~/bin entries point here, and dirname on the
# link would give the link's directory, making $top / -- where cd succeeds and
# the failure only surfaces later, somewhere else.
here="$(cd "$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")" && pwd)"
top="${ANDROID_BUILD_TOP:-$(cd "$here/../../../.." && pwd)}"
[ -f "$top/build/envsetup.sh" ] || { echo "not an Android tree: $top" >&2; exit 1; }
logs="${FROGGER_LOGS:-$HOME/android/logs}"
mkdir -p "$logs"
set -u

P=$top/out/target/product/frogger
STAMP=${STAMP:-$logs/brunch.zip}

[ -f "$STAMP" ] || { echo "no finished build recorded in $STAMP" >&2; exit 1; }
rom=$(cat "$STAMP")
[ -f "$rom" ] || { echo "$rom is gone" >&2; exit 1; }
out="${rom%.zip}-images.zip"

TMP=$(mktemp -d)
trap "rm -rf $TMP" EXIT

cd "$P" || exit 1
for f in boot.img dtbo.img vendor_boot.img recovery.img init_boot.img \
         vbmeta.img vbmeta_system.img vbmeta_vendor.img super_empty.img; do
    [ -f "$f" ] && cp "$f" "$TMP/"
done

cd "$TMP" || exit 1
sha256sum ./*.img > SHA256SUMS
zip -q -r "$TMP/images.zip" ./*.img SHA256SUMS || exit 1

mv -f "$TMP/images.zip" "$out" || exit 1
( cd "${out%/*}" && sha256sum "${out##*/}" > "${out##*/}.sha256sum" )
chmod 644 "$out" "$out.sha256sum"
ls -l --time-style=+%H:%M "$out"
