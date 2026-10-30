package com.househunter.app

import android.content.Context

/** Small settings that should survive closing the app: which layers are hidden, the pen color, snap on/off. */
class AppPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("househunter", Context.MODE_PRIVATE)

    var hiddenLayers: Set<LayerGroup>
        get() {
            var saved = prefs.getStringSet("hiddenLayers", emptySet()) ?: emptySet()
            // School zones are a busy layer, so it starts off; this switches it off just once for everyone.
            if (!prefs.getBoolean("schoolsIntroduced", false)) {
                saved = saved + LayerGroup.Schools.name
                prefs.edit().putBoolean("schoolsIntroduced", true).putStringSet("hiddenLayers", saved).apply()
            }
            return saved.mapNotNull { name -> LayerGroup.values().firstOrNull { it.name == name } }.toSet()
        }
        set(value) = prefs.edit().putStringSet("hiddenLayers", value.map { it.name }.toSet()).apply()

    /** Drawing layers switched off in the Layers panel, by layer id. */
    var hiddenDrawingLayers: Set<String>
        get() = prefs.getStringSet("hiddenDrawingLayers", emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet("hiddenDrawingLayers", value).apply()

    /** The drawing layer new highlights go onto. */
    var activeLayerId: String
        get() = prefs.getString("activeLayerId", DrawingLayers.DEFAULT_ID) ?: DrawingLayers.DEFAULT_ID
        set(value) = prefs.edit().putString("activeLayerId", value).apply()

    var penColor: Int
        get() = prefs.getInt("penColor", 2).coerceIn(0, PenColors.hex.lastIndex)
        set(value) = prefs.edit().putInt("penColor", value).apply()

    var snap: Boolean
        get() = prefs.getBoolean("snap", true)
        set(value) = prefs.edit().putBoolean("snap", value).apply()

    /** When the last export was saved, in epoch millis; 0 if never. */
    var lastBackupAt: Long
        get() = prefs.getLong("lastBackupAt", 0L)
        set(value) = prefs.edit().putLong("lastBackupAt", value).apply()
}
