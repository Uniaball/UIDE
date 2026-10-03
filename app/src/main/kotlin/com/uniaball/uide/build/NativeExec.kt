package com.uniaball.uide.build

import android.content.Context
import android.os.Build
import java.io.File

/**
 * Single entry point for spawning native programs.
 *
 * Apps targeting API 29 or newer are not allowed to `execve()` anything inside
 * their own data directory (SELinux `app_data_file` lost `execute_no_trans`).
 * The escape hatch used by Termux and friends is to hand the real target to the
 * system dynamic linker: `/system/bin/linker64 /data/.../bin/foo`. The linker
 * re-execs the target through `execveat(..., AT_EMPTY_PATH)`, for which SELinux
 * has no path to check, so the exec is allowed. See `uidexec.c` for the details.
 */
class NativeExec(context: Context) {

    private val appContext = context.applicationContext

    val filesDir: File = appContext.filesDir
    val externalDir: File = appContext.getExternalFilesDir(null) ?: filesDir
    val prefixDir: File = File(filesDir, "usr")
    val binDir: File = File(prefixDir, "bin")
    val libDir: File = File(prefixDir, "lib")
    val shareDir: File = File(prefixDir, "share")
    val sysrootDir: File = File(prefixDir, "sysroot")
    val homeDir: File = File(filesDir, "home")
    val tmpDir: File = File(filesDir, "tmp")
    val buildRoot: File = File(filesDir, "build")
    val selftestDir: File = File(filesDir, "uide-selftest")

    val abis: List<String> = Build.SUPPORTED_ABIS.toList()

    val isSupportedAbi: Boolean = abis.contains(ABI_ARM64)

    val sdkInt: Int = Build.VERSION.SDK_INT

