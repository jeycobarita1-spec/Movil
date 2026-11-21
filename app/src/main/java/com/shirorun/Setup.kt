package com.shirorun

import android.content.Context
import java.io.File

object Setup {
    private const val DIR = "/data/local/tmp/shirorun"

    fun provision(ctx: Context) {
        val so = File(ctx.applicationInfo.nativeLibraryDir, "libshirorun.so")
        val boot = File(ctx.cacheDir, "boot.lua")
        runCatching {
            ctx.assets.open("boot.lua").use { i ->
                boot.outputStream().use { o -> i.copyTo(o) }
            }
        }
        val cmd = "mkdir -p $DIR/queue $DIR/done; " +
                "cp ${so.absolutePath} $DIR/libshirorun.so 2>/dev/null; " +
                "cp ${boot.absolutePath} $DIR/boot.lua 2>/dev/null; " +
                "chmod -R 0777 $DIR"
        runCatching { Runtime.getRuntime().exec(arrayOf("su", "-c", cmd)).waitFor() }
    }
}
