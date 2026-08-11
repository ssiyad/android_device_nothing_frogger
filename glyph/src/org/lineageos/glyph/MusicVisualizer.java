/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glyph;

import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.audiofx.Visualizer;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;

import java.util.List;

/**
 * Drives the white segments from the beat rather than the volume.
 *
 * Loudness does not work as the height of the column. Music is mastered to sit
 * at a near-constant level, so a mean over any short window barely moves, and
 * the bar parks at whatever that track's loudness happens to be. What reads as
 * music is the bass hitting: the column takes the energy of the low bands, rises
 * with it at once and falls back slowly, so each beat lands.
 *
 * The scale is a peak that decays over seconds, which lets a quiet track and a
 * loud one both use the whole strip without a level being fixed anywhere. It
 * starts high and settles downwards, because a scale that starts at the first
 * sample it sees makes the opening frame full.
 *
 * A ringtone drives the same meter and is not held back by any of the gating,
 * since a call is worth showing whichever way the phone is lying.
 */
final class MusicVisualizer extends AudioManager.AudioPlaybackCallback {
    private static final String TAG = "Glyph";

    private static final int BRIGHTNESS = 110;

    /** Bass, in hertz. Kick and bassline live here; voices and cymbals do not. */
    private static final double BASS_LOW_HZ = 40;
    private static final double BASS_HIGH_HZ = 260;

    /** Fraction of the envelope kept per frame as it falls back between beats. */
    private static final double RELEASE = 0.8;

    /** Fraction of the scale kept per frame, so it drifts over seconds. */
    private static final double REFERENCE_DECAY = 0.995;

    /** Where the scale starts, high enough that the first frames read low. */
    private static final double INITIAL_REFERENCE = 600;

    /** Band energy below this is silence rather than a quiet passage. */
    private static final double SILENCE = 20;

    /** Above one, this keeps the column near the floor except on a beat. */
    private static final double CONTRAST = 2.0;

    private static final long SETTLE_MS = 600;

    /**
     * The capture is of the whole output mix, so once attached the meter reacts
     * to every sound the phone makes — an unlock chime and a shutter click look
     * exactly like a kick drum. Staying attached through silence is what turns
     * those into blinks, so quiet for this long lets go until music starts
     * again.
     */
    private static final long QUIET_MS = 4000;

    private final int[] mLevels = new int[Panel.SEGMENTS];
    private final Handler mHandler;
    private final AudioManager mAudioManager;

    private Visualizer mVisualizer;
    private boolean mSettling;
    private boolean mMedia;
    private boolean mRinging;
    private boolean mFaceDown;
    private int mBassFrom;
    private int mBassTo;
    private long mQuietSince;
    private double mEnvelope;
    private double mReference = INITIAL_REFERENCE;
    private double mHeight;
    private int mOwner = Panel.OWNER_MUSIC;

    private final Runnable mAttach = new Runnable() {
        @Override
        public void run() {
            mSettling = false;
            if (allowed()) {
                attach();
            }
        }
    };

    MusicVisualizer(AudioManager audioManager, Handler handler) {
        mAudioManager = audioManager;
        mHandler = handler;
    }

    private final Visualizer.OnDataCaptureListener mCaptureListener =
            new Visualizer.OnDataCaptureListener() {
        @Override
        public void onWaveFormDataCapture(Visualizer visualizer, byte[] waveform, int rate) {
        }

        @Override
        public void onFftDataCapture(Visualizer visualizer, byte[] fft, int rate) {
            double energy = 0;
            for (int bin = mBassFrom; bin < mBassTo && bin * 2 + 1 < fft.length; bin++) {
                energy += Math.hypot(fft[bin * 2], fft[bin * 2 + 1]);
            }

            // Rise with the beat, fall back between them.
            mEnvelope = energy > mEnvelope ? energy
                    : mEnvelope * RELEASE + energy * (1 - RELEASE);
            mReference = Math.max(mEnvelope, mReference * REFERENCE_DECAY);

            if (energy >= SILENCE) {
                mQuietSince = 0;
            } else if (mQuietSince == 0) {
                mQuietSince = SystemClock.uptimeMillis();
            } else if (SystemClock.uptimeMillis() - mQuietSince > QUIET_MS) {
                detach();
                return;
            }

            final double share = mReference <= 0 ? 0 : mEnvelope / mReference;
            mHeight = energy < SILENCE ? 0
                    : Panel.SEGMENTS * Math.pow(Math.min(1, share), CONTRAST);

            for (int i = 0; i < Panel.SEGMENTS; i++) {
                final double fill = mHeight - (Panel.SEGMENTS - 1 - i);
                mLevels[i] = fill >= 1 ? BRIGHTNESS
                        : fill <= 0 ? 0 : (int) (BRIGHTNESS * fill);
            }

            Panel.get().setWhite(mOwner, mLevels);
        }
    };

