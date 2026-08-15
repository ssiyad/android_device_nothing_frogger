/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glyph;

import android.content.Context;
import android.os.Handler;
import android.os.PowerManager;

/**
 * Blinks the red indicator for a call missed from someone starred.
 *
 * It blinks for a while and then stops, rather than until the notification is
 * dealt with. The blink is stepped from a handler, so it needs the machine held
 * awake for as long as it runs, and a missed call can sit there for hours —
 * long enough for an indefinite blink to cost real battery for a fact that has
 * already been noticed or is not going to be.
 *
 * What is left afterwards is the steady glow, which any missed call raises and
 * which costs nothing to hold. So the blink is the announcement and the glow is
 * the record.
 *
 * The blink itself does not wait on the gate. Someone starred calling is worth
 * catching whether or not the phone is face-down.
 */
final class MissedCallIndicator {
    private static final String TAG = "Glyph";

    private static final int BRIGHTNESS = 160;
    private static final long BLINK_MS = 500;

    /** How long to keep announcing before falling back to the glow. */
    private static final long WINDOW_MS = 60000;

    private final Handler mHandler;
    private final PowerManager.WakeLock mWakeLock;

    private String mKey;
    private boolean mLit;

    private final Runnable mBlink = new Runnable() {
        @Override
        public void run() {
            mLit = !mLit;
            Panel.get().setRed(Panel.RED_MISSED, mLit ? BRIGHTNESS : 0);
            mHandler.postDelayed(this, BLINK_MS);
        }
    };

    private final Runnable mSettle = this::stop;

    MissedCallIndicator(Context context, Handler handler) {
        mHandler = handler;
        mWakeLock = context.getSystemService(PowerManager.class)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
        mWakeLock.setReferenceCounted(false);
    }

    /** Announces this call, or does nothing if it is the one already showing. */
    void show(String key) {
        if (key.equals(mKey)) {
            return;
        }
        mKey = key;

        mWakeLock.acquire(WINDOW_MS + BLINK_MS);
        mLit = false;
        mHandler.removeCallbacks(mBlink);
        mHandler.removeCallbacks(mSettle);
        mHandler.post(mBlink);
        mHandler.postDelayed(mSettle, WINDOW_MS);
    }

    /** No missed call from a favourite is waiting any more. */
    void clear() {
        if (mKey == null) {
            return;
        }
        mKey = null;
        stop();
    }

    private void stop() {
        mHandler.removeCallbacks(mBlink);
        mHandler.removeCallbacks(mSettle);
        Panel.get().releaseRed(Panel.RED_MISSED);
        if (mWakeLock.isHeld()) {
            mWakeLock.release();
        }
    }
}
