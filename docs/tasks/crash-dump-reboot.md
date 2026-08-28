# Catch the reboot to the crash dump screen

The device reboots at random into the crash dump screen XBL puts up ahead of the
bootloader. No trigger is known, so the first job is not to debug it but to make
sure the next one leaves something to read. Right now it would not.

## Nothing records a panic yet

`/sys/module/ramoops/parameters/record_size` reads 0 on the device, and
`noth/frogger-common.dtsi` says why. The ramoops node divides its 4 MB carveout
into 2 MB of console and 2 MB of pmsg and sets no `record-size`. pstore gives
the dmesg zone whatever console and pmsg leave behind, which is nothing, so
there is no dmesg zone at all: a panic writes no record, and `/sys/fs/pstore`
comes back holding only `pmsg-ramoops-0`.

The devicetree repo is ours, so the fix is a `record-size` and a megabyte taken
back — from pmsg rather than console, since console is the previous boot's
kernel log and is what a boot failure is read from. It needs `dtbo-build.sh`
and a flash before any of the capture advice below applies.

`console-ramoops-0` is missing too, which the layout does not explain:
`console-size` is 2 MB, `CONFIG_PSTORE_CONSOLE=y`, and the device has been up
since a warm reboot, so the previous boot's console should be there. Establish
what happens to it before relying on it — a dmesg zone that lands next to a
console log that never appears is half a fix.

## When it is recording

**The obvious way out of that screen destroys the evidence.** pstore lives in
RAM at `0x81f20000` and survives a warm reboot but not a power-hold, which is a
PMIC-level reset — so holding power to escape the crash dump screen erases the
backtrace that explains it. Leave the screen with a warm reboot into LineageOS
recovery, which has a root shell where stock recovery has only sideload, and run
`tools/grab-logs.sh` from there.

| Entry | Holds |
|---|---|
| `dmesg-ramoops-*` | the panic backtrace — the one that matters, and the one that does not exist yet |
| `console-ramoops-*` | the previous boot's console, for what led up to it |
| `pmsg-ramoops-*` | the previous boot's userspace ring buffer |

`pstore.kmsg_bytes` caps each dmesg record at 10 KB, so what lands is the tail
of the log rather than the whole of it. That covers a backtrace and little else.

`nt_log:boot_log/` is written by Android userspace, so it stops where the crash
began rather than describing it.

## The Qualcomm path is a separate question

The `nt_kmsg` and `rawdump` partitions exist, and the devicetree carries
`qcom,minidump` and `qcom,va-minidump`. None of it is armed: nothing in this
tree sets `persist.vendor.ssr.enable_ramdumps` or
`persist.vendor.sys.rawdump_copy`, so the `init.qcom.rc` triggers that start
`vendor.ss_ramdump` and arm the rawdump copy never fire.

Setting them by hand before the next occurrence costs nothing, but neither
replaces pstore for this: they cover subsystem restarts — ADSP, modem — and the
crash dump screen is the applications processor going down.
