/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.essentialbutton;

import android.app.ActivityOptions;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.display.DisplayManager;
import android.media.AudioManager;
import android.media.session.MediaSessionLegacyHelper;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.VibrationAttributes;
import android.os.Vibrator;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.WindowManager;

import com.android.internal.util.ScreenshotHelper;
import com.android.internal.view.RotationPolicy;

/**
 * Runs whatever a gesture is bound to.
 *
 * This runs inside system_server, so it holds every permission the calls below
 * want and can use the internal variants of them. That matters in two places:
 * {@code setRingerModeInternal} skips the do-not-disturb reconciliation the
 * public setter applies on behalf of third-party apps, and {@code setZenMode}
 * with {@code fromUser} set is what makes the shade treat the change as
 * something the user did rather than something that happened to them.
 *
 * Whether an action wakes the screen belongs to the action. A torch or a skipped
 * track is worth having precisely because it works with the phone in a pocket,
 * while a screenshot nobody can see is worthless. So most of these run as they
 * are, and only the three that produce something to look at wake first.
 */
final class ActionExecutor {
    /** Long enough to cover waking the display and starting an activity. */
    private static final long ACTION_WAKELOCK_MS = 3000;

    /** Given up on if the display does not come up; better a dark shot than none. */
    private static final long DISPLAY_ON_TIMEOUT_MS = 1500;

    /** lineageos.content.Intent.ACTION_SCREEN_CAMERA_GESTURE, without the dependency. */
    private static final String ACTION_SCREEN_CAMERA_GESTURE =
            "lineageos.intent.action.SCREEN_CAMERA_GESTURE";

    private final Context mContext;
    private final Handler mHandler;
    private final PowerManager mPowerManager;
    private final PowerManager.WakeLock mWakeLock;

    /**
     * Shared, never rebuilt. Every call to it registers a broadcast receiver and
     * none of them unregisters, so one helper per screenshot would leak
     * receivers inside system_server for as long as it runs.
     */
    private final ScreenshotHelper mScreenshotHelper;

    private String mTorchCameraId;
    private boolean mTorchResolved;
    private boolean mTorchOn;

    private final CameraManager.TorchCallback mTorchCallback =
            new CameraManager.TorchCallback() {
                @Override
                public void onTorchModeChanged(String cameraId, boolean enabled) {
                    if (cameraId.equals(mTorchCameraId)) {
                        mTorchOn = enabled;
                    }
                }

                @Override
                public void onTorchModeUnavailable(String cameraId) {
                    if (cameraId.equals(mTorchCameraId)) {
                        mTorchOn = false;
                    }
                }
            };

