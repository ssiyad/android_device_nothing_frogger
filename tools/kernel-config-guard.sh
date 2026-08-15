#!/bin/bash
#
# Wipes KERNEL_OBJ when the kernel's configuration inputs change, because the
# build system does not.
#
# TARGET_KERNEL_CONFIG is a list of defconfig fragments, and they are merged
# onto whatever .config is already sitting in KERNEL_OBJ. Change the list, or
# the contents of a fragment, and two things happen quietly: a symbol the
# removed fragment set stays set, because merging never unsets anything, and
# arch/arm64/boot/Image is not recompiled at all. The build then succeeds and
# ships the previous kernel. Nothing warns, and out/.../kernel is rewritten
# every build whether or not the kernel was rebuilt, so it looks fresh either
# way. The only tell is a /proc/version older than the ROM.
#
# That cost two builds that were made specifically to test a kernel with a
# fragment removed, and neither had removed anything.
#
# Run before a build. Idempotent, and cheap when nothing changed.

set -euo pipefail

# Resolved through any symlink: ~/bin entries point here, and dirname on the
# link would give the link's directory, making $top / -- where cd succeeds and
# the failure only surfaces later, somewhere else.
here="$(cd "$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")" && pwd)"
top="${ANDROID_BUILD_TOP:-$(cd "$here/../../../.." && pwd)}"
[ -f "$top/build/envsetup.sh" ] || { echo "not an Android tree: $top" >&2; exit 1; }

board="$here/../BoardConfig.mk"
obj="$top/out/target/product/frogger/obj/KERNEL_OBJ"
stamp="$obj/.kernel-config-inputs"

[ -f "$board" ] || { echo "no BoardConfig.mk at $board" >&2; exit 1; }

# The assignment spans lines with trailing backslashes; take it whole so that
# reordering or reformatting the list counts as a change too.
block="$(awk '
    /^TARGET_KERNEL_CONFIG[ \t]*:=/ { f = 1 }
    f { print; if ($0 !~ /\\[ \t]*$/) exit }
' "$board")"

source_rel="$(awk -F':=' '
    /^TARGET_KERNEL_SOURCE[ \t]*:=/ { gsub(/[ \t]/, "", $2); print $2; exit }
' "$board")"

configs="$top/$source_rel/arch/arm64/configs"

# Everything after the := , with the line continuations dropped.
fragments="$(printf '%s\n' "$block" | sed -e 's/^TARGET_KERNEL_CONFIG[ \t]*:=//' -e 's/\\[ \t]*$//')"

inputs="$(
    printf '%s\n' "$block"
    for fragment in $fragments; do
        path="$configs/$fragment"
        if [ -f "$path" ]; then
            printf '%s  ' "$(sha256sum < "$path" | cut -d' ' -f1)"
            printf '%s\n' "$fragment"
        else
            # A fragment that is missing now and present later has to register
            # as a change, so it goes into the hash rather than being skipped.
            printf 'absent  %s\n' "$fragment"
        fi
    done
)"

current="$(printf '%s' "$inputs" | sha256sum | cut -d' ' -f1)"

# Everything below reports on stderr and says nothing when there is nothing to
# do. It is called from BoardConfig.mk through $(shell), whose stdout make
# parses as makefile text -- a single line on stdout there fails the build with
# "Failed to parse make line".
if [ -f "$stamp" ] && [ "$(cat "$stamp")" = "$current" ]; then
    exit 0
fi

if [ -d "$obj" ]; then
    echo "kernel config changed -- clearing KERNEL_OBJ" >&2
    rm -rf "$obj"
fi

mkdir -p "$obj"
printf '%s\n' "$current" > "$stamp"
