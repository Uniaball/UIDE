package com.uniaball.uide.build

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** A compiler or linker diagnostic extracted from the build output. */
data class BuildIssue(
    val file: String,
    val line: Int,
    val column: Int,
    val severity: Severity,
    val message: String,
) {
    enum class Severity { ERROR, WARNING, NOTE }
}

data class BuildArtifact(val name: String, val file: File)

data class BuildOutcome(
    val configureExitCode: Int?,
    val buildExitCode: Int?,
    val issues: List<BuildIssue>,
    val artifacts: List<BuildArtifact>,
) {
    val isSuccess: Boolean get() = configureExitCode == 0 && buildExitCode == 0
    val errors: List<BuildIssue> get() = issues.filter { it.severity == BuildIssue.Severity.ERROR }
}

/**
 * Drives `cmake configure` + `cmake --build` for a project directory.
 *
 * The build tree lives in the app's internal storage: SD card / FUSE mounts have
 * no POSIX permission bits, which ninja, the linker and exec all need.
 */
class CProjectBuilder(
    context: Context,
    private val nativeExec: NativeExec,
    private val runner: ProcessRunner,
) {

    private val appContext = context.applicationContext

    private val ptyRunner = PtyRunner(nativeExec)

    fun projectRoot(): File {
        val base = appContext.getExternalFilesDir(null) ?: appContext.filesDir
        return base.resolve("uide")
    }

    /** Source tree the user browses in the file list. */
    fun projectDir(relativePath: String): File = File(projectRoot(), relativePath)

    fun buildDirFor(sourceDir: File): File =
        File(nativeExec.buildRoot, sha1(sourceDir.absolutePath).take(16))

    fun isBuildable(sourceDir: File): Boolean = File(sourceDir, "CMakeLists.txt").isFile

    suspend fun build(sourceDir: File, onLine: suspend (OutputLine) -> Unit): BuildOutcome {
        val buildDir = buildDirFor(sourceDir)
        buildDir.mkdirs()
        val cmake = File(nativeExec.binDir, "cmake").absolutePath
        val toolchain = File(nativeExec.shareDir, "uide/uide-android.cmake").absolutePath
        val ninja = File(nativeExec.binDir, "ninja").absolutePath
        // CMake cannot derive its own root from /proc/self/exe behind the
        // linker64 exec path; the init cache pre-seeds CMAKE_ROOT.
        val initCache = nativeExec.ensureCmakeInitCache()
        val initCacheArg = if (initCache != null) "-C$initCache" else null

        onLine(OutputLine("=== cmake configure ===", false))
        val configure = runTool(
            cmake,
            listOfNotNull(
                "-S", sourceDir.absolutePath,
                "-B", buildDir.absolutePath,
                "-G", "Ninja",
                initCacheArg,
                "-DCMAKE_TOOLCHAIN_FILE=$toolchain",
                "-DCMAKE_BUILD_TYPE=Debug",
                "-DCMAKE_MAKE_PROGRAM=$ninja",
            ),
            sourceDir,
            onLine,
        )

        onLine(OutputLine("=== cmake --build ===", false))
        val compile = runTool(
            cmake,
            listOf(
                "--build", buildDir.absolutePath,
                "--parallel", Runtime.getRuntime().availableProcessors().coerceAtLeast(2).toString(),
            ),
            sourceDir,
            onLine,
        )

        return BuildOutcome(
            configureExitCode = configure.exit,
            buildExitCode = compile.exit,
            issues = CompilerErrorParser.parseAll(configure.output + compile.output),
            artifacts = findArtifacts(buildDir, sourceDir.name),
        )
    }

    /** Runs a freshly built executable on a PTY, so it can read stdin. */
    fun runArtifact(artifact: BuildArtifact): PtyProcess? =
        ptyRunner.launch(
            program = artifact.file.absolutePath,
            workingDir = artifact.file.parentFile,
            env = mapOf("LD_LIBRARY_PATH" to nativeExec.libDir.absolutePath),
        )

    /**
     * Deletes the build tree of [sourceDir] (and with it every artifact).
     * Returns the number of bytes that were on disk, or -1 when there was no
     * build directory.  Meant for the "清空" button, so it runs off the main
     * thread.
     */
    suspend fun clean(sourceDir: File): Long = withContext(Dispatchers.IO) {
        val buildDir = buildDirFor(sourceDir)
        if (!buildDir.isDirectory) {
            -1L
        } else {
            val bytes = directorySize(buildDir)
            buildDir.deleteRecursively()
            bytes
        }
    }

    private fun directorySize(dir: File): Long {
        var total = 0L
        dir.walkTopDown().filter { it.isFile }.forEach { total += it.length() }
        return total
    }

    private data class ToolResult(val exit: Int?, val output: String)

    private suspend fun runTool(
        program: String,
        args: List<String>,
        workingDir: File,
        onLine: suspend (OutputLine) -> Unit,
    ): ToolResult {
        val handle = runner.launch(program = program, args = args, workingDir = workingDir)
        val collected = StringBuilder()
        val collector = CoroutineScope(Dispatchers.IO)
        val collect = collector.launch {
            handle.lines.collect {
                collected.appendLine(it.text)
                onLine(it)
            }
        }
        val exit = handle.awaitOrTimeout(BUILD_TIMEOUT_MS)
        collect.cancel()
        if (exit == null) handle.cancel()
        return ToolResult(exit, collected.toString())
    }

    /** Picks executables and shared libraries ninja produced in the build tree. */
    private fun findArtifacts(buildDir: File, projectName: String): List<BuildArtifact> {
        if (!buildDir.isDirectory) return emptyList()
        val ignored = setOf(
            "CMakeCache.txt", "build.ninja", ".ninja_deps", ".ninja_log",
            ".cmake", "CMakeFiles", "cmake_install.cmake", "CTestTestfile.cmake",
            "CMakeOutput.log", "CMakeConfigureLog.yaml", "rules.ninja",
        )
        val found = buildDir.listFiles().orEmpty()
            .filter { it.isFile && it.name !in ignored && !it.name.startsWith("cmake_install") }
            .filter { it.canExecute() || it.extension == "so" }
            .sortedByDescending { it.name == projectName }
        return found.map { BuildArtifact(it.name, it) }
    }

    private fun sha1(value: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
        return digest.digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val BUILD_TIMEOUT_MS = 10 * 60 * 1000L
    }
}