    ActionExecutor(Context context, Handler handler) {
        mContext = context;
        mHandler = handler;
        mPowerManager = context.getSystemService(PowerManager.class);
        mWakeLock = mPowerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, Constants.TAG + ":action");
        mScreenshotHelper = new ScreenshotHelper(context);
    }

    void perform(String settingKey) {
        // Read at the moment of use. The settings live in /data/system/users,
        // which is device-encrypted, so this answers on the lock screen before
        // the first unlock as well as after it.
        String action = Settings.System.getStringForUser(mContext.getContentResolver(),
                settingKey, UserHandle.USER_CURRENT);
        if (action == null) {
            action = Constants.defaultFor(settingKey);
        }

        if (Constants.ACTION_NONE.equals(action)) {
            return;
        }

        Log.i(Constants.TAG, "action " + action);

        // Held past the call, because most of what follows finishes somewhere
        // else -- a display coming up, an activity starting, a broadcast being
        // delivered -- and the phone is free to suspend again in between.
        mWakeLock.acquire(ACTION_WAKELOCK_MS);

        if (action.startsWith(Constants.ACTION_APP_PREFIX)) {
            launchApp(action.substring(Constants.ACTION_APP_PREFIX.length()));
            return;
        }

        switch (action) {
            case Constants.ACTION_SCREENSHOT -> screenshot();
            case Constants.ACTION_CAMERA -> camera();
            case Constants.ACTION_FLASHLIGHT -> flashlight();
            case Constants.ACTION_PLAY_PAUSE -> mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
            case Constants.ACTION_NEXT -> mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT);
            case Constants.ACTION_PREVIOUS -> mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
            case Constants.ACTION_DND -> toggleDnd();
            case Constants.ACTION_RINGER -> cycleRinger();
            case Constants.ACTION_ROTATION -> toggleRotationLock();
            default -> Log.w(Constants.TAG, "Unknown action " + action);
        }
    }

    void performHaptic() {
        // USAGE_HARDWARE_FEEDBACK is the class that survives silent and
        // do-not-disturb, which is what a press confirmation has to do: the
        // gesture is the only evidence the key was even held long enough.
        mContext.getSystemService(Vibrator.class).performHapticFeedback(
                HapticFeedbackConstants.LONG_PRESS,
                VibrationAttributes.USAGE_HARDWARE_FEEDBACK,
                Constants.TAG,
                HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING,
                0);
    }

    private void screenshot() {
        whenDisplayOn(() -> mScreenshotHelper.takeScreenshot(
                WindowManager.TAKE_SCREENSHOT_FULLSCREEN,
                WindowManager.ScreenshotSource.SCREENSHOT_VENDOR_GESTURE,
                mHandler, null));
    }

    private void camera() {
        // SystemUI owns the decision about which camera a locked phone may
        // open, and it wakes the screen itself, so this is a request rather
        // than a launch.
        mContext.sendBroadcastAsUser(new Intent(ACTION_SCREEN_CAMERA_GESTURE),
                UserHandle.CURRENT, android.Manifest.permission.STATUS_BAR_SERVICE);
    }

    private void flashlight() {
        final CameraManager cameraManager = mContext.getSystemService(CameraManager.class);

        // Resolved on first use rather than in the constructor: that runs while
        // PhoneWindowManager is being built, long before the camera service is
        // up, and asking it anything there would either fail or hold up the
        // boot.
        if (!mTorchResolved) {
            mTorchResolved = true;
            mTorchCameraId = findRearCamera(cameraManager);
            if (mTorchCameraId != null) {
                cameraManager.registerTorchCallback(mTorchCallback, mHandler);
            }
        }

        if (mTorchCameraId == null) {
            Log.w(Constants.TAG, "No rear camera, cannot toggle the torch");
            return;
        }
        try {
            cameraManager.setTorchMode(mTorchCameraId, !mTorchOn);
        } catch (CameraAccessException e) {
            Log.e(Constants.TAG, "Cannot toggle the torch", e);
        }
    }

    private void mediaKey(int keyCode) {
        final MediaSessionLegacyHelper helper = MediaSessionLegacyHelper.getHelper(mContext);
        final long now = SystemClock.uptimeMillis();
        final KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0);
        helper.sendMediaButtonEvent(down, true);
        helper.sendMediaButtonEvent(KeyEvent.changeAction(down, KeyEvent.ACTION_UP), true);
    }

    private void toggleDnd() {
        final NotificationManager nm = mContext.getSystemService(NotificationManager.class);
        final boolean off = nm.getZenMode() == Settings.Global.ZEN_MODE_OFF;
        // A null condition leaves it on until something turns it off, which is
        // the only reading of a button press that does not invent a duration.
        nm.setZenMode(off
                        ? Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS
                        : Settings.Global.ZEN_MODE_OFF,
                null, Constants.TAG, true);
    }

    private void cycleRinger() {
        final AudioManager am = mContext.getSystemService(AudioManager.class);
        final int next = switch (am.getRingerModeInternal()) {
            case AudioManager.RINGER_MODE_NORMAL -> AudioManager.RINGER_MODE_VIBRATE;
            case AudioManager.RINGER_MODE_VIBRATE -> AudioManager.RINGER_MODE_SILENT;
            default -> AudioManager.RINGER_MODE_NORMAL;
        };
        am.setRingerModeInternal(next);
    }

    private void toggleRotationLock() {
        // Through RotationPolicy rather than ACCELEROMETER_ROTATION directly,
        // because locking has to freeze the angle the phone is being held at.
        RotationPolicy.setRotationLock(mContext,
                !RotationPolicy.isRotationLocked(mContext), Constants.TAG);
    }

    private void launchApp(String flattened) {
        final ComponentName component = ComponentName.unflattenFromString(flattened);
        if (component == null) {
            Log.w(Constants.TAG, "Cannot parse component " + flattened);
            return;
        }
        try {
            mContext.getPackageManager().getActivityInfo(component, 0);
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(Constants.TAG, "Gone: " + flattened);
            return;
        }

        wakeUp();

        // Insecure keyguards get out of the way; a secure one stays up and the
        // app appears behind it once unlocked. Asking for credentials outright
        // would be the wrong answer to a button press.
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setDismissKeyguardIfInsecure();

        final Intent intent = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try {
            mContext.startActivityAsUser(intent, options.toBundle(), UserHandle.CURRENT);
        } catch (Exception e) {
            Log.e(Constants.TAG, "Cannot launch " + flattened, e);
        }
    }

    /**
     * Waking is asynchronous, and a capture taken before the display is up is a
     * black rectangle. So the work waits on the display saying it is on, with a
     * timeout so a display that never arrives costs one bad screenshot rather
     * than silence.
     */
    private void whenDisplayOn(Runnable work) {
        final DisplayManager dm = mContext.getSystemService(DisplayManager.class);
        if (dm.getDisplay(Display.DEFAULT_DISPLAY).getState() == Display.STATE_ON) {
            work.run();
            return;
        }

        wakeUp();

        final DisplayWait wait = new DisplayWait(dm, work);
        dm.registerDisplayListener(wait, mHandler);
        mHandler.postDelayed(wait::run, DISPLAY_ON_TIMEOUT_MS);
    }

    /** Runs the work once, on whichever of the display coming up or the timeout lands first. */
    private final class DisplayWait implements DisplayManager.DisplayListener, Runnable {
        private final DisplayManager mDisplayManager;
        private final Runnable mWork;
        private boolean mDone;

        DisplayWait(DisplayManager displayManager, Runnable work) {
            mDisplayManager = displayManager;
            mWork = work;
        }

        @Override
        public void run() {
            if (mDone) {
                return;
            }
            mDone = true;
            mDisplayManager.unregisterDisplayListener(this);
            mWork.run();
        }

        @Override
        public void onDisplayChanged(int displayId) {
            if (displayId == Display.DEFAULT_DISPLAY
                    && mDisplayManager.getDisplay(displayId).getState() == Display.STATE_ON) {
                run();
            }
        }

        @Override
        public void onDisplayAdded(int displayId) {
        }

        @Override
        public void onDisplayRemoved(int displayId) {
        }
    }

    private void wakeUp() {
        mPowerManager.wakeUp(SystemClock.uptimeMillis(),
                PowerManager.WAKE_REASON_WAKE_KEY, Constants.TAG);
    }

    private static String findRearCamera(CameraManager cameraManager) {
        try {
            for (String id : cameraManager.getCameraIdList()) {
                final CameraCharacteristics c = cameraManager.getCameraCharacteristics(id);
                final Boolean flash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                final Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                if (Boolean.TRUE.equals(flash)
                        && facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    return id;
                }
            }
        } catch (CameraAccessException e) {
            Log.e(Constants.TAG, "Cannot enumerate the cameras", e);
        }
        return null;
    }
}
