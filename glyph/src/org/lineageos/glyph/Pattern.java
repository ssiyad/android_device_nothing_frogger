/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glyph;

import android.os.Handler;

/**
 * Plays a fixed sequence of frames on the white segments.
 *
 * Position reads at a glance where brightness does not, so what a pattern
 * carries is movement: which way it runs and how fast. Brightness is only used
 * within a frame, to give the movement a direction.
 */
final class Pattern {
    private final Handler mHandler;
    private final int mOwner;

    private int[][] mFrames;
    private long mStepMs;
    private boolean mLoop;
    private int mIndex;
    private boolean mRunning;

    private final Runnable mStep = new Runnable() {
        @Override
        public void run() {
            if (mIndex >= mFrames.length) {
                if (!mLoop) {
                    stop();
                    return;
                }
                mIndex = 0;
            }
            Panel.get().setWhite(mOwner, mFrames[mIndex++]);
            mHandler.postDelayed(this, mStepMs);
        }
    };

    Pattern(Handler handler, int owner) {
        mHandler = handler;
        mOwner = owner;
    }

    boolean isRunning() {
        return mRunning;
    }

    void play(int[][] frames, long stepMs, boolean loop) {
        mHandler.removeCallbacks(mStep);
        mFrames = frames;
        mStepMs = stepMs;
        mLoop = loop;
        mIndex = 0;
        mRunning = true;
        mHandler.post(mStep);
    }

    void stop() {
        mHandler.removeCallbacks(mStep);
        mRunning = false;
        Panel.get().releaseWhite(mOwner);
    }

    /**
     * A comet running the length of the strip, brightest at its head and
     * halving for each segment behind it. The head carries on past the far end
     * so the tail leaves with it rather than piling up there.
     *
     * Which way it runs is the whole of what tells two patterns apart, so it is
     * the one thing a caller has to choose.
     */
    static int[][] sweep(int brightness, int tail, boolean up) {
        final int[][] frames = new int[Panel.SEGMENTS + tail][Panel.SEGMENTS];
        for (int frame = 0; frame < frames.length; frame++) {
            for (int behind = 0; behind <= tail; behind++) {
                final int head = up ? Panel.SEGMENTS - 1 - frame : frame;
                final int segment = up ? head + behind : head - behind;
                if (segment >= 0 && segment < Panel.SEGMENTS) {
                    frames[frame][segment] = brightness >> behind;
                }
            }
        }
        return frames;
    }

    /** The same frames back to back, for a pattern that reads better twice. */
    static int[][] repeat(int[][] frames, int times) {
        final int[][] out = new int[frames.length * times][];
        for (int i = 0; i < out.length; i++) {
            out[i] = frames[i % frames.length];
        }
        return out;
    }
}
