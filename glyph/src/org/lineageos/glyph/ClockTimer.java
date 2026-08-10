/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glyph;

import android.app.Notification;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Chronometer;
import android.widget.FrameLayout;
import android.widget.RemoteViews;
import android.widget.TextView;

/**
 * Reads a countdown out of the clock app's ongoing notification.
 *
 * The deadline is published only inside the notification's custom RemoteViews,
 * so it is recovered by inflating that view and asking the Chronometer for its
 * base. A paused timer keeps a deadline that has stopped meaning anything, and
 * the state label is the only thing that tells the two apart.
 */
final class ClockTimer {
    private static final String TAG = "Glyph";

    static final String PACKAGE = "com.android.deskclock";

    private final Context mContext;

    private Resources mResources;

    ClockTimer(Context context) {
        mContext = context;
        try {
            mResources = context.getPackageManager().getResourcesForApplication(PACKAGE);
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "No clock app, the timer indicator is inert");
        }
    }

    /** Returns null when the notification carries no countdown. */
    Reading read(Notification notification) {
        if (mResources == null) {
            return null;
        }

        final RemoteViews views = notification.contentView != null
                ? notification.contentView : notification.bigContentView;
        if (views == null) {
            return null;
        }

        final View content;
        try {
            content = views.apply(mContext, new FrameLayout(mContext));
        } catch (RuntimeException e) {
            Log.e(TAG, "Failed to inflate the timer notification", e);
            return null;
        }

        final Chronometer chronometer = findChronometer(content);
        return chronometer == null ? null : new Reading(chronometer.getBase(), isPaused(content));
    }

    private boolean isPaused(View content) {
        final int stateId = mResources.getIdentifier("state", "id", PACKAGE);
        final int pausedId = mResources.getIdentifier("timer_paused", "string", PACKAGE);
        if (stateId == 0 || pausedId == 0) {
            return false;
        }
        final View state = content.findViewById(stateId);
        return state instanceof TextView
                && mResources.getString(pausedId).contentEquals(((TextView) state).getText());
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

    static final class Reading {
        /** Elapsed-realtime instant at which the countdown reaches zero. */
        final long base;
        final boolean paused;

        Reading(long base, boolean paused) {
            this.base = base;
            this.paused = paused;
        }
    }
}
