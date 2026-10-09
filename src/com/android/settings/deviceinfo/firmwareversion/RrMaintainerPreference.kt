/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.settings.deviceinfo.firmwareversion

import android.content.Context
import android.os.SystemProperties
import androidx.preference.Preference
import com.android.settings.R
import com.android.settingslib.metadata.PreferenceAvailabilityProvider
import com.android.settingslib.metadata.PreferenceMetadata
import com.android.settingslib.metadata.PreferenceSummaryProvider
import com.android.settingslib.metadata.UI_ONLY_PREFERENCE
import com.android.settingslib.metadata.preferencesapi.preconditions.PreconditionStability
import com.android.settingslib.preference.PreferenceBinding

/** Build maintainer from RR_MAINTAINER; hidden when the build sets none. */
class RrMaintainerPreference :
    PreferenceMetadata,
    PreferenceSummaryProvider,
    PreferenceAvailabilityProvider,
    PreferenceBinding {

    override val key: String
        get() = "rr_maintainer"

    override val purpose: Int
        get() = R.string.os_firmware_version_purpose

    override val title: Int
        get() = R.string.rr_maintainer

    override val availabilityDescription = UI_ONLY_PREFERENCE

    override fun getAvailabilityStability() = PreconditionStability.STABLE_UNTIL_APK_UPDATE

    override fun isAvailable(context: Context): Boolean =
        SystemProperties.get(MAINTAINER_PROPERTY).isNotEmpty()

    override fun getSummary(context: Context): CharSequence? =
        SystemProperties.get(MAINTAINER_PROPERTY)

    override fun bind(preference: Preference, metadata: PreferenceMetadata) {
        super.bind(preference, metadata)
        preference.isSelectable = false
        preference.isCopyingEnabled = true
    }

    companion object {
        const val MAINTAINER_PROPERTY: String = "ro.rr.maintainer"
    }
}
