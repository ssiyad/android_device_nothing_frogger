/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glyph;

import android.content.Context;
import android.os.Handler;
import android.os.PowerManager;

/**
 * Runs a comet down the strip when a notification arrives.
 *
 * It is driven by the notification rather than by the sound, which is the point
 * of it: a phone on silent alerts exactly as a phone that rings does, so the
 * strip becomes the silent ringtone the hardware was always shaped for. The
 * opposite is true too — nothing that merely makes a noise gets a pattern,
 * which is what keeps unlock chimes and touch feedback out.
 *
 * It runs downwards where the ring runs up. Direction is the only thing the eye
 * reliably reads off six segments, so it is what tells a call from a message
 * without either needing to be learnt.
 *
 * Priority arrivals get the same pattern brighter rather than a different one.
 * A second shape would have to be learnt; brightness is noticed without being
 * read, and the red glow is already saying which it was.
 */
final class AlertIndicator {
    private static final String TAG = "Glyph";

    private static final int BRIGHTNESS = 90;
    private static final int PRIORITY_BRIGHTNESS = 170;

    /** Segments lit behind the head, each at half the one in front. */
    private static final int TAIL = 2;

    private static final long STEP_MS = 55;
    private static final int PASSES = 2;

    private final int[][] mFrames =
            Pattern.repeat(Pattern.sweep(BRIGHTNESS, TAIL, false /* up */), PASSES);
    private final int[][] mPriorityFrames =
            Pattern.repeat(Pattern.sweep(PRIORITY_BRIGHTNESS, TAIL, false /* up */), PASSES);

    private final Pattern mPattern;
    private final PowerManager.WakeLock mWakeLock;

    AlertIndicator(Context context, Handler handler) {
        mPattern = new Pattern(handler, Panel.OWNER_ALERT);
        mWakeLock = context.getSystemService(PowerManager.class)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
        mWakeLock.setReferenceCounted(false);
    }

    void play(boolean priority) {
        // The phone is asleep behind the gate, and a posted notification only
        // wakes it long enough to deliver. The pattern is stepped from a
        // handler, so it needs the machine kept up for its own length.
        mWakeLock.acquire(mFrames.length * STEP_MS * 2);
        mPattern.play(priority ? mPriorityFrames : mFrames, STEP_MS, false /* loop */);
    }
}
