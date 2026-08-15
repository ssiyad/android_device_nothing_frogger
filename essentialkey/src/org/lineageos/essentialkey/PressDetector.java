/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.essentialkey;

import android.os.Handler;
import android.util.Log;

/**
 * Turns downs and ups into one of three gestures.
 *
 * Every timer here is this class's own. PhoneWindowManager clears
 * ACTION_PASS_TO_USER for the MACRO keys, so the event is never dispatched, the
 * dispatcher synthesises no repeats and nothing ever sets FLAG_LONG_PRESS --
 * exactly one down and one up arrive per press, and there is no framework
 * timing to lean on.
 *
 * A single press always waits the multi-press window out, even when no double
 * press is configured. Skipping the wait in that case would make the key feel
 * different depending on a setting belonging to a different gesture, which is a
 * worse trade than 300 ms.
 *
 * The window is measured from the release rather than from down to down as
 * SingleKeyGestureDetector measures it. That is the same clock the single press
 * already waits on, so the two cannot disagree about which gesture happened.
 *
 * A long press outranks a pending double: press-then-press-and-hold gives the
 * long action, and the release that ends it is swallowed so no single press
 * follows.
 *
 * Everything runs on the handler thread the constructor is given.
 */
final class PressDetector {
    private static final long LONG_PRESS_MS = 500;
    private static final long MULTI_PRESS_MS = 300;

    private final Handler mHandler;
    private final ActionExecutor mExecutor;

    private boolean mLongFired;
    private boolean mDoublePending;
    private boolean mAwaitingSecondPress;

    private final Runnable mLongPress = () -> {
        mLongFired = true;
        mDoublePending = false;
        mAwaitingSecondPress = false;
        mExecutor.performHaptic();
        fire(Constants.KEY_LONG_PRESS);
    };

    private final Runnable mSinglePress = () -> {
        mAwaitingSecondPress = false;
        fire(Constants.KEY_SINGLE_PRESS);
    };

    PressDetector(Handler handler, ActionExecutor executor) {
        mHandler = handler;
        mExecutor = executor;
    }

    void onKey(boolean down) {
        if (down) {
            onDown();
        } else {
            onUp();
        }
    }

    private void onDown() {
        // A down arriving while one is already outstanding means an up was
        // lost. Starting from scratch recovers rather than wedging.
        mHandler.removeCallbacks(mLongPress);

        if (mAwaitingSecondPress) {
            mHandler.removeCallbacks(mSinglePress);
            mAwaitingSecondPress = false;
            mDoublePending = true;
        }

        mLongFired = false;
        mHandler.postDelayed(mLongPress, LONG_PRESS_MS);
    }

    private void onUp() {
        mHandler.removeCallbacks(mLongPress);

        if (mLongFired) {
            mLongFired = false;
            return;
        }

        if (mDoublePending) {
            mDoublePending = false;
            fire(Constants.KEY_DOUBLE_PRESS);
            return;
        }

        mAwaitingSecondPress = true;
        mHandler.postDelayed(mSinglePress, MULTI_PRESS_MS);
    }

    private void fire(String settingKey) {
        Log.i(Constants.TAG, "gesture " + settingKey);
        mExecutor.perform(settingKey);
    }
}
