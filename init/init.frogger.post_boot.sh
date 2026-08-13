#!/vendor/bin/sh
# Scheduler tuning that has to outlive kernel-post-boot.
#
# init.kernel.post_boot-volcano_default_4_3_1.sh writes input_boost_freq,
# input_boost_ms and sched_conservative_pl back to their defaults, and it
# finishes after init.qcom.post_boot.sh does. Setting these from there is
# silently undone, so they are set here instead, on boot_completed.

IB=/proc/sys/walt/input_boost

# Running on sys.boot_completed is not late enough on its own. kernel-post-boot
# starts before boot_completed fires and outlives it -- measured at 28.719 to
# 29.321 against boot_completed at 29.09 -- so waiting for it to exit is what
# actually decides who writes last.
i=0
while [ "$(getprop init.svc.kernel-post-boot)" = "running" ] && [ $i -lt 15 ]; do
    sleep 1
    i=$((i + 1))
done
sleep 1

if [ -d $IB ]; then
    echo "1497600 1497600 1497600 1497600 1612800 1612800 1612800 1651200" > $IB/input_boost_freq
    echo 160 > $IB/input_boost_ms
    echo 1 > $IB/sched_boost_on_input
fi

if [ -d /proc/sys/walt ]; then
    echo 0 > /proc/sys/walt/sched_conservative_pl
    echo 1 > /proc/sys/walt/sched_asymcap_boost
fi
