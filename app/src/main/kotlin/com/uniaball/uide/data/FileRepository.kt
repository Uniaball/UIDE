package com.uniaball.uide.data

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * CRUD on external storage (SD card).  Uses [Context.getExternalFilesDir],
 * which lives on the shared / external storage partition and requires
 * **zero** permissions on every Android version.
 */
class FileRepository(private val root: File) {

    init {
        if (!root.mkdirs() && !root.exists()) {
            Log.e(TAG, "无法创建存储目录: ${root.absolutePath}")
        }
    }

    fun listFiles(): List<File> =
        (root.listFiles() ?: emptyArray())
            .filter { it.isFile }
            .sortedByDescending { it.lastModified() }

    fun read(name: String): String {
        val safe = sanitize(name) ?: return ""
        val f = File(root, safe)
        return try {
            if (f.exists()) f.readText() else ""
        } catch (e: IOException) {
            Log.e(TAG, "读取文件失败: ${f.name}", e)
            ""
        }
    }

    fun write(name: String, content: String): Boolean {
        val safe = sanitize(name) ?: return false
        return try {
            File(root, safe).writeText(content)
            true
        } catch (e: IOException) {
            Log.e(TAG, "写入文件失败: $safe", e)
            false
        }
    }

    fun create(name: String): Boolean {
        val safe = sanitize(name) ?: return false
        if (File(root, safe).exists()) return false
        return try {
            File(root, safe).createNewFile()
        } catch (e: IOException) {
            Log.e(TAG, "创建文件失败: $safe", e)
            false
        }
    }

    fun delete(name: String): Boolean {
        val safe = sanitize(name) ?: return false
        return try {
            File(root, safe).delete()
        } catch (e: Exception) {
            Log.e(TAG, "删除文件失败: $safe", e)
            false
        }
    }

    /**
     * Allow only a plain file name: non-empty, <=255 chars, no path separators,
     * no "..", no colons (the name is used in navigation routes).
     */
    private fun sanitize(name: String): String? {
        val n = name.trim()
        if (n.isEmpty() || n.length > 255) return null
        if (n == "." || n == "..") return null
        if (n.contains('/') || n.contains('\\') || n.contains(':')) return null
        if (n.any { it == '\u0000' }) return null
        return n
    }

    companion object {
        private const val TAG = "UIDE:FileRepo"

        /**
         * External storage (SD card), app-specific directory.
         * Falls back to internal [Context.getFilesDir] if external storage is
         * unmounted.  User files are placed in a `uide/` subdirectory.
         */
        fun fromContext(context: Context): FileRepository {
            val base = context.getExternalFilesDir(null) ?: context.filesDir
            return FileRepository(File(base, "uide"))
        }
    }
}
