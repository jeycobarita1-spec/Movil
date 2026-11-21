package com.shirorun.core

import java.io.File

object AntiDetect {
    private val TARGETS = listOf(
        "/data/data/com.roblox.client/cache",
        "/data/data/com.roblox.client/files/logs",
        "/data/local/tmp/shirorun/queue",
        "/data/local/tmp/shirorun/done"
    )

    fun wipe() { TARGETS.forEach { runCatching { File(it).deleteRecursively() } } }

    fun kill() {
        runCatching {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "am force-stop com.roblox.client")).waitFor()
        }
    }
}
