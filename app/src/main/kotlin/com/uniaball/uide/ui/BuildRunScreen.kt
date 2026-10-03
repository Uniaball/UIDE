package com.uniaball.uide.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uniaball.uide.build.BuildArtifact
import com.uniaball.uide.build.BuildIssue
import com.uniaball.uide.build.BuildOutcome
import com.uniaball.uide.build.CProjectBuilder
import com.uniaball.uide.build.CompilerErrorParser
import com.uniaball.uide.build.NativeExec
import com.uniaball.uide.build.OutputLine
import com.uniaball.uide.build.PtyProcess
import com.uniaball.uide.build.ProcessRunner
import com.uniaball.uide.build.ToolchainManager
import com.uniaball.uide.build.ToolchainState
import com.uniaball.uide.ui.theme.EditorFontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.File

private data class LogLine(
    val id: Long,
    val text: String,
    val isError: Boolean,
    val issue: BuildIssue?,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BuildRunScreen(
    projectPath: String,
    onBack: () -> Unit,
    onOpenFile: (String) -> Unit,
) {
    val context = LocalContext.current
    val nativeExec = remember { NativeExec(context) }
    val toolchains = remember { ToolchainManager(context, nativeExec) }
    val runner = remember { ProcessRunner(nativeExec) }
    val builder = remember { CProjectBuilder(context, nativeExec, runner) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val hScroll = rememberScrollState()

    val projectDir = remember(projectPath) { builder.projectDir(projectPath) }
    val workspaceRoot = remember { builder.projectRoot() }
    val toolchainState by toolchains.state.collectAsStateWithLifecycle()

    var lines by remember { mutableStateOf<List<LogLine>>(emptyList()) }
    var outcome by remember { mutableStateOf<BuildOutcome?>(null) }
    var building by remember { mutableStateOf(false) }
    var buildJob by remember { mutableStateOf<Job?>(null) }
    var runJob by remember { mutableStateOf<Job?>(null) }
    var pty by remember { mutableStateOf<PtyProcess?>(null) }
    var stdin by remember { mutableStateOf("") }
    var runMenuOpen by remember { mutableStateOf(false) }
    // Measured height of the floating composer; the log reserves exactly that
    // much space so the newest line can always be scrolled clear of it.
    var pillHeight by remember { mutableStateOf(0) }
    var chipsHeight by remember { mutableStateOf(0) }
    var nextId by remember { mutableLongStateOf(0L) }

    // Terminal behaviour: keep following the newest line while output arrives.
    // A plain animateScrollToItem() would pin the last line to the *top* of the
    // list, leaving the reserved space at the bottom empty; the oversized offset
    // lets the list clamp at its real end, so the newest line ends up right on
    // top of the composer pill.
    LaunchedEffect(building, pty, lines.size) {
        if ((building || pty != null) && lines.isNotEmpty()) {
            listState.animateScrollToItem(lines.lastIndex, Int.MAX_VALUE)
        }
    }

    val ready = toolchainState.status == ToolchainState.Status.READY
    val buildable = builder.isBuildable(projectDir)

    fun append(line: OutputLine, issue: BuildIssue? = null) {
        val entry = LogLine(nextId++, line.text, line.isError, issue)
        lines = (lines + entry).takeLast(MAX_LINES)
    }

    /** Makes the compiler's own diagnostic lines tappable. */
    fun attachIssues(issues: List<BuildIssue>) {
        if (issues.isEmpty()) return
        lines = lines.map { line ->
            if (line.issue != null) {
                line
            } else {
                val match = issues.firstOrNull { line.text.contains("${it.file}:${it.line}:") }
                if (match != null) line.copy(issue = match) else line
            }
        }
    }

    fun startBuild() {
        if (buildJob?.isActive == true) return
        buildJob = scope.launch {
            building = true
            pty?.cancel()
            outcome = null
            lines = listOf(LogLine(nextId++, "=== 开始构建 ===", false, null))
            val result = builder.build(projectDir) { append(it) }
            outcome = result
            attachIssues(result.issues)
            building = false
            val summary = if (result.isSuccess) {
                "=== 构建成功，产物 ${result.artifacts.size} 个 ==="
            } else {
                "=== 构建失败，${result.errors.size} 个错误 ==="
            }
            append(OutputLine(summary, !result.isSuccess))
        }
    }

    fun startRun(artifact: BuildArtifact) {
        if (runJob?.isActive == true) return
        runJob = scope.launch {
            pty?.cancel()
            append(OutputLine("=== 运行 ${artifact.name}（交互终端） ===", false))
            val process = builder.runArtifact(artifact)
            if (process == null) {
                append(OutputLine("=== 启动失败：无法创建伪终端 ===", true))
                return@launch
            }
            pty = process
            val collector = kotlinx.coroutines.CoroutineScope(Dispatchers.IO)
            val collectJob = collector.launch { process.lines.collect { append(it) } }
            val exit = process.await()
            collectJob.cancel()
            pty = null
            stdin = ""
            append(OutputLine("=== 退出码 $exit ===", exit != 0))
        }
    }

    /** Clears the log *and* the build tree, so the next build starts from zero. */
    fun clearAll() {
        pty?.cancel()
        lines = emptyList()
        outcome = null
        scope.launch {
            val bytes = builder.clean(projectDir)
            val message = when {
                bytes < 0L -> "已清空日志（没有构建目录）"
                bytes == 0L -> "已清空日志和构建目录"
                else -> "已清空日志和构建目录（${formatSize(bytes)}）"
            }
            snackbar.showSnackbar(message)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(projectPath.ifEmpty { "构建" }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                // No imePadding: the window already resizes for the keyboard, so
                // the input row as the last child sits right above it.  Adding
                // the inset here as well left a gap the height of the keyboard.
                .padding(padding),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = ::startBuild,
                    enabled = ready && buildable && !building,
                ) {
                    Text(if (building) "构建中" else "构建")
                }
                val artifacts = outcome?.artifacts.orEmpty()
                Box {
                    OutlinedButton(
                        onClick = {
                            // One artifact: run it.  Several: let the user pick.
                            if (artifacts.size == 1) startRun(artifacts.first()) else runMenuOpen = true
                        },
                        enabled = artifacts.isNotEmpty() && runJob?.isActive != true,
                    ) {
                        Text(if (artifacts.size > 1) "运行 ▾" else "运行")
                    }
                    DropdownMenu(
                        expanded = runMenuOpen,
                        onDismissRequest = { runMenuOpen = false },
                    ) {
                        artifacts.forEach { artifact ->
                            DropdownMenuItem(
                                text = {
                                    Text("${artifact.name} · ${formatSize(artifact.file.length())}")
                                },
                                onClick = {
                                    runMenuOpen = false
                                    startRun(artifact)
                                },
                            )
                        }
                    }
                }
                if (building) {
                    OutlinedButton(
                        onClick = {
                            buildJob?.cancel()
                            runJob?.cancel()
                            pty?.cancel()
                            building = false
                        },
                    ) { Text("停止") }
                }
                OutlinedButton(onClick = ::clearAll, enabled = !building) { Text("清空") }
            }

            HorizontalDivider()

            Box(modifier = Modifier.weight(1f)) {
                when {
                    !ready -> CenteredMessage("工具链未安装，请先在“构建环境”页安装")
                    !buildable -> CenteredMessage("该目录没有 CMakeLists.txt，无法构建")
                    else -> LazyColumn(
state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 14.dp,
                            end = 14.dp,
                            top = 10.dp,
                            bottom = if (pty != null) {
                                (pillHeight + chipsHeight).dp + CHIPS_GAP + COMPOSER_MARGIN
                            } else {
                                10.dp
                            },
                        ),
                    ) {
                        items(lines, key = { it.id }) { line ->
                            // Vertical scrolling is the list's job; the shared
                            // scroll state only pans long lines sideways, which
                            // is why it lives on the row instead of on the list.
                            Box(modifier = Modifier.horizontalScroll(hScroll)) {
                                LogRow(line) { issue ->
                                    // The compiler reports absolute paths; the
                                    // editor is addressed relative to the
                                    // workspace root.
                                    val relative =
                                        CompilerErrorParser.resolveSource(issue, workspaceRoot)
                                    if (relative != null) {
                                        onOpenFile(relative)
                                    } else {
                                        // Linker diagnostics point at object files.
                                        scope.launch {
                                            snackbar.showSnackbar("该诊断没有对应的源文件位置")
                                        }
                                    }
}
                            }
                        }
                    }
                }

                // The composer floats over the output, the way the reference
                // project's translucent pill sits on top of the transcript.
                // The window is adjustNothing, so imePadding() moves only this
                // pill up when the keyboard opens - the log keeps its size.
                pty?.let { process ->
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .imePadding()
                            .padding(horizontal = 24.dp, vertical = 12.dp),
                        horizontalAlignment = Alignment.End,
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.onSizeChanged { chipsHeight = it.height },
                        ) {
                            TermChip("ESC") { process.write("") }
                            TermChip("^C") { process.interrupt() }
                            TermChip("^D") { process.endOfInput() }
                        }
                        Spacer(Modifier.height(CHIPS_GAP))
                        Surface(
                            shape = RoundedCornerShape(28.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.84f),
                            // Only the pill itself decides how much room the log
                            // keeps, so the last line ends up right at its edge.
                            modifier = Modifier.onSizeChanged { pillHeight = it.height },
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 56.dp)
                                    .padding(start = 20.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                BasicTextField(
                                    value = stdin,
                                    onValueChange = { stdin = it },
                                    singleLine = true,
                                    textStyle = TextStyle(
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontSize = 15.sp,
                                    ),
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp),
                                    decorationBox = { field ->
                                        if (stdin.isEmpty()) {
                                            Text(
                                                text = "给程序输入…",
                                                style = TextStyle(
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    fontSize = 15.sp,
                                                ),
                                            )
                                        }
                                        field()
                                    },
                                )
                                Spacer(Modifier.width(8.dp))
                                val canSend = stdin.isNotEmpty()
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (canSend) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.surfaceContainerHighest
                                        )
                                        .clickable(enabled = canSend) {
                                            process.writeLine(stdin)
                                            stdin = ""
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = "➤",
                                        fontSize = 16.sp,
                                        color = if (canSend) MaterialTheme.colorScheme.onPrimary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                }
                            }
                        }
                    }
            }
            if (building) {
                HorizontalDivider()
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.width(12.dp))
                    Text("构建进行中…")
                }
            }
        }
    }
}

