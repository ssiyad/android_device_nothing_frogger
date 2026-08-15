/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.essentialkey.ui;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;

import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import org.lineageos.essentialkey.Constants;
import org.lineageos.essentialkey.R;

/**
 * One row per gesture.
 *
 * The preferences are marked non-persistent and read and written by hand,
 * because they live in Settings.System rather than in this app's shared
 * preferences -- the half of the app that acts on them runs in system_server and
 * cannot see anything else.
 *
 * "Open application" is a list entry whose stored value is not the entry value:
 * choosing it opens a picker and what gets written is the chosen component. So
 * every read has to map an `app:` value back onto the list entry, and every
 * summary for one has to come from the package manager.
 */
public class SettingsFragment extends PreferenceFragmentCompat
        implements Preference.OnPreferenceChangeListener {
    private static final int REQUEST_PICK_APP = 1;

    private static final String[] KEYS = {
            Constants.KEY_SINGLE_PRESS,
            Constants.KEY_DOUBLE_PRESS,
            Constants.KEY_LONG_PRESS,
    };

    /** Not an action, only the list entry that leads to the picker. */
    private static final String ENTRY_OPEN_APP = "open_app";

    private static final String STATE_PENDING_KEY = "pending_key";

    /** Which gesture the open picker belongs to; survives a rotation behind it. */
    private String mPendingKey;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.essential_key_settings, rootKey);

        if (savedInstanceState != null) {
            mPendingKey = savedInstanceState.getString(STATE_PENDING_KEY);
        }

        for (String key : KEYS) {
            final ListPreference preference = findPreference(key);
            preference.setPersistent(false);
            preference.setOnPreferenceChangeListener(this);
            refresh(preference);
        }
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_PENDING_KEY, mPendingKey);
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object value) {
        final String key = preference.getKey();
        if (ENTRY_OPEN_APP.equals(value)) {
            // Nothing is stored until an app is actually chosen, so backing out
            // of the picker leaves the gesture as it was.
            mPendingKey = key;
            startActivityForResult(new Intent(getContext(), AppPickerActivity.class),
                    REQUEST_PICK_APP);
            return false;
        }

        store(key, (String) value);
        refresh((ListPreference) preference);
        return false;
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK_APP || resultCode != Activity.RESULT_OK
                || data == null || mPendingKey == null) {
            return;
        }

        store(mPendingKey, Constants.ACTION_APP_PREFIX
                + data.getStringExtra(AppPickerActivity.EXTRA_COMPONENT));
        refresh(findPreference(mPendingKey));
        mPendingKey = null;
    }

    private void store(String key, String value) {
        Settings.System.putString(getContext().getContentResolver(), key, value);
    }

    private void refresh(ListPreference preference) {
        final String stored = read(preference.getKey());

        if (stored.startsWith(Constants.ACTION_APP_PREFIX)) {
            preference.setValue(ENTRY_OPEN_APP);
            preference.setSummary(labelOf(
                    stored.substring(Constants.ACTION_APP_PREFIX.length())));
            return;
        }

        preference.setValue(stored);
        preference.setSummary(preference.getEntry());
    }

    private String read(String key) {
        final String value = Settings.System.getString(
                getContext().getContentResolver(), key);
        return value == null ? Constants.defaultFor(key) : value;
    }

    /** Falls back to the flattened name, so an uninstalled app still reads as something. */
    private CharSequence labelOf(String flattened) {
        final ComponentName component = ComponentName.unflattenFromString(flattened);
        final PackageManager pm = getContext().getPackageManager();
        if (component != null) {
            try {
                return pm.getActivityInfo(component, 0).loadLabel(pm);
            } catch (PackageManager.NameNotFoundException e) {
                Log.w(Constants.TAG, "Gone: " + flattened);
            }
        }
        return flattened;
    }
}
