package com.trackeye.app

import android.content.Context
import org.json.JSONObject

data class UserProfile(
    val name: String,
    val calibration: Calibration,
    val settings: TrackSettings,
    val actionMap: Map<String, String>
)

fun defaultActionMap(): Map<String, String> = linkedMapOf(
    Gesture.BLINK.name to "click",
    Gesture.DOUBLE_BLINK.name to "back",
    Gesture.LONG_BLINK.name to "activate",
    Gesture.BROW_RAISE.name to "switch_mode",
    Gesture.MOUTH_LEFT.name to "back",
    Gesture.MOUTH_RIGHT.name to "forward",
    Gesture.HEAD_UP.name to "scroll_up",
    Gesture.HEAD_DOWN.name to "scroll_down",
    Gesture.HEAD_LEFT.name to "back",
    Gesture.HEAD_RIGHT.name to "forward",
    Gesture.DWELL.name to "click"
)

fun presetProfiles(): List<UserProfile> = listOf(
    UserProfile(
        "Telefon – chat",
        Calibration(blinkThreshold = 0.3f),
        TrackSettings(dwellMs = 700, blinkThreshold = 0.3f),
        defaultActionMap()
    ),
    UserProfile(
        "PC – prohlížeč",
        Calibration(),
        TrackSettings(dwellMs = 900),
        defaultActionMap()
    ),
    UserProfile(
        "Tablet – vzdělávání",
        Calibration(),
        TrackSettings(dwellMs = 1200, holdToActivate = true),
        defaultActionMap()
    )
)

/** Local-only profile + stats storage. No data ever leaves the device. */
class ProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("trackeye", Context.MODE_PRIVATE)

    fun saveProfile(profile: UserProfile) {
        val json = JSONObject().apply {
            put("name", profile.name)
            put("centerYaw", profile.calibration.centerYaw.toDouble())
            put("centerPitch", profile.calibration.centerPitch.toDouble())
            put("yawRange", profile.calibration.yawRange.toDouble())
            put("pitchRange", profile.calibration.pitchRange.toDouble())
            put("browBaseline", profile.calibration.browBaseline.toDouble())
            put("blinkThreshold", profile.calibration.blinkThreshold.toDouble())
            put("dwellMs", profile.settings.dwellMs)
            put("confidence", profile.settings.confidenceThreshold.toDouble())
            put("tiltSensitivity", profile.settings.tiltSensitivity.toDouble())
            put("overlay", profile.settings.overlayEnabled)
            put("dynamicFps", profile.settings.dynamicFps)
            put("holdToActivate", profile.settings.holdToActivate)
            put("actions", JSONObject(profile.actionMap as Map<*, *>))
        }
        prefs.edit().putString("profile_${profile.name}", json.toString()).apply()
        prefs.edit().putString("active_profile", profile.name).apply()
    }

    fun loadProfile(name: String): UserProfile? {
        val raw = prefs.getString("profile_$name", null) ?: return null
        return runCatching {
            val j = JSONObject(raw)
            val actions = mutableMapOf<String, String>()
            val aj = j.optJSONObject("actions") ?: JSONObject()
            aj.keys().forEach { actions[it] = aj.optString(it) }
            UserProfile(
                name = j.optString("name", name),
                calibration = Calibration(
                    centerYaw = j.optDouble("centerYaw", 0.0).toFloat(),
                    centerPitch = j.optDouble("centerPitch", 0.0).toFloat(),
                    yawRange = j.optDouble("yawRange", 18.0).toFloat(),
                    pitchRange = j.optDouble("pitchRange", 14.0).toFloat(),
                    browBaseline = j.optDouble("browBaseline", 0.0).toFloat(),
                    blinkThreshold = j.optDouble("blinkThreshold", 0.25).toFloat()
                ),
                settings = TrackSettings(
                    dwellMs = j.optLong("dwellMs", 800),
                    blinkThreshold = j.optDouble("blinkThreshold", 0.25).toFloat(),
                    confidenceThreshold = j.optDouble("confidence", 0.6).toFloat(),
                    tiltSensitivity = j.optDouble("tiltSensitivity", 1.0).toFloat(),
                    overlayEnabled = j.optBoolean("overlay", true),
                    dynamicFps = j.optBoolean("dynamicFps", true),
                    holdToActivate = j.optBoolean("holdToActivate", false)
                ),
                actionMap = if (actions.isEmpty()) defaultActionMap() else actions
            )
        }.getOrNull()
    }

    fun profileNames(): List<String> =
        prefs.all.keys.filter { it.startsWith("profile_") }.map { it.removePrefix("profile_") }.sorted()

    fun activeProfileName(): String? = prefs.getString("active_profile", null)

    // --- session statistics (comfort score inputs) ---
    fun addStat(key: String, delta: Long) {
        prefs.edit().putLong("stat_$key", prefs.getLong("stat_$key", 0) + delta).apply()
    }

    fun stat(key: String): Long = prefs.getLong("stat_$key", 0)

    fun clearStats() {
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith("stat_") }.forEach { editor.remove(it) }
        editor.apply()
    }
}
