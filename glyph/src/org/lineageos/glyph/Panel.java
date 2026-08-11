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
    static final int OWNER_RINGING = 3;
    private static final int OWNERS = 4;

    /**
     * Red carries two meanings, told apart by rhythm: a steady glow for
     * something waiting, a blink for a camera that is running. The blink wins
     * while it lasts, because two things blinking red could not be read.
     */
    static final int RED_WAITING = 0;
    static final int RED_RECORDING = 1;
    static final int RED_CAPTURE = 2;
    private static final int RED_OWNERS = 3;

    private static final Panel sInstance = new Panel();

    private final int[][] mLevels = new int[OWNERS][SEGMENTS];
    private final boolean[] mHeld = new boolean[OWNERS];
    private final int[] mReds = new int[RED_OWNERS];
    private final boolean[] mRedHeld = new boolean[RED_OWNERS];


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

    /**
     * Holds red for this owner. A blink writes zero for its dark half, which
     * has to stay dark rather than falling through to a glow underneath, so an
     * owner keeps red until it releases.
     */
    synchronized void setRed(int owner, int level) {
        mReds[owner] = level;
        mRedHeld[owner] = true;
        apply();
    }

    synchronized void releaseRed(int owner) {
        mReds[owner] = 0;
        mRedHeld[owner] = false;
        apply();
    }

    private void apply() {
        int[] white = null;
        for (int owner = OWNERS - 1; owner >= 0 && white == null; owner--) {
            if (mHeld[owner]) {
                white = mLevels[owner];
            }
        }

        int red = 0;
        for (int owner = RED_OWNERS - 1; owner >= 0; owner--) {
            if (mRedHeld[owner]) {
                red = mReds[owner];
                break;
            }
        }

        boolean lit = red > 0;
        final StringBuilder frame = new StringBuilder();
        for (int segment = 0; segment < SEGMENTS; segment++) {
            final int level = white == null ? 0 : white[segment];
            lit |= level > 0;
            frame.append(level).append(' ');
        }

        // Suspend powers the chip down and clears every brightness register,
        // and resume brings it back in stand-by, so what mode it was left in
        // cannot be remembered across a write. The driver ignores a mode it is
        // already in, which makes asking every time close to free.
        if (lit) {
            write("always_on", "1");
            write("operating_mode", Integer.toString(MODE_ACTIVE));
        }

        write("frame_brightness", frame.toString().trim());
        write("single_brightness", RED_REGISTER + " " + red);

        if (!lit) {
            write("operating_mode", Integer.toString(MODE_STANDBY));
            // Only worth keeping powered while it is showing something.
            write("always_on", "0");
        }
    }

    private void write(String node, String value) {
        try (FileOutputStream out = new FileOutputStream(BASE + node)) {
            out.write(value.getBytes());
        } catch (IOException e) {
            Log.e(TAG, "Failed to write " + value + " to " + node, e);
        }
    }
}
