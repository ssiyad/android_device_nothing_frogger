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
 *
 * Every owner's last frame is kept rather than only the winning one, so a brief
 * high-priority interruption falls back to whatever was underneath instead of
 * leaving the strip dark until that owner next has something to say.
 */
final class Panel {
    private static final String TAG = "Glyph";

    private static final String BASE = "/sys/class/leds/aw20036_led/";
    private static final int RED_REGISTER = 26;

    private static final int MODE_ACTIVE = 1;
    private static final int MODE_STANDBY = 2;

    static final int SEGMENTS = 6;

    /** Contenders for the six white segments, highest wins. Red has no owner. */
    static final int OWNER_MUSIC = 0;
    static final int OWNER_NOTIFICATION = 1;
    static final int OWNER_STATUS = 2;
    private static final int OWNERS = 3;

    private static final Panel sInstance = new Panel();

    private final int[][] mLevels = new int[OWNERS][SEGMENTS];
    private final boolean[] mHeld = new boolean[OWNERS];

    private int mRed;
    private int mMode = -1;

    private Panel() {}

    static Panel get() {
        return sInstance;
    }

    synchronized void setWhite(int owner, int[] levels) {
        System.arraycopy(levels, 0, mLevels[owner], 0, SEGMENTS);
        mHeld[owner] = true;
        apply();
    }

    synchronized void releaseWhite(int owner) {
        mHeld[owner] = false;
        Arrays.fill(mLevels[owner], 0);
        apply();
    }

    synchronized void setRed(int level) {
        mRed = level;
        apply();
    }

    private void apply() {
        int[] white = null;
        for (int owner = OWNERS - 1; owner >= 0 && white == null; owner--) {
            if (mHeld[owner]) {
                white = mLevels[owner];
            }
        }

        boolean lit = mRed > 0;
        final StringBuilder frame = new StringBuilder();
        for (int segment = 0; segment < SEGMENTS; segment++) {
            final int level = white == null ? 0 : white[segment];
            lit |= level > 0;
            frame.append(level).append(' ');
        }

        // Brightness writes only reach the chip while it is out of standby.
        if (lit) {
            setMode(MODE_ACTIVE);
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
