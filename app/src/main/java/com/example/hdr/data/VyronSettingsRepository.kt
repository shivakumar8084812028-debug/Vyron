package com.example.hdr.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.hdr.model.ToneMappingConfig
import com.example.hdr.model.VyronVisionMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.vyronDataStore: DataStore<Preferences> by preferencesDataStore(name = "vyron_vision_prefs")

class VyronSettingsRepository(private val context: Context) {

    private object Keys {
        val VISION_MODE = stringPreferencesKey("vision_mode")
        val TARGET_SDR_NITS = floatPreferencesKey("target_sdr_nits")
        val HIGHLIGHT_ROLLOFF = floatPreferencesKey("highlight_rolloff")
        val SHADOW_LIFT = floatPreferencesKey("shadow_lift")
        val SMART_CONTRAST = floatPreferencesKey("smart_contrast")
        val SATURATION_GUARD = floatPreferencesKey("saturation_guard")
        val SHARPNESS_AMOUNT = floatPreferencesKey("sharpness_amount")
        val COMPARISON_ENABLED = booleanPreferencesKey("comparison_enabled")
    }

    val configFlow: Flow<ToneMappingConfig> = context.vyronDataStore.data.map { prefs ->
        val modeName = prefs[Keys.VISION_MODE] ?: VyronVisionMode.AUTO_ADAPTIVE.name
        val mode = runCatching { VyronVisionMode.valueOf(modeName) }
            .getOrDefault(VyronVisionMode.AUTO_ADAPTIVE)
        ToneMappingConfig(
            mode = mode,
            targetSdrPeakNits = prefs[Keys.TARGET_SDR_NITS] ?: 380f,
            highlightRolloff = prefs[Keys.HIGHLIGHT_ROLLOFF] ?: 0.85f,
            shadowLift = prefs[Keys.SHADOW_LIFT] ?: 0.14f,
            smartContrast = prefs[Keys.SMART_CONTRAST] ?: 0.22f,
            saturationGuard = prefs[Keys.SATURATION_GUARD] ?: 0.92f,
            sharpnessAmount = prefs[Keys.SHARPNESS_AMOUNT] ?: 0.18f,
            comparisonEnabled = prefs[Keys.COMPARISON_ENABLED] ?: false
        )
    }

    suspend fun saveConfig(config: ToneMappingConfig) {
        context.vyronDataStore.edit { prefs ->
            prefs[Keys.VISION_MODE] = config.mode.name
            prefs[Keys.TARGET_SDR_NITS] = config.targetSdrPeakNits
            prefs[Keys.HIGHLIGHT_ROLLOFF] = config.highlightRolloff
            prefs[Keys.SHADOW_LIFT] = config.shadowLift
            prefs[Keys.SMART_CONTRAST] = config.smartContrast
            prefs[Keys.SATURATION_GUARD] = config.saturationGuard
            prefs[Keys.SHARPNESS_AMOUNT] = config.sharpnessAmount
            prefs[Keys.COMPARISON_ENABLED] = config.comparisonEnabled
        }
    }
}
