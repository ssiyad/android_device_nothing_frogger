/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.essentialkey.ui;

import android.os.Bundle;

import androidx.fragment.app.Fragment;

import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;
import com.android.settingslib.collapsingtoolbar.R;

/** Reached from Settings > System > Buttons, never from a launcher icon. */
public class SettingsActivity extends CollapsingToolbarBaseActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final Fragment existing = getSupportFragmentManager()
                .findFragmentById(R.id.content_frame);
        if (existing == null) {
            getSupportFragmentManager().beginTransaction()
                    .add(R.id.content_frame, new SettingsFragment())
                    .commit();
        }
    }
}
