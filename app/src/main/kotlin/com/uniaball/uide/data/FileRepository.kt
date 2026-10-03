package com.uniaball.uide.data

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * CRUD on external storage (SD card).  Uses [Context.getExternalFilesDir],
 * which lives on the shared / external storage partition and requires
 * **zero** permissions on every Android version.
 *
 * Entries are addressed by **relative paths** under the root directory
 * (e.g. `sub/foo.c`, `"CMakeLists.txt"`).  Reading / writing / deletion
 * accept full relative paths; creation only accepts a plain single-segment
 * name — new files and folders are always created inside the currently
 * browsed directory.
 */
class FileRepository(private val root: File) {

    init {
        if (!root.mkdirs() && !root.exists()) {
            Log.e(TAG, "无法创建存储目录: ${root.absolutePath}")
        }
    }

    /**
     * List the entries (files and directories) of the directory at
     * [path] (`""` = root).  Directories are listed first, then files —
     * each group sorted by modification time, newest first.
     */
    fun listEntries(path: String = ""): List<File> {
        val dir = resolveDir(path) ?: return emptyList()
        return (dir.listFiles() ?: emptyArray())
            .filter { !it.isHidden && it.name != "." && it.name != ".." }
            .sortedWith(compareByDescending<File> { it.isDirectory }.thenByDescending { it.lastModified() })
    }

    fun read(name: String): String {
        val safe = sanitize(name) ?: return ""
        val f = File(root, safe)
        return try {
            if (f.isFile) f.readText() else ""
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

    /**
     * Create a new (empty) file named [name] inside the directory [dir]
     * (`""` = root).  [name] must be a plain single-segment name without
     * `/`, `\` or `:`.  Returns false if it already exists.
     */
    fun create(dir: String, name: String): Boolean {
        val f = childFile(dir, name) ?: return false
        if (f.exists()) return false
        return try {
            f.parentFile?.mkdirs()
            f.createNewFile()
        } catch (e: IOException) {
            Log.e(TAG, "创建文件失败: ${f.name}", e)
            false
        }
    }

    /**
     * Create a new directory named [name] inside the directory [dir]
     * (`""` = root).  [name] must be a plain single-segment name without
     * `/`, `\` or `:`.  Returns false if it already exists.
     */
    fun createDirectory(dir: String, name: String): Boolean {
        val f = childFile(dir, name) ?: return false
        if (f.exists()) return false
        return try {
            f.parentFile?.mkdirs()
            f.mkdir()
        } catch (e: Exception) {
            Log.e(TAG, "创建目录失败: ${f.name}", e)
            false
        }
    }

    /** [File] for a single-segment [name] inside [dir], or null if invalid. */
    private fun childFile(dir: String, name: String): File? {
        val safeDir = dir.trim().let { if (it.isEmpty()) "" else sanitize(it) } ?: return null
        val safeName = sanitizeSegment(name) ?: return null
        return File(root, if (safeDir.isEmpty()) safeName else "$safeDir/$safeName")
    }

    /** Delete a file, or a directory recursively (all contents). */
    fun delete(name: String): Boolean {
        val safe = sanitize(name) ?: return false
        return try {
            File(root, safe).deleteRecursively()
        } catch (e: Exception) {
            Log.e(TAG, "删除失败: $safe", e)
            false
        }
    }

    /** Resolve a relative path to its [File] (may not exist), or null if invalid. */
    fun resolve(name: String): File? {
        val safe = sanitize(name) ?: return null
        return File(root, safe)
    }

    /**
     * Directory at [path] (`""` = root), or null if the path is invalid or is
     * not an existing directory.  Unlike [resolve] this accepts the root, which
     * is why callers that mean "the directory currently being browsed" must use
     * this instead of [resolve].
     */
    fun dirAt(path: String): File? = resolveDir(path)

    /** Resolve a directory path (`""` = root), or null if it is not a directory. */
    private fun resolveDir(path: String): File? {
        if (path.isBlank()) return root
        val safe = sanitize(path) ?: return null
        val f = File(root, safe)
        return if (f.isDirectory) f else null
    }

    /**
     * Validate a full relative path: non-empty, no leading/trailing `/`,
     * no empty segments, no `.` / `..`, no `\` or `:`, and every segment
     * at most 255 chars.  Used for reading / writing / deleting / resolving.
     */
    private fun sanitize(name: String): String? {
        val n = name.trim()
        if (n.isEmpty()) return null
        if (n.startsWith('/') || n.endsWith('/')) return null
        if (n.contains('\\') || n.contains(':')) return null
        val segments = n.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." || it.length > 255 }) return null
        return n
    }

    /** Validate a plain single-segment name for file / directory creation. */
    private fun sanitizeSegment(name: String): String? {
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