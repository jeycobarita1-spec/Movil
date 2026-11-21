package com.shirorun.core

import java.io.File
import org.json.JSONObject

object FlagManager {
    private const val DIR = "/data/data/com.roblox.client/files/ClientSettings"
    private const val FILE = "$DIR/ClientAppSettings.json"

    val presets = mapOf(
        "Physics" to mapOf(
            "DFIntTaskSchedulerTargetFps" to 240,
            "DFIntS2PhysicsSenderRate" to 60,
            "FIntRenderShadowIntensity" to 0
        ),
        "Network" to mapOf(
            "DFIntRakNetMinAckGrowthPercent" to 100,
            "DFIntMaxDataPacketsPerFrame" to 60
        ),
        "Camera" to mapOf(
            "FIntCameraMaxZoomDistance" to 1000
        )
    )

    fun apply(flags: Map<String, Any>): Boolean = try {
        File(DIR).mkdirs()
        val o = JSONObject()
        flags.forEach { (k, v) -> o.put(k, v) }
        File(FILE).writeText(o.toString(2))
        true
    } catch (e: Exception) { false }

    fun clear() = File(FILE).delete()
}
