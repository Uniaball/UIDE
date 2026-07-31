package com.uniaball.uide.data

import java.io.File

/**
 * CRUD over the app's private internal storage directory (Context.getFilesDir()).
 * No external-storage permission is required, and files are removed with the app.
 *
 * All names are sanitized so a caller cannot escape the directory (no "/" or "..").
 */
class FileRepository(private val root: File) {

    init {
        // Make sure the storage directory exists before any read/write.
        root.mkdirs()
    }

    /**
     * Files written by the system into our private directory (not user content).
     * The AndroidX profile-installer drops `profileinstalled` here on first run,
     * which must never show up as a user-editable file.
     */
    private val systemFiles: Set<String> = setOf("profileinstalled")

    fun listFiles(): List<File> =
        (root.listFiles() ?: emptyArray())
            .filter { it.isFile }
            .filter { !it.name.startsWith(".") }
            .filter { it.name !in systemFiles && !it.name.startsWith("profileinstaller") }
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
        fun fromFilesDir(filesDir: File): FileRepository = FileRepository(filesDir)
    }
}