    val linkerPath: String = if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()) LINKER64 else LINKER32

    val preloadLibrary: File? = File(appContext.applicationInfo.nativeLibraryDir, PRELOAD_NAME)
        .takeIf { it.isFile }

    val isSelfTestAvailable: Boolean = preloadLibrary != null && isSupportedAbi

    fun needsLinkerWrap(): Boolean = sdkInt >= Build.VERSION_CODES.Q

    /**
     * Builds the argument vector for [program].
     *
     * ELF executables inside the app data directory have to be handed to the
     * system linker. `#!` scripts must *not* be: the linker rejects them before
     * the `uidexec` hook is ever loaded, so the interpreter is resolved here
     * and the script path is passed to it instead.
     */
    fun commandLine(program: String, args: List<String> = emptyList()): List<String> {
        val shebang = readShebang(program)
        if (shebang != null) {
            val interpreter = resolveProgram(shebang.first)
            if (interpreter != null) {
                val tail = shebang.second + program + args
                return if (needsLinkerWrap() && !isSystemPath(interpreter)) {
                    listOf(linkerPath, interpreter) + tail
                } else {
                    listOf(interpreter) + tail
                }
            }
        }
        return if (needsLinkerWrap()) listOf(linkerPath, program) + args else listOf(program) + args
    }

    private fun readShebang(program: String): Pair<String, List<String>>? = runCatching {
        val file = File(program)
        if (!file.isFile) return null
        file.inputStream().use { input ->
            val head = ByteArray(2)
            if (input.read(head) != 2 || head[0] != '#'.code.toByte() || head[1] != '!'.code.toByte()) {
                return null
            }
            val line = buildString {
                while (true) {
                    val byte = input.read()
                    if (byte <= 0 || byte == '\n'.code) break
                    append(byte.toChar())
                }
            }.trim()
            val words = line.split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (words.isEmpty()) null else words.first() to words.drop(1)
        }
    }.getOrNull()

    private fun resolveProgram(name: String): String? {
        if (name.contains('/')) return name.takeIf { File(it).canExecute() }
        val searchPath = "$binDir:$SYSTEM_BIN"
        for (dir in searchPath.split(':')) {
            if (dir.isEmpty()) continue
            val candidate = File(dir, name)
            if (candidate.canExecute()) return candidate.absolutePath
        }
        return null
    }

    private fun isSystemPath(path: String): Boolean =
        SYSTEM_PREFIXES.any { path.startsWith(it) }

    fun baseEnvironment(): MutableMap<String, String> {
        val env = mutableMapOf<String, String>()
        env["PATH"] = "$binDir:$SYSTEM_BIN"
        env["PREFIX"] = prefixDir.absolutePath
        env["TERMUX_PREFIX"] = prefixDir.absolutePath
        env["HOME"] = homeDir.absolutePath
        env["TMPDIR"] = tmpDir.absolutePath
        env["LD_LIBRARY_PATH"] = libDir.absolutePath
        env["TERM"] = "dumb"
        env["LANG"] = "C.UTF-8"
        preloadLibrary?.let { env["LD_PRELOAD"] = it.absolutePath }
        return env
    }

    /**
     * Environment for a child that should believe it was started normally.
     *
     * `/proc/self/exe` points at the system linker for programs launched through
     * [linkerPath], which breaks tools that locate their own installation
     * (CMake looks for its Modules directory that way, with no override).
     * [SELF_EXE_ENV] carries the real path and `uidexec.c` reports it back from
     * its `readlink` hook.
     */
    fun environmentFor(program: String): MutableMap<String, String> =
        baseEnvironment().apply { this[SELF_EXE_ENV] = program }

    fun prepareDirectories() {
        for (dir in listOf(prefixDir, homeDir, tmpDir, buildRoot, selftestDir)) {
            if (!dir.exists()) dir.mkdirs()
        }
    }

    /**
     * Root of the bundled CMake installation (the directory holding `Modules`).
     *
     * CMake normally derives this from `/proc/self/exe`, but that path points at
     * the linker when a binary is started through the linker64 exec path, so it
     * has to be passed explicitly with `-DCMAKE_ROOT=`.
     */
    fun cmakeRoot(): File? = shareDir.listFiles()
        ?.filter { it.isDirectory && it.name.startsWith("cmake-") }
        ?.firstOrNull { File(it, "Modules").isDirectory }

    /**
     * Writes (once) the CMake initial-cache script that pins `CMAKE_ROOT`.
     *
     * CMake locates its own `Modules` directory relative to `/proc/self/exe`,
     * which points at the system linker when a process is started through the
     * linker64 exec path. Pre-loading `CMAKE_ROOT` through `cmake -C` is
     * CMake's supported escape hatch for exactly that case.
     */
    fun ensureCmakeInitCache(): File? {
        val root = cmakeRoot() ?: return null
        val file = File(filesDir, CMAKE_INIT_CACHE)
        if (file.isFile) {
            if (file.readText().contains(root.absolutePath)) return file
        }
        return runCatching {
            file.writeText("set(CMAKE_ROOT \"${root.absolutePath}\" CACHE PATH \"\" FORCE)\n")
            file
        }.getOrNull()
    }

    fun describeAbiSupport(): String =
        if (isSupportedAbi) "$ABI_ARM64 已包含（${abis.joinToString()}）"
        else "仅支持 $ABI_ARM64，当前设备为 ${abis.joinToString()}"

    companion object {
        const val ABI_ARM64 = "arm64-v8a"
        const val PRELOAD_NAME = "libuidexec.so"
        const val CMAKE_INIT_CACHE = "uide-cmake-init.cmake"
        const val SELF_EXE_ENV = "UIDE_EXEC_SELF_EXE"
        const val LINKER64 = "/system/bin/linker64"
        const val LINKER32 = "/system/bin/linker"
        private const val SYSTEM_BIN = "/system/bin"
        private val SYSTEM_PREFIXES = listOf(
            "/system/",
            "/apex/",
            "/vendor/",
            "/product/",
            "/system_ext/",
            "/odm/",
            "/data/local/tmp/",
        )
    }
}
