# Reconcile the kernel module load lists

`modules.load.*` references 29 modules the stock images do not ship:

```
9pnet.ko  9pnet_fd.ko  clk-scmi.ko  governor_gpubw_mon.ko
governor_msm_adreno_tz.ko  leds-qcom-lpg.ko  macsec.ko  ntfs3.ko
pps_core.ko  ptp.ko  ptp_kvm.ko  q2spi-geni.ko  qcom-amoled-regulator.ko
qcom_ice.ko  qrtr-tun.ko  qti_pmic_glink.ko  tls.ko  ucsi_qti_glink.ko
ufs-qcom.ko  usbmon.ko  vcpu_stall_detector.ko  virtio_*.ko (6)
vmw_vsock_virtio_transport.ko  xhci-sideband.ko
```

Most are GKI or virtualisation modules, or drivers stock compiles into the
kernel. A few look like cross-branch renames — stock has `pmic_glink.ko` and
`ucsi_glink.ko` where the list wants `qti_pmic_glink.ko` and
`ucsi_qti_glink.ko`.

Nothing here breaks anything: depmod reports no unresolved symbols. The change is
cosmetic and keeps the load lists honest.

## Method

Compare against
`out/target/product/frogger/obj/PACKAGING/kernel_modules_intermediates` and drop
whatever the kernel does not produce.

## Validation constraint

`BOARD_VENDOR_*_KERNEL_MODULES_LOAD` is expanded at config time via
`$(shell cat modules.load.*)` and baked into the generated `Image.gz.rsp`.
Re-running that `.rsp` rebuilds the image from an existing `.config` and an
existing module list, so it **cannot** validate a change to either
`modules.load.*` or `arch/arm64/configs/vendor/*.config`. Only a full
`mka`/`brunch` run regenerates them.
