package com.uniaball.uide.build

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class CheckResult(val name: String, val ok: Boolean, val detail: String)

/**
 * Verifies on the real device that binaries living in the app data directory can
 * be launched through `/system/bin/linker64` and that the `uidexec` hook keeps
 * working for nested `execv` / `posix_spawn` calls and `#!` scripts.
 */
class EnvironmentChecker(
    context: Context,
    private val nativeExec: NativeExec,
    private val runner: ProcessRunner,
) {

    private val appContext = context.applicationContext

    /**
     * Report file inside the app's external directory, so that the result can be
     * pulled with `adb shell cat` on devices whose logcat is not readable.
     */
    val reportFile: File = File(nativeExec.externalDir, REPORT_NAME)

    suspend fun runAndReport(): List<CheckResult> = run().also { writeReport(it) }

    private fun writeReport(results: List<CheckResult>) {
        val text = buildString {
            append("UIDE environment self-test\n")
            append("generated: ").append(TIMESTAMP.format(Date())).append('\n')
            append("abi: ").append(nativeExec.abis.joinToString()).append('\n')
            append("sdk: ").append(nativeExec.sdkInt).append('\n')
            append("preload: ").append(nativeExec.preloadLibrary?.absolutePath ?: "(none)")
            append("\n\n")
            results.forEach {
                append(if (it.ok) "PASS  " else "FAIL  ").append(it.name)
                append("\n      ").append(it.detail).append('\n')
            }
        }
        runCatching { reportFile.writeText(text) }
    }

    suspend fun run(): List<CheckResult> {
        val results = mutableListOf<CheckResult>()

        results += CheckResult("设备 ABI", nativeExec.isSupportedAbi, nativeExec.describeAbiSupport())
        results += CheckResult(
            "系统版本",
            true,
            "SDK_INT=${nativeExec.sdkInt}，exec 策略：" +
                if (nativeExec.needsLinkerWrap()) "经 ${nativeExec.linkerPath} 包装"
                else "直接执行即可",
        )
        val preload = nativeExec.preloadLibrary
        results += CheckResult(
            "exec 钩子库",
            preload != null,
            preload?.absolutePath ?: "未找到 ${NativeExec.PRELOAD_NAME}（APK 未安装 arm64 原生库）",
        )
        if (preload == null || !nativeExec.isSupportedAbi) return results

        nativeExec.prepareDirectories()
        val selftest = extractSelfTest()
        if (selftest == null) {
            results += CheckResult("自检程序", false, "无法从 assets 释放 $SELFTEST_ASSET")
            return results
        }
        results += CheckResult("自检程序", true, selftest.absolutePath)

        val direct = runProcess(selftest.absolutePath, wrap = false)
        results += CheckResult(
            "对照组：直接执行数据目录程序",
            direct.launchError != null,
            direct.launchError ?: "未按预期被拒绝（exit=${direct.exitCode}），本机未强制 W^X 限制",
        )

        val wrapped = runProcess(
            selftest.absolutePath,
            wrap = true,
            env = mapOf(SELF_ENV to selftest.absolutePath),
        )
        val text = wrapped.output
        results += CheckResult("LD_PRELOAD 注入钩子", MARK_LOADED in text, extractDetail(text, "uidexec-preloaded=", wrapped))
        val selfExe = text.lineSequence()
            .firstOrNull { it.contains("selftest: proc-self-exe=") }
            ?.substringAfter("selftest: proc-self-exe=")
            ?.trim()
        results += CheckResult(
            "/proc/self/exe 修正",
            selfExe?.endsWith(SELFTEST_NAME) == true,
            selfExe ?: "自检未输出 proc-self-exe",
        )
        results += CheckResult(
            "执行系统程序",
            "exec-system-binary ok" in text,
            extractDetail(text, "exec-system-binary", wrapped),
        )
        results += CheckResult(
            "执行数据目录 ELF（execv）",
            "exec-data-dir-elf ok" in text,
            extractDetail(text, "exec-data-dir-elf", wrapped),
        )
        results += CheckResult(
            "执行数据目录 ELF（posix_spawn）",
            "spawn-data-dir-elf ok" in text,
            extractDetail(text, "spawn-data-dir-elf", wrapped),
        )
        results += CheckResult(
            "自检进程退出码",
            wrapped.exitCode == 0,
            "exit=${wrapped.exitCode ?: "超时"}",
        )

        val script = writeProbeScript()
        if (script == null) {
            results += CheckResult("脚本 shebang 解释器", false, "无法写入 $SCRIPT_NAME")
        } else {
            val scriptRun = runProcess(script.absolutePath, wrap = true)
            results += CheckResult(
                "脚本 shebang 解释器",
                SHEBANG_OK in scriptRun.output,
                extractDetail(scriptRun.output, "shebang", scriptRun),
            )
        }

        return results
    }

    private fun extractSelfTest(): File? {
        val target = File(nativeExec.selftestDir, SELFTEST_NAME)
        if (target.isFile && target.canExecute()) return target
        return try {
            appContext.assets.open(SELFTEST_ASSET).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            target.setReadable(true, true)
            target.setExecutable(true, true)
            target.takeIf { it.canExecute() }
        } catch (e: IOException) {
            null
        }
    }

    private fun writeProbeScript(): File? {
        val script = File(nativeExec.selftestDir, SCRIPT_NAME)
        return try {
            script.writeText("#!/system/bin/sh\necho $SHEBANG_OK\n")
            script.setReadable(true, true)
            script.setExecutable(true, true)
            script.takeIf { it.canExecute() }
        } catch (e: IOException) {
            null
        }
    }

    private suspend fun runProcess(
        program: String,
        wrap: Boolean,
        env: Map<String, String> = emptyMap(),
    ): RunResult {
        val started = try {
            runner.launch(
                program = program,
                workingDir = nativeExec.selftestDir,
                env = env,
                wrap = wrap,
            )
        } catch (e: IOException) {
            return RunResult(null, "", e.message ?: e.toString())
        } catch (e: SecurityException) {
            return RunResult(null, "", e.message ?: e.toString())
        }

        val collected = StringBuilder()
        val reader = CoroutineScope(Dispatchers.IO).launch {
            started.lines.collect { line ->
                synchronized(collected) { collected.append(line.text).append('\n') }
            }
        }
        val code = started.awaitOrTimeout(TIMEOUT_MS)
        reader.cancel()
        if (code == null) started.cancel()
        return RunResult(code, synchronized(collected) { collected.toString() })
    }

    private fun extractDetail(output: String, needle: String, result: RunResult): String {
        val line = output.lineSequence().firstOrNull { it.contains(needle) }
        val fallback = result.launchError ?: result.output.trim().ifEmpty { "输出为空" }
        return (line?.trim() ?: "输出中没有匹配 “$needle” 的行：$fallback")
            .take(MAX_DETAIL)
    }

    private data class RunResult(
        val exitCode: Int?,
        val output: String,
        val launchError: String? = null,
    )

    companion object {
        const val REPORT_NAME = "selftest-report.txt"
        private const val SELFTEST_ASSET = "uidexec-selftest-arm64"
        private const val SELFTEST_NAME = "uidexec-selftest"
        private const val SCRIPT_NAME = "probe.sh"
        private const val SELF_ENV = "UIDE_SELFTEST_SELF"
        private const val MARK_LOADED = "uidexec-preloaded=yes"
        private const val SHEBANG_OK = "shebang-ok"
        private const val TIMEOUT_MS = 60_000L
        private const val MAX_DETAIL = 400
        private val TIMESTAMP = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    }
}
