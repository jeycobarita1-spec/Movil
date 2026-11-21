package com.shirorun.core

import java.io.File

object Injector {
    init { System.loadLibrary("shirorun") }

    external fun injectNative(pkg: String, soPath: String): Boolean

    private const val ROBLOX = "com.roblox.client"
    private val DIR = File("/data/local/tmp/shirorun")
    private val QUEUE = File(DIR, "queue")

    fun isRooted() = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su").any { File(it).exists() }

    fun attach(): Boolean {
        val so = File(DIR, "libshirorun.so")
        if (!so.exists()) return false
        return injectNative(ROBLOX, so.absolutePath)
    }

    fun run(name: String, src: String): Boolean = try {
        QUEUE.mkdirs()
        File(QUEUE, "$name.lua").writeText(src)
        true
    } catch (e: Exception) { false }

    fun lastResult(name: String): String = try {
        File(File(DIR, "done"), "$name.lua.rc").readText()
    } catch (e: Exception) { "-" }
}
