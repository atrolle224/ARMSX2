package com.armsx2.config

import android.util.Log
import org.json.JSONObject

/**
 * EverQuest Online Adventures (this build's game): its per-game settings turn texture
 * replacements on, so the EQOA HD texture pack loads once it is installed in
 * textures/SLUS-20744/replacements (Texture manager → Import folder). Loaded as the game
 * draws (asynchronously) and not precached: the pack is 2.2 GB of PNGs, more than fits in
 * memory at once. Applied once per install; changing it in the game's settings afterwards sticks.
 */
object EqoaTexturePreset {
    private const val TAG = "EqoaTexturePreset"
    private const val KEY_APPLIED = "preset.eqoaTextures.v1"
    val SERIALS = listOf("SLUS-20744")

    fun apply() {
        val prefs = runCatching { com.armsx2.runtime.MainActivityRuntime.prefs }.getOrNull() ?: return
        if (prefs.getBoolean(KEY_APPLIED, false)) return
        for (serial in SERIALS) {
            val o = ConfigStore.loadOverrides(serial) ?: JSONObject()
            o.put("loadTextureReplacements", true)
            o.put("loadTextureReplacementsAsync", true)
            o.put("precacheTextureReplacements", false)
            ConfigStore.saveOverrides(serial, o)
            Log.i(TAG, "$serial: texture replacements on (per-game)")
        }
        prefs.edit().putBoolean(KEY_APPLIED, true).apply()
    }
}
