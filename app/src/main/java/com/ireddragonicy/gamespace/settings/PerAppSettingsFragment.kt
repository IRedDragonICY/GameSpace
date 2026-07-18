/*
 * Copyright (C) 2026 crDroid Android Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ireddragonicy.gamespace.settings

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView

import androidx.fragment.app.activityViewModels
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.SwitchPreference

import com.android.settingslib.widget.LayoutPreference
import com.android.settingslib.widget.SettingsBasePreferenceFragment

import dagger.hilt.android.AndroidEntryPoint

import com.ireddragonicy.gamespace.R
import com.ireddragonicy.gamespace.ui.viewmodel.PerAppSettingsViewModel

@AndroidEntryPoint(SettingsBasePreferenceFragment::class)
class PerAppSettingsFragment : Hilt_PerAppSettingsFragment(),
    Preference.OnPreferenceChangeListener {

    private val viewModel: PerAppSettingsViewModel by activityViewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activity?.title = context?.getString(R.string.per_app_title)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.per_app_preferences, rootKey)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Disable item animations so preferences appear instantly (no slide-in)
        listView?.itemAnimator = null

        // Header (game icon + label)
        findPreference<LayoutPreference>(PREF_HEADERS)?.apply {
            isSelectable = false
            findViewById<ImageView>(android.R.id.icon)
                ?.setImageDrawable(viewModel.gameIcon)
            findViewById<TextView>(android.R.id.title)
                ?.text = viewModel.gameLabel
        }



        // Thermal profile picker (synced with MiThermal)
        findPreference<ListPreference>(PREF_THERMAL_PROFILE)?.apply {
            val opts = viewModel.thermalProfileOptions
            entries = opts.map { it.second }.toTypedArray()
            entryValues = opts.map { it.first.toString() }.toTypedArray()
            value = viewModel.thermalProfile.toString()
            summary = opts.firstOrNull { it.first == viewModel.thermalProfile }?.second ?: "Follow Global"
            onPreferenceChangeListener = this@PerAppSettingsFragment
        }

        // GPU composition toggle (synced with DisplayManagerService)
        findPreference<SwitchPreference>(PREF_GPU_COMPOSITION)?.apply {
            isChecked = viewModel.gpuComposition
            onPreferenceChangeListener = this@PerAppSettingsFragment
        }

        // Resolution picker
        findPreference<ListPreference>(PREF_RESOLUTION)?.apply {
            val opts = viewModel.resolutionOptions
            entries = opts.map { it.second }.toTypedArray()
            entryValues = opts.map { it.first }.toTypedArray()
            value = viewModel.resolution
            summary = opts.firstOrNull { it.first == viewModel.resolution }?.second ?: "Native"
            onPreferenceChangeListener = this@PerAppSettingsFragment
        }

        // ANGLE driver
        findPreference<ListPreference>(PREF_ANGLE_DRIVER)?.apply {
            if (!viewModel.angleFeatureAvailable) {
                isVisible = false
                return@apply
            }
            val opts = viewModel.angleDriverOptions
            entries = opts.map { it.second }.toTypedArray()
            entryValues = opts.map { it.first }.toTypedArray()
            value = viewModel.angleDriverChoice
            onPreferenceChangeListener = this@PerAppSettingsFragment
        }

        // AFME Frame Generation multiplier (Off / 2x / 3x / 4x)
        findPreference<ListPreference>(PREF_AFME)?.apply {
            val opts = viewModel.afmeMultiplierOptions
            entries = opts.map { it.second }.toTypedArray()
            entryValues = opts.map { it.first.toString() }.toTypedArray()
            value = viewModel.afmeMultiplier.toString()
            summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            onPreferenceChangeListener = this@PerAppSettingsFragment
        }

        // AFME Extrapolation Factor (Auto / 0.25 / 0.33 / 0.5 / 0.67 / 0.75 / 1.0)
        findPreference<ListPreference>(PREF_AFME_FACTOR)?.apply {
            val opts = viewModel.afmeFactorOptions
            entries = opts.map { it.second }.toTypedArray()
            entryValues = opts.map { it.first }.toTypedArray()
            value = viewModel.afmeFactor
            summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            onPreferenceChangeListener = this@PerAppSettingsFragment
            isEnabled = viewModel.afmeMultiplier > 0
        }

        // Graphics Enhancement (Off / SGSR1 / SGSR2 / MobFGSR)
        findPreference<ListPreference>(PREF_SGSR)?.apply {
            val opts = viewModel.sgsrOptions
            entries = opts.map { it.second }.toTypedArray()
            entryValues = opts.map { it.first.toString() }.toTypedArray()
            value = viewModel.sgsrMode.toString()
            summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            onPreferenceChangeListener = this@PerAppSettingsFragment
        }

        // Smooth Motion toggle
        findPreference<SwitchPreference>(PREF_SMOOTH_MOTION)?.apply {
            isChecked = viewModel.smoothMotionEnabled
            onPreferenceChangeListener = this@PerAppSettingsFragment
        }

        // VRS picker — use SimpleSummaryProvider to avoid String.format crash
        // (VRS entries contain % chars like "~25% GPU save" which breaks String.format)
        findPreference<ListPreference>(PREF_VRS)?.apply {
            val opts = viewModel.vrsOptions
            entries = opts.map { it.second }.toTypedArray()
            entryValues = opts.map { it.first.toString() }.toTypedArray()
            value = viewModel.vrsLevel.toString()
            summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            onPreferenceChangeListener = this@PerAppSettingsFragment
        }

        // Color Enhance toggle
        findPreference<SwitchPreference>(PREF_COLOR_ENHANCE)?.apply {
            isChecked = viewModel.colorEnhanceEnabled
            onPreferenceChangeListener = this@PerAppSettingsFragment
        }

        // Touch Tuning
        findPreference<SwitchPreference>(PREF_TOUCH_SUPER_REPORT)?.apply {
            isChecked = viewModel.touchSuperReport
            onPreferenceChangeListener = this@PerAppSettingsFragment
        }
        val expertModeSwitch = findPreference<SwitchPreference>(PREF_TOUCH_EXPERT_MODE)?.apply {
            isChecked = viewModel.touchExpertMode
            onPreferenceChangeListener = this@PerAppSettingsFragment
        }
        findPreference<com.android.settingslib.widget.SliderPreference>(PREF_TOUCH_EXPERT_PRESET)?.apply {
            min = 1
            max = 3
            value = viewModel.touchExpertPreset
            isVisible = viewModel.touchExpertMode
            setSliderIncrement(1)
            setTickVisible(true)
            onPreferenceChangeListener = this@PerAppSettingsFragment
        }
        findPreference<com.android.settingslib.widget.SliderPreference>(PREF_TOUCH_THRESHOLD)?.apply {
            min = 0
            max = 4
            value = viewModel.touchThreshold
            isEnabled = !viewModel.touchExpertMode
            setSliderIncrement(1)
            setTickVisible(true)
            onPreferenceChangeListener = this@PerAppSettingsFragment
        }
        findPreference<com.android.settingslib.widget.SliderPreference>(PREF_TOUCH_TOLERANCE)?.apply {
            min = 0
            max = 4
            value = viewModel.touchTolerance
            isEnabled = !viewModel.touchExpertMode
            setSliderIncrement(1)
            setTickVisible(true)
            onPreferenceChangeListener = this@PerAppSettingsFragment
        }
        findPreference<com.android.settingslib.widget.SliderPreference>(PREF_TOUCH_AIM_SENS)?.apply {
            min = 0
            max = 4
            value = viewModel.touchAimSens
            isEnabled = !viewModel.touchExpertMode
            setSliderIncrement(1)
            setTickVisible(true)
            onPreferenceChangeListener = this@PerAppSettingsFragment
        }
        findPreference<com.android.settingslib.widget.SliderPreference>(PREF_TOUCH_TAP_STAB)?.apply {
            min = 0
            max = 4
            value = viewModel.touchTapStab
            isEnabled = !viewModel.touchExpertMode
            setSliderIncrement(1)
            setTickVisible(true)
            onPreferenceChangeListener = this@PerAppSettingsFragment
        }
        findPreference<com.android.settingslib.widget.SliderPreference>(PREF_TOUCH_EDGE_FILTER)?.apply {
            min = 0
            max = 3
            value = viewModel.touchEdgeFilter
            isEnabled = !viewModel.touchExpertMode
            setSliderIncrement(1)
            setTickVisible(true)
            onPreferenceChangeListener = this@PerAppSettingsFragment
        }


        // Unregister
        findPreference<Preference>(PREF_UNREGISTER)?.apply {
            summary = context.getString(R.string.per_app_unregister, viewModel.gameLabel)
            setOnPreferenceClickListener {
                viewModel.unregisterGame()
                activity?.setResult(
                    Activity.RESULT_OK,
                    Intent().apply {
                        putExtra(PREF_UNREGISTER, viewModel.packageName)
                    }
                )
                activity?.finish()
                true
            }
        }
    }

    override fun onPreferenceChange(preference: Preference, newValue: Any?): Boolean {
        return when (preference.key) {

            PREF_THERMAL_PROFILE -> {
                val profileIdx = (newValue as String).toIntOrNull() ?: 0
                viewModel.updateThermalProfile(profileIdx)
                val opts = viewModel.thermalProfileOptions
                preference.summary = opts.firstOrNull { it.first == profileIdx }?.second ?: "Follow Global"
                true
            }
            PREF_GPU_COMPOSITION -> {
                val enabled = newValue as Boolean
                viewModel.updateGpuComposition(enabled)
                true
            }
            PREF_RESOLUTION -> {
                val factor = newValue as String
                viewModel.updateResolution(factor)
                val opts = viewModel.resolutionOptions
                preference.summary = opts.firstOrNull { it.first == factor }?.second ?: "Native"
                true
            }
            PREF_ANGLE_DRIVER -> {
                viewModel.updateAngleDriverChoice(newValue as String)
                true
            }
            PREF_AFME -> {
                val mult = (newValue as String).toIntOrNull() ?: 0
                viewModel.updateAfmeMultiplier(mult)
                // Enable/disable factor preference based on AFME state
                findPreference<ListPreference>(PREF_AFME_FACTOR)?.isEnabled = mult > 0
                // Summary auto-updated by SimpleSummaryProvider
                true
            }
            PREF_AFME_FACTOR -> {
                viewModel.updateAfmeFactor(newValue as String)
                // Summary auto-updated by SimpleSummaryProvider
                true
            }
            PREF_SGSR -> {
                val mode = (newValue as String).toIntOrNull() ?: 0
                viewModel.updateSgsrMode(mode)
                // Summary auto-updated by SimpleSummaryProvider
                true
            }
            PREF_SMOOTH_MOTION -> {
                viewModel.updateSmoothMotion(newValue as Boolean)
                true
            }
            PREF_VRS -> {
                val level = (newValue as String).toIntOrNull() ?: 0
                viewModel.updateVrs(level)
                // Summary auto-updated by SimpleSummaryProvider
                true
            }
            PREF_COLOR_ENHANCE -> {
                viewModel.updateColorEnhance(newValue as Boolean)
                true
            }
            PREF_TOUCH_SUPER_REPORT -> {
                viewModel.updateTouchSuperReport(newValue as Boolean)
                true
            }
            PREF_TOUCH_EXPERT_MODE -> {
                val enabled = newValue as Boolean
                viewModel.updateTouchExpertMode(enabled)
                findPreference<com.android.settingslib.widget.SliderPreference>(PREF_TOUCH_EXPERT_PRESET)?.isVisible = enabled
                findPreference<com.android.settingslib.widget.SliderPreference>(PREF_TOUCH_THRESHOLD)?.isEnabled = !enabled
                findPreference<com.android.settingslib.widget.SliderPreference>(PREF_TOUCH_TOLERANCE)?.isEnabled = !enabled
                findPreference<com.android.settingslib.widget.SliderPreference>(PREF_TOUCH_AIM_SENS)?.isEnabled = !enabled
                findPreference<com.android.settingslib.widget.SliderPreference>(PREF_TOUCH_TAP_STAB)?.isEnabled = !enabled
                findPreference<com.android.settingslib.widget.SliderPreference>(PREF_TOUCH_EDGE_FILTER)?.isEnabled = !enabled
                true
            }
            PREF_TOUCH_EXPERT_PRESET -> {
                viewModel.updateTouchExpertPreset(newValue as Int)
                true
            }
            PREF_TOUCH_THRESHOLD -> {
                viewModel.updateTouchThreshold(newValue as Int)
                true
            }
            PREF_TOUCH_TOLERANCE -> {
                viewModel.updateTouchTolerance(newValue as Int)
                true
            }
            PREF_TOUCH_AIM_SENS -> {
                viewModel.updateTouchAimSens(newValue as Int)
                true
            }
            PREF_TOUCH_TAP_STAB -> {
                viewModel.updateTouchTapStab(newValue as Int)
                true
            }
            PREF_TOUCH_EDGE_FILTER -> {
                viewModel.updateTouchEdgeFilter(newValue as Int)
                true
            }
            else -> false
        }
    }

    companion object {
        const val PREF_HEADERS = "headers"
        const val PREF_THERMAL_PROFILE = "per_app_thermal_profile"
        const val PREF_GPU_COMPOSITION = "per_app_gpu_composition"
        const val PREF_RESOLUTION = "per_app_resolution"
        const val PREF_ANGLE_DRIVER = "per_app_angle_driver"
        const val PREF_AFME = "per_app_afme"
        const val PREF_AFME_FACTOR = "per_app_afme_factor"
        const val PREF_SGSR = "per_app_sgsr"
        const val PREF_SMOOTH_MOTION = "per_app_smooth_motion"
        const val PREF_VRS = "per_app_vrs"
        const val PREF_COLOR_ENHANCE = "per_app_color_enhance"
        
        const val PREF_TOUCH_SUPER_REPORT = "per_app_touch_super_report"
        const val PREF_TOUCH_EXPERT_MODE = "per_app_touch_expert_mode"
        const val PREF_TOUCH_EXPERT_PRESET = "per_app_touch_expert_preset"
        const val PREF_TOUCH_THRESHOLD = "per_app_touch_threshold"
        const val PREF_TOUCH_TOLERANCE = "per_app_touch_tolerance"
        const val PREF_TOUCH_AIM_SENS = "per_app_touch_aim_sens"
        const val PREF_TOUCH_TAP_STAB = "per_app_touch_tap_stab"
        const val PREF_TOUCH_EDGE_FILTER = "per_app_touch_edge_filter"

        const val PREF_UNREGISTER = "per_app_unregister"
    }
}
