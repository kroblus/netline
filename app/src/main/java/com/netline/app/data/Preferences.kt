package com.netline.app.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

private val Context.settingsStore by preferencesDataStore("netline_settings")
data class AppSettings(val x: Float = 1f, val y: Float = .35f, val range: String = "Full Session",
    val language: String = com.netline.app.defaultLanguage())
class Preferences(context: Context) {
    private val store = context.settingsStore
    private val x = floatPreferencesKey("ball_x")
    private val y = floatPreferencesKey("ball_y")
    private val range = stringPreferencesKey("export_range")
    private val language = stringPreferencesKey("language")
    val settings = store.data.map { AppSettings(it[x] ?: 1f, it[y] ?: .35f, it[range] ?: "Full Session",
        it[language]?.takeIf { code -> code == "zh" || code == "en" } ?: com.netline.app.defaultLanguage()) }
    suspend fun position(px: Float, py: Float) { store.edit { it[x] = px; it[y] = py } }
    suspend fun range(value: String) { store.edit { it[range] = value } }
    suspend fun language(value: String) { require(value == "zh" || value == "en"); store.edit { it[language] = value } }
}
