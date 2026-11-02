package com.armsx2.config

import com.armsx2.runtime.MainActivityRuntime

/**
 * The screen refresh rate asked for while a game runs (App settings → Screen refresh rate).
 * 0 is Auto, ARMSX2's own choice (a clean multiple of the game's rate with Low Latency on,
 * else the system's); 60, 90 or 120 asks for that mode, the nearest the screen has.
 * This build defaults to 90 Hz (the Anbernic RG477M's middle mode).
 */
object DisplayRefreshPreference {
    private const val KEY = "display.refreshHz"
    const val DEFAULT_HZ = 90
    val OPTIONS = listOf(0, 60, 90, 120)

    fun hz(): Int = runCatching { MainActivityRuntime.prefs.getInt(KEY, DEFAULT_HZ) }.getOrDefault(DEFAULT_HZ)

    fun set(hz: Int) {
        runCatching { MainActivityRuntime.prefs.edit().putInt(KEY, hz).apply() }
    }
}
