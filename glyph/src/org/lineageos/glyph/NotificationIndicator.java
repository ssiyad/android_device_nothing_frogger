/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glyph;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

/**
 * Fills the white segments from whatever the notifications say is under way,
 * from the bottom up, so a full strip means finished either way: a countdown
 * fills as it runs out, a progress bar fills as it completes.
 *
 * A countdown wins when both are present, because it is the one with a deadline
 * to miss.
 *
 * This registers itself rather than being bound as a declared listener, so it
 * never appears in the user's notification-access list and never depends on it.
 * That list is seeded from configuration only when a profile is created, which
 * leaves a declared listener inert on any device that was not wiped.
 */
public final class NotificationIndicator extends NotificationListenerService {
    private static final String TAG = "Glyph";

    private static final int BRIGHTNESS = 70;

    /** Dim enough to sit there for hours without becoming the room's light. */
    private static final int WAITING_BRIGHTNESS = 20;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final int[] mLevels = new int[Panel.SEGMENTS];

    private final AlarmManager.OnAlarmListener mTick = this::refresh;

    private final Context mContext;
    private final AlarmManager mAlarmManager;
    private final ClockTimer mClockTimer;

    private String mKey;
    private long mTotal;

    NotificationIndicator(Context context) {
        mContext = context;
        mAlarmManager = context.getSystemService(AlarmManager.class);
        mClockTimer = new ClockTimer(context);
    }

    void register() {
        try {
            registerAsSystemService(mContext,
                    new ComponentName(mContext.getPackageName(), getClass().getCanonicalName()),
                    UserHandle.USER_ALL);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to register the notification listener", e);
        }
    }

    @Override
    public void onListenerConnected() {
        refresh();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        refresh();
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        refresh();
    }

    private void refresh() {
        mAlarmManager.cancel(mTick);

        final StatusBarNotification[] active = getActiveNotifications();
        if (active == null) {
            Panel.get().releaseRed(Panel.RED_WAITING);
            clear();
            return;
        }

        updateWaiting(active);

        StatusBarNotification progress = null;
        for (StatusBarNotification sbn : active) {
            if (ClockTimer.PACKAGE.equals(sbn.getPackageName())) {
                final ClockTimer.Reading reading = mClockTimer.read(sbn.getNotification());
                if (reading != null) {
                    showTimer(sbn.getKey(), reading);
                    return;
                }
            }
            if (progressOf(sbn.getNotification()) >= 0
                    && (progress == null || sbn.getPostTime() > progress.getPostTime())) {
                progress = sbn;
            }
        }

        if (progress != null) {
            final Notification notification = progress.getNotification();
            final int max = notification.extras.getInt(Notification.EXTRA_PROGRESS_MAX);
            fill((double) Math.min(progressOf(notification), max) / max);
            return;
        }

        clear();
    }

    private void showTimer(String key, ClockTimer.Reading reading) {
        final long remaining = reading.base - SystemClock.elapsedRealtime();
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

        final int lit = fill((double) (mTotal - remaining) / mTotal);

        if (reading.paused || lit >= Panel.SEGMENTS) {
            return;
        }

        // One wake-up per segment rather than a ticking handler. The next
        // segment lights once the elapsed share reaches the following step.
        final long step = mTotal * lit / (Panel.SEGMENTS - 1);
        mAlarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                reading.base - (mTotal - step), TAG, mTick, mHandler);
    }

    /**
     * Glows red while anything worth turning the phone over for is waiting.
     *
     * What counts is decided by settings the user already keeps elsewhere: a
     * conversation marked priority, a channel raised to high importance, and
     * whatever Do Not Disturb is currently letting through. Nothing here has a
     * setting of its own.
     */
    private void updateWaiting(StatusBarNotification[] active) {
        final RankingMap rankings = getCurrentRanking();
        for (StatusBarNotification sbn : active) {
            if (isImportant(sbn, rankings)) {
                Panel.get().setRed(Panel.RED_WAITING, WAITING_BRIGHTNESS);
                return;
            }
        }
        Panel.get().releaseRed(Panel.RED_WAITING);
    }

    private boolean isImportant(StatusBarNotification sbn, RankingMap rankings) {
        final Notification notification = sbn.getNotification();

        // Something already on screen is not waiting to be noticed, and a
        // group summary only repeats what its children say.
        if ((notification.flags
                & (Notification.FLAG_ONGOING_EVENT | Notification.FLAG_GROUP_SUMMARY)) != 0) {
            return false;
        }

        // A call already missed stands on its own, whatever the filter says.
        if (Notification.CATEGORY_MISSED_CALL.equals(notification.category)) {
            return true;
        }

        final Ranking ranking = new Ranking();
        if (rankings == null || !rankings.getRanking(sbn.getKey(), ranking)) {
            return false;
        }

        if (getCurrentInterruptionFilter() != INTERRUPTION_FILTER_ALL
                && !ranking.matchesInterruptionFilter()) {
            return false;
        }

        final NotificationChannel channel = ranking.getChannel();
        if (channel != null && (channel.isImportantConversation()
                || channel.getImportance() >= NotificationManager.IMPORTANCE_HIGH)) {
            return true;
        }

        return Notification.CATEGORY_MESSAGE.equals(notification.category);
    }

    /** Returns -1 unless the notification carries determinate progress. */
    private static int progressOf(Notification notification) {
        if (notification.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)) {
            return -1;
        }
        final int max = notification.extras.getInt(Notification.EXTRA_PROGRESS_MAX);
        return max > 0 ? notification.extras.getInt(Notification.EXTRA_PROGRESS) : -1;
    }

    /**
     * Lights the bottom segments for a fraction of the way through. The bottom
     * one stands for "under way", so nothing in progress ever reads as absent.
     */
    private int fill(double fraction) {
        final int lit = 1 + (int) ((Panel.SEGMENTS - 1) * Math.min(Math.max(fraction, 0), 1));
        for (int i = 0; i < Panel.SEGMENTS; i++) {
            mLevels[i] = i >= Panel.SEGMENTS - lit ? BRIGHTNESS : 0;
        }
        Panel.get().setWhite(Panel.OWNER_NOTIFICATION, mLevels);
        return lit;
    }

    private void clear() {
        mKey = null;
        mTotal = 0;
        Panel.get().releaseWhite(Panel.OWNER_NOTIFICATION);
    }
}