    @Override
    public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
        // Unprivileged callers are only handed configurations that are active,
        // so presence is the whole of the test.
        mMedia = false;
        mRinging = false;
        for (AudioPlaybackConfiguration config : configs) {
            final int usage = config.getAudioAttributes().getUsage();
            if (usage == AudioAttributes.USAGE_MEDIA) {
                mMedia = true;
            } else if (usage == AudioAttributes.USAGE_NOTIFICATION_RINGTONE
                    && mAudioManager.getMode() == AudioManager.MODE_RINGTONE) {
                // Ringtone usage alone is not a call: plenty of ordinary sounds
                // carry it. Only the audio mode says the phone is ringing.
                mRinging = true;
            }
        }
        update();
    }

    void onFaceDownChanged(boolean faceDown) {
        mFaceDown = faceDown;
        update();
    }

    private void update() {
        final int owner = mRinging ? Panel.OWNER_RINGING : Panel.OWNER_MUSIC;
        if (owner != mOwner) {
            Panel.get().releaseWhite(mOwner);
            mOwner = owner;
            reset();
        }

        if (mRinging) {
            // A ring is worth showing at once, and nothing sonifies a ringtone
            // by accident, so it neither waits to settle nor waits to be seen.
            mSettling = false;
            mHandler.removeCallbacks(mAttach);
            attach();
            return;
        }

        // The meter is decoration, and decoration on a face the holder cannot
        // see costs an effect chain for nothing.
        if (allowed()) {
            if (!mSettling) {
                mSettling = true;
                mHandler.postDelayed(mAttach, SETTLE_MS);
            }
        } else {
            mSettling = false;
            mHandler.removeCallbacks(mAttach);
            detach();
        }
    }

    /** Whether anything should be driving the meter at all. */
    private boolean allowed() {
        return mRinging || (mMedia && mFaceDown);
    }

    private synchronized void attach() {
        if (mVisualizer != null) {
            return;
        }

        try {
            mVisualizer = new Visualizer(0);
            final int size = Math.min(1024, Visualizer.getCaptureSizeRange()[1]);
            mVisualizer.setCaptureSize(size);

            // Bin width follows the capture rate, so the band is asked for in
            // hertz rather than assumed.
            final double binHz = (mVisualizer.getSamplingRate() / 1000.0) / size;
            mBassFrom = Math.max(1, (int) (BASS_LOW_HZ / binHz));
            mBassTo = Math.max(mBassFrom + 1, (int) (BASS_HIGH_HZ / binHz));

            mVisualizer.setDataCaptureListener(mCaptureListener,
                    Visualizer.getMaxCaptureRate(), false /* waveform */, true /* fft */);
            mVisualizer.setEnabled(true);
            Log.i(TAG, "visualizer attached, faceDown=" + mFaceDown);
        } catch (RuntimeException e) {
            Log.e(TAG, "Failed to attach the visualizer", e);
            release();
        }
    }

    private synchronized void detach() {
        if (mVisualizer == null) {
            return;
        }
        release();
        Log.i(TAG, "visualizer detached");
        Panel.get().releaseWhite(mOwner);
    }

    private void release() {
        if (mVisualizer == null) {
            return;
        }
        mVisualizer.setEnabled(false);
        mVisualizer.release();
        mVisualizer = null;
        reset();
    }

    private void reset() {
        mEnvelope = 0;
        mReference = INITIAL_REFERENCE;
        mHeight = 0;
        mQuietSince = 0;
    }
}
