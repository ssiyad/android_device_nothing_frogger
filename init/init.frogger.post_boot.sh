#!/vendor/bin/sh
# Scheduler tuning that has to outlive kernel-post-boot.
#
# init.kernel.post_boot-volcano_default_4_3_1.sh writes input_boost_freq,
# input_boost_ms and sched_conservative_pl back to their defaults on lines 118,
# 119 and 135. It starts before sys.boot_completed fires and finishes after
# this script would otherwise have exited, so it wrote last.
#
# Rather than time against it -- init.svc.kernel-post-boot is a
# system_internal_prop and its neverallow puts it out of reach of any
# non-coredomain -- write, let it settle, and write again if it did not hold.
# The writes are idempotent, so whoever else is writing, this ends up last.

IB=/proc/sys/walt/input_boost

apply() {
    if [ -d $IB ]; then
        echo "1497600 1497600 1497600 1497600 1612800 1612800 1612800 1651200" > $IB/input_boost_freq
        echo 160 > $IB/input_boost_ms
        echo 1 > $IB/sched_boost_on_input
    fi

    if [ -d /proc/sys/walt ]; then
        echo 0 > /proc/sys/walt/sched_conservative_pl
        echo 1 > /proc/sys/walt/sched_asymcap_boost
    fi
}

i=0
while [ $i -lt 10 ]; do
    apply
    sleep 1
    if [ "$(cat $IB/input_boost_ms)" = "160" ] &&
       [ "$(cat /proc/sys/walt/sched_conservative_pl)" = "0" ]; then
        break
    fi
    i=$((i + 1))
done
