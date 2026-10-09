package com.sowens.stocked.ui

import android.content.Context
import android.util.AtomicFile
import java.io.File

/** Keeps an incoming share pending until review closes; large text never enters a Bundle. */
object IncomingRecipeShareStore {
    private const val LIMIT = 2 * 1024 * 1024
    private fun file(context: Context) = AtomicFile(File(context.filesDir, "pending-recipe-share.txt"))
    @Synchronized fun save(context: Context, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= LIMIT) { "Shared recipe exceeds the import limit." }
        val target = file(context)
        val output = target.startWrite()
        try { output.write(bytes); target.finishWrite(output) }
        catch (error: Exception) { target.failWrite(output); throw error }
    }
    @Synchronized fun load(context: Context): String? {
        val target = file(context)
        if (!target.baseFile.exists() && !File(target.baseFile.path + ".bak").exists()) return null
        return target.openRead().use { input ->
            val bytes = input.readBytesBounded()
            bytes.toString(Charsets.UTF_8)
        }
    }
    private fun java.io.InputStream.readBytesBounded(): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            require(output.size() + count <= LIMIT) { "Saved share exceeds the import limit; its file has been preserved." }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
    @Synchronized fun clear(context: Context) = file(context).delete()
}
