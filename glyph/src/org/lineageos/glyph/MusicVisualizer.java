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

import java.util.Arrays;
import java.util.List;

/**
 * Drives the white segments from the output mix while media plays, low
 * frequencies at the bottom. The effect is attached only while something is
 * actually playing, because an attached effect chain costs whether or not the
 * capture callback finds anything.
 */
final class MusicVisualizer extends AudioManager.AudioPlaybackCallback {
    private static final String TAG = "Glyph";

    private static final int CAPTURE_SIZE = 128;

    /** Magnitude bin boundaries, one band per segment, log-spaced. */
    private static final int[] BAND_EDGES = {1, 2, 4, 8, 16, 32, 64};

    /** Largest magnitude a byte-valued FFT bin pair can reach. */
    private static final double MAX_MAGNITUDE = 181.0;

    /** Fraction of the previous level retained, so segments fall rather than snap. */
    private static final float DECAY = 0.6f;

    private final int[] mLevels = new int[Panel.SEGMENTS];

    private Visualizer mVisualizer;

    private final Visualizer.OnDataCaptureListener mCaptureListener =
            new Visualizer.OnDataCaptureListener() {
        @Override
        public void onWaveFormDataCapture(Visualizer visualizer, byte[] waveform, int rate) {
        }

        @Override
        public void onFftDataCapture(Visualizer visualizer, byte[] fft, int rate) {
            for (int band = 0; band < Panel.SEGMENTS; band++) {
                double peak = 0;
                for (int bin = BAND_EDGES[band]; bin < BAND_EDGES[band + 1]; bin++) {
                    final int real = fft[bin * 2];
                    final int imaginary = fft[bin * 2 + 1];
                    peak = Math.max(peak, Math.hypot(real, imaginary));
                }

                final int level = Math.min(255,
                        (int) (255 * Math.log1p(peak) / Math.log1p(MAX_MAGNITUDE)));
                // Bass at the bottom, and index 0 is the top segment.
                final int segment = Panel.SEGMENTS - 1 - band;
                mLevels[segment] = Math.max(level, (int) (mLevels[segment] * DECAY));
            }

            Panel.get().setWhite(Panel.OWNER_MUSIC, mLevels);
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
                    Visualizer.getMaxCaptureRate(), false /* waveform */, true /* fft */);
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
        Arrays.fill(mLevels, 0);
    }
}
