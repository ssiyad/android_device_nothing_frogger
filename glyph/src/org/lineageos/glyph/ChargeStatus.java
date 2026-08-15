/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glyph;

import android.content.Context;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.PowerManager;

/**
 * Shows the charge level as a column for a few seconds when the phone is set
 * down on its face, which is the moment the strip becomes the only thing worth
 * looking at.
 *
 * This answers to the posture alone rather than to the gate. Putting the phone
 * down is a moment, and the screen is still on for the whole of it.
 */
final class ChargeStatus implements Gate.FaceDownListener {
    private static final String TAG = "Glyph";

    private static final int BRIGHTNESS = 80;
    private static final long DURATION_MS = 3000;

    private final Handler mHandler;
    private final BatteryManager mBatteryManager;
    private final PowerManager.WakeLock mWakeLock;
    private final int[] mLevels = new int[Panel.SEGMENTS];

    private final Runnable mHide = new Runnable() {
        @Override
        public void run() {
            Panel.get().releaseWhite(Panel.OWNER_STATUS);
            if (mWakeLock.isHeld()) {
                mWakeLock.release();
            }
        }
    };

    ChargeStatus(Context context, Handler handler) {
        mHandler = handler;
        mBatteryManager = context.getSystemService(BatteryManager.class);
        mWakeLock = context.getSystemService(PowerManager.class)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
        mWakeLock.setReferenceCounted(false);
    }

    @Override
    public void onFaceDown(boolean faceDown, boolean initial) {
        if (!faceDown || initial) {
            return;
        }

        final int capacity = mBatteryManager.getIntProperty(
                BatteryManager.BATTERY_PROPERTY_CAPACITY);
        if (capacity < 0) {
            return;
        }

        // The strip holds its own pattern across suspend, but the release does
        // not, so the machine has to stay up for as long as this is showing.
        mWakeLock.acquire(DURATION_MS * 2);

        final double height = Panel.SEGMENTS * capacity / 100.0;
        for (int i = 0; i < Panel.SEGMENTS; i++) {
            final double fill = height - (Panel.SEGMENTS - 1 - i);
            mLevels[i] = fill >= 1 ? BRIGHTNESS : fill <= 0 ? 0 : (int) (BRIGHTNESS * fill);
        }
        Panel.get().setWhite(Panel.OWNER_STATUS, mLevels);

        mHandler.removeCallbacks(mHide);
        mHandler.postDelayed(mHide, DURATION_MS);
    }
}
