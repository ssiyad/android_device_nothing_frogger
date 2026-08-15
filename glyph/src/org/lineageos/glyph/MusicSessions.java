/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glyph;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Says whether music is playing.
 *
 * An output stream carrying USAGE_MEDIA is a poor answer to that: a video
 * autoplaying in a feed, a game, a voice note and a settings preview all use it,
 * and a paused player keeps its stream open for seconds afterwards, so the meter
 * ran on things that were not music and kept running on music that had stopped.
 *
 * A media session is the thing an app publishes when it means to be a player,
 * and its transport state is the thing that says play or pause. Asking it costs
 * a callback per session and answers the moment the state changes rather than
 * whenever the audio stream happens to close.
 *
 * What it deliberately does not do is separate music from video. Nothing in a
 * session tells the two apart — metadata is optional and half the players that
 * matter leave the artist empty — and a video played to a phone lying on its
 * face is being listened to anyway.
 */
final class MusicSessions {
    private static final String TAG = "Glyph";

    interface Listener {
        void onMusicChanged(boolean playing);
    }

    private final Context mContext;
    private final Handler mHandler;
    private final Listener mListener;
    private final List<MediaController> mControllers = new ArrayList<>();

    private MediaSessionManager mManager;
    private boolean mPlaying;

    /**
     * A controller's callback is only told about its own session, so every
     * session has to be watched. The instance is shared: registration is kept
     * per controller inside the framework, and nothing here depends on which
     * one spoke.
     */
    private final MediaController.Callback mCallback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            update();
        }

        @Override
        public void onAudioInfoChanged(MediaController.PlaybackInfo info) {
            update();
        }

        @Override
        public void onSessionDestroyed() {
            update();
        }
    };

    MusicSessions(Context context, Handler handler, Listener listener) {
        mContext = context;
        mHandler = handler;
        mListener = listener;
    }

    void register() {
        ServiceWait.whenPublished(mHandler, Context.MEDIA_SESSION_SERVICE, this::registerSessions);
    }

    /**
     * Even the manager is asked for late. MediaSessionManager reads its binder
     * once, in its constructor, and is then kept per context and never built
     * again — so asking for it before the service is published is what breaks
     * it, and nothing done afterwards recovers.
     */
    private void registerSessions() {
        mManager = mContext.getSystemService(MediaSessionManager.class);
        try {
            mManager.addOnActiveSessionsChangedListener(this::onSessions, null, mHandler);
            onSessions(mManager.getActiveSessions(null));
        } catch (RuntimeException e) {
            Log.e(TAG, "Cannot read the media sessions, the meter will not run", e);
        }
    }

    private void onSessions(List<MediaController> controllers) {
        for (MediaController controller : mControllers) {
            controller.unregisterCallback(mCallback);
        }
        mControllers.clear();

        if (controllers != null) {
            for (MediaController controller : controllers) {
                controller.registerCallback(mCallback, mHandler);
                mControllers.add(controller);
            }
        }
        update();
    }

    private void update() {
        boolean playing = false;
        for (MediaController controller : mControllers) {
            final PlaybackState state = controller.getPlaybackState();
            if (state == null || state.getState() != PlaybackState.STATE_PLAYING) {
                continue;
            }

            // A session can be routed anywhere; only the ones that are media
            // are the meter's business.
            final MediaController.PlaybackInfo info = controller.getPlaybackInfo();
            final AudioAttributes attributes = info == null ? null : info.getAudioAttributes();
            if (attributes != null && attributes.getUsage() != AudioAttributes.USAGE_MEDIA) {
                continue;
            }

            playing = true;
            break;
        }

        if (playing == mPlaying) {
            return;
        }
        mPlaying = playing;
        Log.i(TAG, "music " + (playing ? "playing" : "stopped"));
        mListener.onMusicChanged(playing);
    }
}
