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
 * A ringtone drives the same meter, so an incoming call arrives as the shape of
 * whatever is ringing rather than as a generic blink, and it outranks music.
 *
 * The effect is attached only while something is actually playing, because an
 * attached effect chain costs whether or not the capture callback finds
 * anything.
 */
final class MusicVisualizer extends AudioManager.AudioPlaybackCallback {
    private static final String TAG = "Glyph";

    private static final int CAPTURE_SIZE = 128;

    private static final int BRIGHTNESS = 110;

    /**
     * Loudness maps to height on a fixed decibel scale rather than against a
     * running peak. A peak that adapts has to start somewhere, and starting at
     * the first sample it sees makes the opening frame full scale — which turns
     * every short sound into a flash of the whole strip.
     */
    private static final double FLOOR_DB = -28;

    /** Loud enough to fill the column. Ordinary music sits well below it. */
    private static final double CEILING_DB = -4;

    /** Root-mean-square below this is silence rather than quiet. */
    private static final double SILENCE = 0.005;

    /**
     * How long a player has to stay active before the strip reacts. UI clicks
     * go out as media on this device — Spotify sonifies through a SoundPool
     * tagged USAGE_MEDIA — and the player type that would tell them apart is
     * anonymised away, so duration is what separates a tap from a track.
     */
    private static final long SETTLE_MS = 600;

    /** Fraction of the old height retained as the meter falls. */
    private static final double RELEASE = 0.85;

    private final int[] mLevels = new int[Panel.SEGMENTS];

    private final Handler mHandler;

    private Visualizer mVisualizer;
    private boolean mSettling;
    private boolean mMedia;
    private boolean mRinging;
    private double mHeight;
    private int mOwner = Panel.OWNER_MUSIC;

    private final Runnable mAttach = this::attach;

    MusicVisualizer(Handler handler) {
        mHandler = handler;
    }

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

            final double decibels = 20 * Math.log10(rms);
            final double scaled = (decibels - FLOOR_DB) / (CEILING_DB - FLOOR_DB);
            final double height = rms < SILENCE ? 0
                    : Panel.SEGMENTS * Math.min(1, Math.max(0, scaled));

            // Rise with the music, fall behind it.
            mHeight = height > mHeight ? height : mHeight * RELEASE + height * (1 - RELEASE);

            for (int i = 0; i < Panel.SEGMENTS; i++) {
                final double fill = mHeight - (Panel.SEGMENTS - 1 - i);
                mLevels[i] = fill >= 1 ? BRIGHTNESS
                        : fill <= 0 ? 0 : (int) (BRIGHTNESS * fill);
            }

            Panel.get().setWhite(mOwner, mLevels);
        }

        @Override
        public void onFftDataCapture(Visualizer visualizer, byte[] fft, int rate) {
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
            } else if (usage == AudioAttributes.USAGE_NOTIFICATION_RINGTONE) {
                mRinging = true;
            }
        }
        update();
    }

    private void update() {
        final boolean media = mMedia;
        final boolean ringing = mRinging;

        final int owner = ringing ? Panel.OWNER_RINGING : Panel.OWNER_MUSIC;
        if (owner != mOwner) {
            Panel.get().releaseWhite(mOwner);
            mOwner = owner;
            mHeight = 0;
        }

        if (ringing) {
            // A ring is worth showing at once, and nothing sonifies a ringtone
            // by accident, so it does not wait to settle.
            mSettling = false;
            mHandler.removeCallbacks(mAttach);
            attach();
        } else if (media) {
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
        Panel.get().releaseWhite(mOwner);
    }

    private void release() {
        if (mVisualizer == null) {
            return;
        }
        mVisualizer.setEnabled(false);
        mVisualizer.release();
        mVisualizer = null;
        mHeight = 0;
    }
}
