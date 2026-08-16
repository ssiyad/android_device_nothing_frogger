# Ship a way to calibrate the proximity part

The thresholds this device runs on are hand-picked, arrived at by writing a
candidate pair to persist and rebooting until an ear registered and a desk did
not. See [proximity.md](../reference/proximity.md) for the values and the
mechanism.

That worked, but nothing about it is repeatable. `libsensorcal.so` is a stock
vendor file this tree does not ship, no binary in the stock vendor partition
links it, and its consumer is one of Nothing's own apps — so the ROM has no way
to measure the crosstalk baseline and derive thresholds from it. Any later
change to the optical stack, another protector above all, means running the
search again by hand.

Two things would have to be established: whether `libsensorcal.so` is usable
without the app that normally drives it, and whether the calibration it performs
is the one that writes `prox.fac_cal` rather than something else. Nothing's
`vendor.noth.hardware.sensor.sensor_extension` HAL is shipped and running with
no client, exposes `execSyncIntInt` and `execSyncIntFloat`, and names
`proximity` among its data types, so it is the other candidate for the path
Nothing uses. Its command IDs are not known.

Worth knowing before starting: the raw count is not readable from the ROM. SEE
publishes only the two-state value, and the diag path needs `Diag_sensor.cfg`,
another stock file this tree does not ship. Anything built here has to recover
that first, or it is the same blind search with a nicer interface.
