# Catch the reboot to the crash dump screen

The device reboots at random into the crash dump screen XBL puts up ahead of the
bootloader. No trigger is known, so the job is to make sure the next one leaves
something to read.

## The capture works now

It did not before. The ramoops node split its 4 MB carveout into 2 MB of console
and 2 MB of pmsg with no `record-size`, and pstore gives the dmesg zone whatever
console and pmsg leave behind — which was nothing. A panic wrote no record at
all. On the running build:

```
/sys/module/ramoops/parameters/record_size   131072
                                console_size 2097152
                                pmsg_size    1048576
```

and `/sys/fs/pstore` now carries `console-ramoops-0` as well as
`pmsg-ramoops-0`, where it previously held only pmsg. So the console log was
collateral damage from the same layout, not a separate fault.

The console record is written without ECC — `ramoops.ecc` is 0 — so a few bytes
per boot come back corrupted, single characters at a time. It is readable, and
worth knowing before treating a mangled line as evidence of anything.

## Getting the record off the device

**The obvious way out of the crash dump screen destroys it.** pstore lives in
RAM at `0x81f20000` and survives a warm reboot but not a power-hold, which is a
PMIC-level reset. Leave that screen with a warm reboot into LineageOS recovery,
which has a root shell where stock recovery has only sideload, and run
`tools/grab-logs.sh`.

| Entry | Holds |
|---|---|
| `dmesg-ramoops-*` | the panic backtrace — the one that matters |
| `console-ramoops-*` | the previous boot's console, for what led up to it |
| `pmsg-ramoops-*` | the previous boot's userspace ring buffer |

`pstore.kmsg_bytes` caps each dmesg record at 10 KB, so what lands is the tail of
the log rather than all of it. That covers a backtrace and little else. The zone
holds eight such records.

`nt_log:boot_log/` is written by Android userspace, so it stops where the crash
began rather than describing it.

## The Qualcomm path is a separate question

The `nt_kmsg` and `rawdump` partitions exist, and the devicetree carries
`qcom,minidump` and `qcom,va-minidump`. None of it is armed: nothing in this
tree sets `persist.vendor.ssr.enable_ramdumps` or
`persist.vendor.sys.rawdump_copy`, so the `init.qcom.rc` triggers that start
`vendor.ss_ramdump` and arm the rawdump copy never fire.

Setting them by hand costs nothing, but neither replaces pstore here: they cover
subsystem restarts — ADSP, modem — and the crash dump screen is the applications
processor going down.
