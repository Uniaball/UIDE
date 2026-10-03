package com.uniaball.uide.build

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * A program running on a pseudo-terminal.
 *
 * Compared to piping stdout this gives the program a real tty: `isatty()` is
 * true, `std::cin` reads what the user types here, stdout is line buffered and
 * colours/prompts work.
 *
 * Output arrives as [lines]; [write] sends keystrokes (including the control
 * characters behind [interrupt] and [endOfInput]); [resize] updates the
 * terminal geometry.
 */
class PtyProcess internal constructor(
    private val master: Int,
    private val pid: Int,
    val lines: Flow<OutputLine>,
    private val scope: CoroutineScope,
) {
    /** True while the child is still running. */
    val isAlive: Boolean get() = NativePty.isAlive(pid)

    /** Sends [text] to the program's stdin, unbuffered. */
    fun write(text: String) {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        if (bytes.isEmpty()) return
        NativePty.write(master, bytes)
    }

    fun writeLine(text: String) = write("$text\n")

    /** Ctrl+C: the tty turns this into SIGINT for the foreground process. */
    fun interrupt() = write("\u0003")

    /** Ctrl+D: end of input for programs that read until EOF. */
    fun endOfInput() = write("\u0004")

    fun resize(rows: Int, cols: Int) = NativePty.resize(master, rows, cols)

    suspend fun await(): Int? = withContext(Dispatchers.IO) { NativePty.waitForPid(pid) }

    fun cancel() {
        NativePty.killPid(pid, 9)
        NativePty.closeFd(master)
        scope.cancel()
    }
}

/** Launches [program] on a PTY, e.g. a freshly built executable. */
class PtyRunner(private val nativeExec: NativeExec) {

    fun launch(
        program: String,
        args: List<String> = emptyList(),
        workingDir: File? = null,
        env: Map<String, String> = emptyMap(),
        rows: Int = DEFAULT_ROWS,
        cols: Int = DEFAULT_COLS,
    ): PtyProcess? {
        NativePty.ensureLoaded()
        workingDir?.mkdirs()

        val master = NativePty.open()
        if (master < 0) return null

        val argv = (listOf(program) + args).toTypedArray()
        val envp = nativeExec.environmentFor(program)
            .apply { putAll(env) }
            .map { (key, value) -> "$key=$value" }
            .toTypedArray()

        val pid = NativePty.spawn(master, program, argv, envp, rows, cols)
        if (pid < 0) {
            NativePty.closeFd(master)
            return null
        }

        val channel = Channel<OutputLine>(Channel.UNLIMITED)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch { pump(master, channel) }
        return PtyProcess(master, pid, channel.receiveAsFlow(), scope)
    }

    /** Splits the PTY byte stream into lines; the tty echoes input as well. */
    private suspend fun pump(master: Int, channel: Channel<OutputLine>) {
        val buffer = ByteArray(4096)
        val pending = StringBuilder()
        while (true) {
            val read = NativePty.read(master, buffer)
            if (read <= 0) break
            pending.append(String(buffer, 0, read, StandardCharsets.UTF_8))
            while (true) {
                val index = pending.indexOf("\n")
                if (index < 0) break
                val line = pending.substring(0, index).removeSuffix("\r")
                pending.delete(0, index + 1)
                channel.send(OutputLine(line, false))
            }
        }
        val rest = pending.toString().removeSuffix("\r")
        if (rest.isNotEmpty()) channel.send(OutputLine(rest, false))
        channel.close()
    }

    companion object {
        const val DEFAULT_ROWS = 24
        const val DEFAULT_COLS = 80
    }
}
