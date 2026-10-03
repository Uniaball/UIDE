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
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

data class OutputLine(val text: String, val isError: Boolean)

/** A running child process together with its live output. */
class ProcessHandle internal constructor(
    private val process: Process,
    val lines: Flow<OutputLine>,
    private val scope: CoroutineScope,
) {
    val isAlive: Boolean get() = process.isAlive

    suspend fun await(): Int = withContext(Dispatchers.IO) { process.waitFor() }

    suspend fun awaitOrTimeout(millis: Long): Int? =
        withContext(Dispatchers.IO) {
            if (process.waitFor(millis, TimeUnit.MILLISECONDS)) process.exitValue() else null
        }

    fun cancel() {
        process.destroyForcibly()
        scope.cancel()
    }
}

/**
 * Spawns child processes with stdout/stderr drained concurrently. Both pipes
 * must be read while the child is running, otherwise a full pipe buffer
 * deadlocks the build.
 */
class ProcessRunner(private val nativeExec: NativeExec) {

    fun launch(
        program: String,
        args: List<String> = emptyList(),
        workingDir: File? = null,
        env: Map<String, String> = emptyMap(),
        wrap: Boolean = true,
    ): ProcessHandle {
        val command = if (wrap) nativeExec.commandLine(program, args) else listOf(program) + args
        val builder = ProcessBuilder(command)
        builder.directory(workingDir)
        val environment = builder.environment()
        nativeExec.environmentFor(program).forEach { (key, value) -> environment[key] = value }
        env.forEach { (key, value) -> environment[key] = value }

        val process = builder.start()
        runCatching { process.outputStream.close() }

        val channel = Channel<OutputLine>(Channel.UNLIMITED)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val out = scope.launch { pump(process.inputStream, false, channel) }
        val err = scope.launch { pump(process.errorStream, true, channel) }
        scope.launch {
            process.waitFor()
            out.join()
            err.join()
            channel.close()
        }
        return ProcessHandle(process, channel.receiveAsFlow(), scope)
    }

    private suspend fun pump(
        source: InputStream,
        isError: Boolean,
        channel: Channel<OutputLine>,
    ) {
        BufferedReader(InputStreamReader(source, Charsets.UTF_8)).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                channel.send(OutputLine(line, isError))
            }
        }
    }
}
