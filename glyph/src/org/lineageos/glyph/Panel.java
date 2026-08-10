/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glyph;

import android.util.Log;

import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * The aw20036 strip.
 *
 * Six white segments are addressed together through frame_brightness, index 0
 * at the top. The red indicator sits on register 26 and is written separately;
 * the driver latches its value and re-applies it on every six-value frame
 * write, so the two never contend.
 */
final class Panel {
    private static final String TAG = "Glyph";

    private static final String BASE = "/sys/class/leds/aw20036_led/";
    private static final int RED_REGISTER = 26;

    private static final int MODE_ACTIVE = 1;
    private static final int MODE_STANDBY = 2;

    static final int SEGMENTS = 6;

    /** Contenders for the six white segments, highest wins. Red has no owner. */
    static final int OWNER_NONE = 0;
    static final int OWNER_MUSIC = 1;
    static final int OWNER_TIMER = 2;

    private static final Panel sInstance = new Panel();

    private final int[] mWhite = new int[SEGMENTS];
    private int mOwner = OWNER_NONE;
    private int mRed;
    private int mMode = -1;

    private Panel() {}

    static Panel get() {
        return sInstance;
    }

    /** Drives the white segments, unless a higher-priority owner holds them. */
    synchronized void setWhite(int owner, int[] levels) {
        if (owner < mOwner) {
            return;
        }
        mOwner = owner;
        System.arraycopy(levels, 0, mWhite, 0, SEGMENTS);
        apply();
    }

    synchronized void releaseWhite(int owner) {
        if (owner != mOwner) {
            return;
        }
        mOwner = OWNER_NONE;
        Arrays.fill(mWhite, 0);
        apply();
    }

    synchronized void setRed(int level) {
        mRed = level;
        apply();
    }

    private void apply() {
        boolean lit = mRed > 0;
        for (int level : mWhite) {
            lit |= level > 0;
        }

        // Brightness writes only reach the chip while it is out of standby.
        if (lit) {
            setMode(MODE_ACTIVE);
        }

        final StringBuilder frame = new StringBuilder();
        for (int level : mWhite) {
            frame.append(level).append(' ');
        }
        write("frame_brightness", frame.toString().trim());
        write("single_brightness", RED_REGISTER + " " + mRed);

        if (!lit) {
            setMode(MODE_STANDBY);
        }
    }

    private void setMode(int mode) {
        if (mode == mMode) {
            return;
        }
        write("operating_mode", Integer.toString(mode));
        mMode = mode;
    }

    private void write(String node, String value) {
        try (FileOutputStream out = new FileOutputStream(BASE + node)) {
            out.write(value.getBytes());
        } catch (IOException e) {
            Log.e(TAG, "Failed to write " + value + " to " + node, e);
        }
    }
}
