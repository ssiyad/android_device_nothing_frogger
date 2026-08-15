/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glyph;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
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
public final class NotificationIndicator extends NotificationListenerService
        implements Gate.Listener {
    private static final String TAG = "Glyph";

    private static final int BRIGHTNESS = 70;

    /** Dim enough to sit there for hours without becoming the room's light. */
    private static final int WAITING_BRIGHTNESS = 20;

    private static final long REGISTER_RETRY_MS = 2000;

    /**
     * How long a block takes to fall one place. Steps are posted rather than
     * scheduled, so a sleeping phone holds the frame it had instead of being
     * woken to finish an animation nobody is watching.
     */
    private static final long FALL_STEP_MS = 70;

    /**
     * A progress bar rests with its leading block dim and brightens it for each
     * update that moves the count. Downloads report far more often than the six
     * blocks advance, so the flicker follows the data arriving: work that has
     * stalled simply stops brightening and sits dim. A countdown keeps its
     * leading block full, having nothing to stall.
     */
    private static final long BLIP_MS = 150;
    private static final int IDLE_BRIGHTNESS = 25;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final int[] mLevels = new int[Panel.SEGMENTS];

    private final AlarmManager.OnAlarmListener mTick = this::refresh;

    private final Context mContext;
    private final Gate mGate;
    private final AlarmManager mAlarmManager;
    private final ClockTimer mClockTimer;

    private String mKey;
    private long mTotal;
    private int mLit;
    private int mFalling = -1;
    private int mFallTarget;
    private int mLeading = BRIGHTNESS;
    private String mProgressKey;
    private int mProgressValue = -1;

    private final Runnable mBlipEnd = new Runnable() {
        @Override
        public void run() {
            mLeading = IDLE_BRIGHTNESS;
            render();
        }
    };

    private final Runnable mFallStep = new Runnable() {
        @Override
        public void run() {
            if (mFalling >= mFallTarget) {
                mLit = Panel.SEGMENTS - mFallTarget;
                mFalling = -1;
            } else {
                mFalling++;
                mHandler.postDelayed(this, FALL_STEP_MS);
            }
            render();
        }
    };

    NotificationIndicator(Context context, Gate gate) {
        mContext = context;
        mGate = gate;
        mAlarmManager = context.getSystemService(AlarmManager.class);
        mClockTimer = new ClockTimer(context);
    }

    /**
     * The notification service is looked up by name with no null check on the
     * way in, so registering before system_server has published it throws
     * rather than failing. This process is persistent and starts early enough
     * for that to be a race it loses on some boots, so it keeps asking.
     */
    void register() {
        try {
            registerAsSystemService(mContext,
                    new ComponentName(mContext.getPackageName(), getClass().getCanonicalName()),
                    UserHandle.USER_ALL);
        } catch (RemoteException | RuntimeException e) {
            Log.w(TAG, "Notification service not up yet, retrying", e);
            mHandler.postDelayed(this::register, REGISTER_RETRY_MS);
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

    @Override
    public void onGateChanged(boolean on) {
        refresh();
    }

    private void refresh() {
        final StatusBarNotification[] active = getActiveNotifications();
        if (active == null) {
            Panel.get().releaseRed(Panel.RED_WAITING);
            clear();
            return;
        }

        updateWaiting(active);
        updateBar(active);
    }

    /**
     * The bar is only ever worth drawing behind the gate. The strip is on the
     * back, so a phone lying face-up has it against the table, and a phone in
     * use has it turned away — in both, an advancing bar is an animation and a
     * countdown is a wake-up per block, spent on nobody.
     */
    private void updateBar(StatusBarNotification[] active) {
        mAlarmManager.cancel(mTick);

        if (!mGate.isOn()) {
            hide();
            return;
        }

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
            final int value = progressOf(notification);

            final boolean moved = !progress.getKey().equals(mProgressKey)
                    || value != mProgressValue;
            mProgressKey = progress.getKey();
            mProgressValue = value;

            mHandler.removeCallbacks(mBlipEnd);
            mLeading = moved ? BRIGHTNESS : IDLE_BRIGHTNESS;
            if (moved) {
                mHandler.postDelayed(mBlipEnd, BLIP_MS);
            }

            show((double) Math.min(value, max) / max);
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

        // A countdown cannot stall, so its leading block stays full.
        mHandler.removeCallbacks(mBlipEnd);
        mLeading = BRIGHTNESS;

        final int lit = show((double) (mTotal - remaining) / mTotal);

        if (reading.paused || lit >= Panel.SEGMENTS) {
            return;
        }

        // One wake-up per block rather than a ticking handler. Each block owns
        // an equal share of the countdown, so the next is due once the elapsed
        // share reaches it.
        final long elapsedAtNext = mTotal * lit / Panel.SEGMENTS;
        mAlarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                reading.base - (mTotal - elapsedAtNext), TAG, mTick, mHandler);
    }

    /**
     * Glows red while anything worth turning the phone over for is waiting.
     *
     * What counts is decided by settings the user already keeps elsewhere: a
     * conversation marked priority, a channel allowed through Do Not Disturb, a
     * call already missed, and whatever the current filter is letting through.
     * Nothing here has a setting of its own.
     *
     * It waits on the gate with everything else. A glow the user is holding in
     * their hand tells them what the screen in front of them already has.
     */
    private void updateWaiting(StatusBarNotification[] active) {
        if (!mGate.isOn()) {
            Panel.get().releaseRed(Panel.RED_WAITING);
            return;
        }

        final RankingMap rankings = getCurrentRanking();
        for (StatusBarNotification sbn : active) {
            if (isImportant(sbn, rankings)) {
                Log.i(TAG, "waiting on " + sbn.getPackageName() + " " + sbn.getKey());
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
        if (channel == null) {
            return false;
        }

        // Importance says nothing about what matters, and the user having once
        // adjusted it says little more. Seven channels on this phone carry a
        // user-set importance and one of them is the SMS channel, at high,
        // which is why every text lit the strip.
        //
        // What is left are the two settings that mean this and nothing else: a
        // conversation marked priority, and a channel allowed through Do Not
        // Disturb.
        return channel.isImportantConversation() || channel.canBypassDnd();
    }

    /** Returns -1 unless the notification carries determinate progress. */
    private static int progressOf(Notification notification) {
        if (notification.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)) {
            return -1;
        }
        final int max = notification.extras.getInt(Notification.EXTRA_PROGRESS_MAX);
        return max > 0 ? notification.extras.getInt(Notification.EXTRA_PROGRESS) : -1;
    }

    /** Draws a static bar, stopping any breath left running. */
    /**
     * Drops a new block from the top of the strip onto the stack each time the
     * bar advances, so progress arrives as a movement rather than as a segment
     * that was simply not lit a moment ago.
     */
    private int show(double fraction) {
        final int lit = Math.min(Panel.SEGMENTS,
                1 + (int) (Panel.SEGMENTS * Math.min(Math.max(fraction, 0), 1)));

        if (lit > mLit) {
            mFallTarget = Panel.SEGMENTS - lit;
            mFalling = 0;
            mHandler.removeCallbacks(mFallStep);
            mHandler.postDelayed(mFallStep, FALL_STEP_MS);
        } else if (lit < mLit) {
            mHandler.removeCallbacks(mFallStep);
            mFalling = -1;
            mLit = lit;
        }

        render();
        return lit;
    }

    /** The resting stack, plus whichever block is still on its way down. */
    private void render() {
        for (int i = 0; i < Panel.SEGMENTS; i++) {
            mLevels[i] = i >= Panel.SEGMENTS - mLit ? BRIGHTNESS : 0;
        }
        if (mLit > 0) {
            mLevels[Panel.SEGMENTS - mLit] = mLeading;
        }
        if (mFalling >= 0) {
            mLevels[mFalling] = BRIGHTNESS;
        }
        Panel.get().setWhite(Panel.OWNER_NOTIFICATION, mLevels);
    }

    private void clear() {
        mKey = null;
        mTotal = 0;
        mProgressKey = null;
        mProgressValue = -1;
        hide();
    }

    /**
     * Stops drawing without forgetting what was being drawn. A countdown takes
     * the largest remaining time it has seen as its full scale, so clearing a
     * running timer because the phone was picked up would restart the bar from
     * empty when it was put back down.
     */
    private void hide() {
        mLit = 0;
        mFalling = -1;
        mLeading = BRIGHTNESS;
        mHandler.removeCallbacks(mFallStep);
        mHandler.removeCallbacks(mBlipEnd);
        Panel.get().releaseWhite(Panel.OWNER_NOTIFICATION);
    }
}
