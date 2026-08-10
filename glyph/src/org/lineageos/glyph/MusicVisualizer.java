/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glyph;

import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.audiofx.Visualizer;
import android.util.Log;

import java.util.List;

/**
 * Drives the white segments as a level meter while media plays: loudness is the
 * height of the column, not the brightness of six bands. Height is the one
 * channel this strip reads well, because the eye ranks position but cannot rank
 * brightness against a shifting ambient level.
 *
 * The topmost lit segment carries the fraction, which gives the column a
 * smoothness six steps cannot.
 *
 * The effect is attached only while something is actually playing, because an
 * attached effect chain costs whether or not the capture callback finds
 * anything.
 */
final class MusicVisualizer extends AudioManager.AudioPlaybackCallback {
    private static final String TAG = "Glyph";

    private static final int CAPTURE_SIZE = 128;

    private static final int BRIGHTNESS = 200;

    /**
     * Loudness is scaled against a decaying peak, so quiet material uses the
     * whole column instead of a corner of it.
     */
    private static final double REFERENCE_DECAY = 0.995;

    /** Root-mean-square below this is silence rather than quiet. */
    private static final double SILENCE = 0.005;

    /** Fraction of the old height retained as the meter falls. */
    private static final double RELEASE = 0.85;

    private final int[] mLevels = new int[Panel.SEGMENTS];

    private Visualizer mVisualizer;
    private double mReference;
    private double mHeight;

    private final Visualizer.OnDataCaptureListener mCaptureListener =
            new Visualizer.OnDataCaptureListener() {
        @Override
        public void onWaveFormDataCapture(Visualizer visualizer, byte[] waveform, int rate) {
            double sum = 0;
            for (byte sample : waveform) {
                // Unsigned 8-bit PCM, silence at 128.
                final double centred = ((sample & 0xFF) - 128) / 128.0;
                sum += centred * centred;
            }
            final double rms = Math.sqrt(sum / waveform.length);

            mReference = Math.max(rms, mReference * REFERENCE_DECAY);

            final double height = rms < SILENCE || mReference < SILENCE
                    ? 0 : Panel.SEGMENTS * Math.min(1, rms / mReference);

            // Rise with the music, fall behind it.
            mHeight = height > mHeight ? height : mHeight * RELEASE + height * (1 - RELEASE);

            for (int i = 0; i < Panel.SEGMENTS; i++) {
                final double fill = mHeight - (Panel.SEGMENTS - 1 - i);
                mLevels[i] = fill >= 1 ? BRIGHTNESS
                        : fill <= 0 ? 0 : (int) (BRIGHTNESS * fill);
            }

            Panel.get().setWhite(Panel.OWNER_MUSIC, mLevels);
        }

        @Override
        public void onFftDataCapture(Visualizer visualizer, byte[] fft, int rate) {
        }
    };

    @Override
    public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
        // Unprivileged callers are only handed configurations that are active,
        // so presence is the whole of the test.
        boolean playing = false;
        for (AudioPlaybackConfiguration config : configs) {
            if (config.getAudioAttributes().getUsage() == AudioAttributes.USAGE_MEDIA) {
                playing = true;
                break;
            }
        }

        if (playing) {
            attach();
        } else {
            detach();
        }
    }

    private synchronized void attach() {
        if (mVisualizer != null) {
            return;
        }

        try {
            mVisualizer = new Visualizer(0);
            mVisualizer.setCaptureSize(CAPTURE_SIZE);
            mVisualizer.setDataCaptureListener(mCaptureListener,
                    Visualizer.getMaxCaptureRate(), true /* waveform */, false /* fft */);
            mVisualizer.setEnabled(true);
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
        Panel.get().releaseWhite(Panel.OWNER_MUSIC);
    }

    private void release() {
        if (mVisualizer == null) {
            return;
        }
        mVisualizer.setEnabled(false);
        mVisualizer.release();
        mVisualizer = null;
        mReference = 0;
        mHeight = 0;
    }
}
