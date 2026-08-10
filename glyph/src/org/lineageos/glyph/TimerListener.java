/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glyph;

import android.app.AlarmManager;
import android.app.Notification;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Chronometer;
import android.widget.FrameLayout;
import android.widget.RemoteViews;
import android.widget.TextView;

/**
 * Drains the white segments as a clock-app timer counts down.
 *
 * The deadline is published only inside the custom RemoteViews of the clock's
 * ongoing notification, so it is read by inflating that view and asking the
 * Chronometer for its base. The full duration is not published at all, so the
 * largest remaining time seen for a notification is taken as full scale: a
 * timer already running when this starts begins from a full bar.
 */
public final class TimerListener extends NotificationListenerService {
    private static final String TAG = "Glyph";

    private static final String DESKCLOCK = "com.android.deskclock";

    private static final int BRIGHTNESS = 120;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final int[] mLevels = new int[Panel.SEGMENTS];

    private final AlarmManager.OnAlarmListener mTick = this::refresh;

    private AlarmManager mAlarmManager;
    private Resources mDeskClockResources;
    private String mKey;
    private long mTotal;

    @Override
    public void onCreate() {
        super.onCreate();
        mAlarmManager = getSystemService(AlarmManager.class);
        try {
            mDeskClockResources = getPackageManager().getResourcesForApplication(DESKCLOCK);
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "No clock app, the timer indicator is inert");
        }
    }

    @Override
    public void onListenerConnected() {
        refresh();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (DESKCLOCK.equals(sbn.getPackageName())) {
            refresh();
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (DESKCLOCK.equals(sbn.getPackageName())) {
            refresh();
        }
    }

    private void refresh() {
        mAlarmManager.cancel(mTick);

        if (mDeskClockResources == null) {
            return;
        }

        final StatusBarNotification[] active = getActiveNotifications();
        if (active == null) {
            clear();
            return;
        }

        for (StatusBarNotification sbn : active) {
            if (!DESKCLOCK.equals(sbn.getPackageName())) {
                continue;
            }
            final View content = inflate(sbn.getNotification());
            final Chronometer chronometer = content == null ? null : findChronometer(content);
            if (chronometer != null) {
                update(sbn.getKey(), chronometer.getBase(), isPaused(content));
                return;
            }
        }

        clear();
    }

    private void update(String key, long base, boolean paused) {
        final long remaining = base - SystemClock.elapsedRealtime();
        if (remaining <= 0) {
            clear();
            return;
        }

        if (!key.equals(mKey)) {
            mKey = key;
            mTotal = remaining;
        } else {
            // The notification's +1 minute action extends a running timer.
            mTotal = Math.max(mTotal, remaining);
        }

        final int lit = Math.min(Panel.SEGMENTS,
                (int) Math.ceil((double) Panel.SEGMENTS * remaining / mTotal));
        for (int i = 0; i < Panel.SEGMENTS; i++) {
            mLevels[i] = i >= Panel.SEGMENTS - lit ? BRIGHTNESS : 0;
        }
        Panel.get().setWhite(Panel.OWNER_TIMER, mLevels);

        if (paused) {
            return;
        }

        // One wake-up per segment rather than a ticking handler.
        final long next = base - (long) ((lit - 1) * mTotal / (double) Panel.SEGMENTS);
        mAlarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, next, TAG, mTick, mHandler);
    }

    private void clear() {
        mKey = null;
        mTotal = 0;
        Panel.get().releaseWhite(Panel.OWNER_TIMER);
    }

    private View inflate(Notification notification) {
        final RemoteViews views = notification.contentView != null
                ? notification.contentView : notification.bigContentView;
        if (views == null) {
            return null;
        }
        try {
            return views.apply(this, new FrameLayout(this));
        } catch (RuntimeException e) {
            Log.e(TAG, "Failed to inflate the timer notification", e);
            return null;
        }
    }

    /**
     * A paused timer keeps a fixed deadline that has already stopped meaning
     * anything, so the state label is the only thing that distinguishes it.
     */
    private boolean isPaused(View content) {
        final int id = mDeskClockResources.getIdentifier("state", "id", DESKCLOCK);
        final int paused = mDeskClockResources.getIdentifier("timer_paused", "string", DESKCLOCK);
        if (id == 0 || paused == 0) {
            return false;
        }
        final View state = content.findViewById(id);
        return state instanceof TextView
                && mDeskClockResources.getString(paused)
                        .contentEquals(((TextView) state).getText());
    }

    private static Chronometer findChronometer(View view) {
        if (view instanceof Chronometer) {
            return (Chronometer) view;
        }
        if (view instanceof ViewGroup) {
            final ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                final Chronometer found = findChronometer(group.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
