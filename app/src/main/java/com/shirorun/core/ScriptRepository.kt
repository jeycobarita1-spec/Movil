package com.shirorun.core

import android.content.Context
import java.io.File

class ScriptRepository(ctx: Context) {
    private val root = File(ctx.filesDir, "scripts").apply { mkdirs() }
    fun list(): List<File> = root.listFiles()?.sortedBy { it.name } ?: emptyList()
    fun save(name: String, src: String) = File(root, "$name.lua").apply { writeText(src) }
    fun read(f: File) = f.readText()
    fun delete(f: File) = f.delete()
}
