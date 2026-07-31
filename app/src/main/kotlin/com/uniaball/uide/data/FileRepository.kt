package com.uniaball.uide.data

import java.io.File

/**
 * CRUD over a dedicated subdirectory of the app's private internal storage.
 * All user files live in `filesDir/uide/` so that AndroidX libraries (e.g.
 * profileinstaller) writing their own markers into `filesDir/` can never
 * pollute the user-visible file list.
 */
class FileRepository(private val root: File) {

    init {
        root.mkdirs()
        migrateLegacyFiles()
    }

    /** Move user files that were created before the "uide/" subdirectory was
     * introduced from `filesDir/` into `filesDir/uide/`. System files (e.g.
     * profileinstalled or profileinstaller_*) are deliberately left behind. */
    private fun migrateLegacyFiles() {
        val parent = root.parentFile ?: return
        parent.listFiles()?.forEach { old ->
            if (!old.isFile) return@forEach
            if (old.name == "profileinstalled") return@forEach
            if (old.name.startsWith("profileinstaller")) return@forEach
            if (old.name.startsWith(".")) return@forEach
            old.renameTo(File(root, old.name))
        }
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
        fun fromFilesDir(filesDir: File): FileRepository =
            FileRepository(File(filesDir, "uide"))
    }
}
