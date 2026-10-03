package com.uniaball.uide.build

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream

/** Progress of the one-time toolchain extraction. */
data class ToolchainState(
    val status: Status,
    val extractedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val installedBytes: Long = 0L,
    val message: String = "",
) {
    enum class Status { NOT_INSTALLED, INSTALLING, READY, FAILED, UNSUPPORTED }

    val progress: Float
        get() = if (totalBytes > 0) (extractedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
}

/**
 * Extracts the bundled toolchain (`assets/bootstrap-<abi>.tar.gz`) into the app's
 * data directory.
 *
 * The toolchain cannot live on external storage: FAT32/exFAT has no POSIX
 * permission bits and the app may not execute code from there, so it is unpacked
 * into [NativeExec.prefixDir] which is also where the linker64 exec path expects
 * it.
 */
class ToolchainManager(
    context: Context,
    private val nativeExec: NativeExec,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    private val appContext = context.applicationContext

    private val _state = MutableStateFlow(readInstalledState())

    val state: StateFlow<ToolchainState> = _state.asStateFlow()

    val isReady: Boolean get() = _state.value.status == ToolchainState.Status.READY

    val markerFile: File get() = File(nativeExec.prefixDir, MARKER_NAME)

    /** Result of the last install, written next to the self-test report. */
    val installReportFile: File get() = File(nativeExec.externalDir, REPORT_NAME)

    /**
     * Cheap: a few stats plus the marker file.  Must stay fast because it runs
     * from the constructor while a screen is being composed - walking the whole
     * prefix tree here blocked the navigation transition for hundreds of ms.
     */
    private fun readInstalledState(): ToolchainState {
        if (!nativeExec.isSupportedAbi) return ToolchainState(ToolchainState.Status.UNSUPPORTED)
        val compiler = File(nativeExec.binDir, "clang")
        val marker = markerFile
        if (!marker.isFile || !compiler.isFile || !compiler.canExecute()) {
            return ToolchainState(ToolchainState.Status.NOT_INSTALLED)
        }
        return ToolchainState(
            status = ToolchainState.Status.READY,
            totalBytes = marker.length(),
            installedBytes = marker.readInstalledBytes(),
        )
    }

    /**
     * Re-reads the state and, when the marker predates the cached size (older
     * installs), measures the tree.  Walks ~30k files, so keep it off the main
     * thread.
     */
    suspend fun refreshMeasured(): ToolchainState = withContext(io) {
        val measured = readInstalledState()
        val state = if (measured.status == ToolchainState.Status.READY && measured.installedBytes <= 0L) {
            measured.copy(installedBytes = directorySize(nativeExec.prefixDir))
        } else {
            measured
        }
        _state.value = state
        state
    }

    /** `installed=<bytes>` line written into the marker at install time. */
    private fun File.readInstalledBytes(): Long = runCatching {
        useLines { lines ->
            lines.firstOrNull { it.startsWith(INSTALLED_KEY) }
                ?.removePrefix(INSTALLED_KEY)
                ?.trim()
                ?.toLongOrNull()
        }
    }.getOrNull() ?: 0L

    /**
     * Re-checks whether the toolchain is present. Cheap enough to call whenever
     * the toolchain screen becomes visible, since the user may have cleared the
     * app data behind our back.
     */
    fun refresh() {
        fixPermissions()
        _state.value = readInstalledState()
    }

    suspend fun install(): ToolchainState {
        val result = installInternal()
        writeReport(result)
        return result
    }

    private suspend fun installInternal(): ToolchainState = withContext(io) {
        if (!nativeExec.isSupportedAbi) {
            return@withContext ToolchainState(
                ToolchainState.Status.UNSUPPORTED,
                message = "仅提供 ${NativeExec.ABI_ARM64} 工具链，当前设备为 ${nativeExec.abis.joinToString()}",
            )
        }
        if (isReady) return@withContext _state.value

        _state.value = ToolchainState(ToolchainState.Status.INSTALLING, message = "正在解压工具链…")
        val staging = File(nativeExec.filesDir, "$PREFIX_DIR_NAME.installing")
        try {
            staging.deleteRecursively()
            check(staging.mkdirs()) { "cannot create ${staging.absolutePath}" }

            val total = assetLength()
            val extracted = unpack(staging, total)

            nativeExec.prefixDir.deleteRecursively()
            check(staging.renameTo(nativeExec.prefixDir)) { "cannot move toolchain into place" }

            val installed = directorySize(nativeExec.prefixDir)
            // The size is cached here so later starts do not have to walk the
            // whole prefix tree just to show it.
            File(nativeExec.prefixDir, MARKER_NAME).writeText(
                "uide-toolchain $BOOTSTRAP_VERSION\nentries=$extracted\n$INSTALLED_KEY$installed\n",
            )

            ToolchainState(
                status = ToolchainState.Status.READY,
                extractedBytes = extracted,
                totalBytes = total,
                installedBytes = installed,
            ).also { finished ->
                _state.value = finished
                Log.i(LOG_TAG, "toolchain installed: $installed bytes")
            }
        } catch (e: Exception) {
            staging.deleteRecursively()
            Log.e(LOG_TAG, "toolchain installation failed", e)
            ToolchainState(ToolchainState.Status.FAILED, message = e.message ?: e.toString())
                .also { _state.value = it }
        }
    }

    fun remove() {
        nativeExec.prefixDir.deleteRecursively()
        _state.value = ToolchainState(ToolchainState.Status.NOT_INSTALLED)
    }

    private fun writeReport(state: ToolchainState) {
        val text = buildString {
            append("UIDE toolchain install\n")
            append("status: ").append(state.status).append('\n')
            append("message: ").append(state.message).append('\n')
            append("unpacked: ").append(state.extractedBytes).append(" bytes\n")
            append("installed: ").append(state.installedBytes).append(" bytes\n")
            append("prefix: ").append(nativeExec.prefixDir).append('\n')
            append("clang: ").append(File(nativeExec.binDir, "clang").isFile).append('\n')
            append("cmake: ").append(File(nativeExec.binDir, "cmake").isFile).append('\n')
            append("ninja: ").append(File(nativeExec.binDir, "ninja").isFile).append('\n')
        }
        runCatching { installReportFile.writeText(text) }
    }

    /** Re-applies the archive modes; needed after a restore or a system umask change. */
    fun fixPermissions() {
        if (!nativeExec.prefixDir.isDirectory) return
        nativeExec.prefixDir.walkTopDown().filter { it.isFile }.forEach { file ->
            val relative = file.relativeTo(nativeExec.prefixDir).invariantSeparatorsPath
            if (relative.startsWith("bin/") || relative.startsWith("libexec/") ||
                relative == "uide-probe.sh"
            ) {
                file.setExecutable(true, true)
            }
            file.setReadable(true, true)
        }
    }

    fun toolchainFile(name: String): File = File(nativeExec.binDir, name)

    private fun assetName(): String = "bootstrap-$ARCH_TAG.bin"

    private fun assetLength(): Long =
        runCatching { appContext.assets.openFd(assetName()).length }.getOrDefault(0L)

    /**
     * Minimal streaming tar.gz extractor. Only regular files, directories and
     * symlinks appear in the bootstrap; anything else is skipped.
     */
    private fun unpack(target: File, totalBytes: Long): Long {
        var written = 0L
        var entries = 0L
        GZIPInputStream(appContext.assets.open(assetName())).use { gzip ->
            val buffer = ByteArray(COPY_BUFFER)
            var header = gzip.readTarHeader()
            while (header != null) {
                // The archive's top level is the Termux prefix layout ("usr/..."),
                // and the prefix directory itself is NativeExec.prefixDir, so the
                // leading "usr/" component is dropped while unpacking.
                val path = header.path.removePrefix(ARCHIVE_ROOT)
                if (path.isNotEmpty()) {
                    val destination = File(target, path)
                    when {
                        header.type == TarType.DIRECTORY -> destination.mkdirs()
                        header.type == TarType.SYMLINK -> {
                            destination.parentFile?.mkdirs()
                            destination.delete()
                            val linked = runCatching {
                                android.system.Os.symlink(
                                    header.linkName, destination.absolutePath,
                                )
                            }.isSuccess
                            if (!linked) {
                                Log.w(LOG_TAG, "symlink refused: $path -> ${header.linkName}")
                            }
                        }
                        header.type == TarType.FILE -> {
                            destination.parentFile?.mkdirs()
                            destination.outputStream().use { output ->
                                written += gzip.copyExactly(output, header.size, buffer)
                            }
                            // Android apps run with umask 0077, so the mode from
                            // the archive is lost. Tools that check access(X_OK) on
                            // themselves - CMake looking for its Modules directory,
                            // for example - refuse to run otherwise.
                            val executable = path.startsWith("bin/") ||
                                path.startsWith("libexec/") ||
                                path == "uide-probe.sh"
                            if (executable) {
                                destination.setExecutable(true, true)
                            }
                            destination.setReadable(true, true)
                        }
                    }
                    entries++
                }
                written += gzip.skipPayload(header, buffer)
                header = gzip.readTarHeader()
                _state.value = _state.value.copy(
                    extractedBytes = written,
                    totalBytes = if (totalBytes > 0) totalBytes else _state.value.totalBytes,
                )
            }
        }
        Log.i(LOG_TAG, "unpacked $entries entries, $written bytes")
        return written
    }

    private fun directorySize(dir: File): Long {
        if (!dir.isDirectory) return 0L
        var total = 0L
        dir.walkTopDown().filter { it.isFile }.forEach { total += it.length() }
        return total
    }

    companion object {
        private const val LOG_TAG = "UIDE:Toolchain"
        private const val MARKER_NAME = ".uide-toolchain"
        private const val INSTALLED_KEY = "installed="
        private const val REPORT_NAME = "toolchain-report.txt"
        private const val COPY_BUFFER = 128 * 1024
        private const val ARCH_TAG = "aarch64"
        private const val ARCHIVE_ROOT = "usr/"
        private const val PREFIX_DIR_NAME = "usr"
        const val BOOTSTRAP_VERSION = "2026.10.03"
    }
}

private enum class TarType(val id: Char) {
    FILE('0'), DIRECTORY('5'), SYMLINK('2');

    companion object {
        fun of(id: Char): TarType? = entries.firstOrNull { it.id == id }
    }
}

private data class TarHeader(
    val path: String,
    val size: Long,
    val type: TarType?,
    val linkName: String,
)

/** Reads a 512 byte tar header block and normalises its fields. */
private fun GZIPInputStream.readTarHeader(): TarHeader? {
    val block = ByteArray(512)
    if (!readFully(block)) return null
    if (block[0] == NUL_BYTE) return null
    val name = headerString(block, 0, 100)
    val size = headerString(block, 124, 12).trim().trim(' ').toLongOrNull(8) ?: 0L
    val type = TarType.of(block[156].toInt().toChar())
    val prefix = headerString(block, 345, 155)
    val link = headerString(block, 157, 100)
    val path = when {
        prefix.isEmpty() -> name
        name.startsWith("./") -> prefix + name.substring(1)
        else -> "$prefix/$name"
    }.removePrefix("./")
    return TarHeader(path, size, type, link)
}

/**
 * tar fields are NUL terminated and space padded; Python's tarfile pads the
 * name fields with NUL, GNU tar with spaces.
 */
private fun headerString(block: ByteArray, offset: Int, length: Int): String {
    var end = offset
    val limit = offset + length
    while (end < limit && block[end] != NUL_BYTE) end++
    return String(block, offset, end - offset, Charsets.UTF_8).trimEnd(' ')
}

private val NUL_BYTE = 0.toByte()

private fun GZIPInputStream.readFully(buffer: ByteArray): Boolean {
    var offset = 0
    while (offset < buffer.size) {
        val read = read(buffer, offset, buffer.size - offset)
        if (read < 0) return false
        offset += read
    }
    return true
}

private fun GZIPInputStream.copyExactly(
    destination: OutputStream,
    size: Long,
    buffer: ByteArray,
): Long {
    var remaining = size
    var total = 0L
    while (remaining > 0) {
        val chunk = minOf(remaining, buffer.size.toLong()).toInt()
        val read = read(buffer, 0, chunk)
        if (read < 0) throw IOException("truncated tar payload")
        destination.write(buffer, 0, read)
        remaining -= read
        total += read
    }
    return total
}

/** Consumes the padding that follows an entry so the next header is aligned. */
private fun GZIPInputStream.skipPayload(header: TarHeader, buffer: ByteArray): Long {
    if (header.type != TarType.FILE && header.type != TarType.SYMLINK) return 0L
    val padding = (512 - header.size % 512) % 512
    var remaining = padding
    while (remaining > 0) {
        val read = read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
        if (read < 0) break
        remaining -= read
    }
    return padding
}