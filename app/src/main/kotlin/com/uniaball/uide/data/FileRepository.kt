package com.uniaball.uide.data

import android.content.Context
import java.io.File

/**
 * CRUD on external storage (SD card).  Uses [Context.getExternalFilesDir],
 * which lives on the shared / external storage partition and requires
 * **zero** permissions on every Android version.
 */
class FileRepository(private val root: File) {

    init {
        root.mkdirs()
    }

    fun listFiles(): List<File> =
        (root.listFiles() ?: emptyArray())
            .filter { it.isFile }
            .sortedByDescending { it.lastModified() }

    fun exists(name: String): Boolean {
        val safe = sanitize(name) ?: return false
        return File(root, safe).exists()
    }

    fun read(name: String): String {
        val safe = sanitize(name) ?: return ""
        val f = File(root, safe)
        return if (f.exists()) f.readText() else ""
    }

    fun write(name: String, content: String): Boolean {
        val safe = sanitize(name) ?: return false
        return runCatching { File(root, safe).writeText(content) }.isSuccess
    }

    fun create(name: String): Boolean {
        val safe = sanitize(name) ?: return false
        if (File(root, safe).exists()) return false
        return runCatching { File(root, safe).createNewFile() }.getOrDefault(false)
    }

    fun delete(name: String): Boolean {
        val safe = sanitize(name) ?: return false
        return runCatching { File(root, safe).delete() }.getOrDefault(false)
    }

    /** Allow only a plain file name: non-empty, <=255 chars, no separators or "..". */
    private fun sanitize(name: String): String? {
        val n = name.trim()
        if (n.isEmpty() || n.length > 255) return null
        if (n == "." || n == "..") return null
        if (n.contains('/') || n.contains('\\')) return null
        if (n.any { it == '\u0000' }) return null
        return n
    }

    companion object {
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