@Composable
private fun CenteredMessage(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(24.dp),
        )
    }
}

/**
 * One output line.  The look follows the reference project's transcript style:
 * plain monospace text for output, small muted letter-spaced labels for the
 * `=== … ===` section markers, errors in the error role and CMake's secondary
 * output dimmed.  No frame around it - the page itself stays plain MD3.
 */
@Composable
private fun LogRow(line: LogLine, onIssueClick: (BuildIssue) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val text = line.text
    val isHeader = text.startsWith("===") && text.endsWith("===")
    val color = when {
        line.issue?.severity == BuildIssue.Severity.ERROR -> scheme.error
        line.isError || text.contains("error", ignoreCase = true) -> scheme.error
        text.contains("warning", ignoreCase = true) -> scheme.tertiary
        isHeader -> scheme.onSurfaceVariant
        text.startsWith("--") || text.startsWith("[") -> scheme.onSurfaceVariant
        else -> scheme.onSurface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (line.issue != null) Modifier.clickable { onIssueClick(line.issue) } else Modifier)
            .padding(horizontal = 2.dp, vertical = 1.dp),
    ) {
        Text(
            text = text.ifEmpty { " " },
            fontFamily = EditorFontFamily,
            fontSize = if (isHeader) 11.sp else 12.sp,
            lineHeight = if (isHeader) 16.sp else 17.sp,
            fontWeight = if (isHeader) FontWeight.Medium else FontWeight.Normal,
            letterSpacing = if (isHeader) 0.8.sp else 0.sp,
            softWrap = false,
            color = color,
        )
    }
}

/** Small pill that sends one control sequence, like the reference's chips. */
@Composable
private fun TermChip(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

private const val MAX_LINES = 5000

/** Gap between the chip row and the pill, matching the composer's own spacing. */
private val CHIPS_GAP = 6.dp

/** Bottom margin of the floating composer. */
private val COMPOSER_MARGIN = 12.dp

/** Same formatting as the file list uses. */
private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024))
}
