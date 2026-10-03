package com.uniaball.uide.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import com.uniaball.uide.build.TemplateProject
import com.uniaball.uide.data.FileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Rows stay untappable for this long, so a tap can never hit a moving row. */
private const val DIR_SLIDE_MS = 300

@Composable
fun FileListScreen(
    repository: FileRepository,
    savedStateHandle: SavedStateHandle,
    onOpenFile: (String) -> Unit,
    onOpenToolchain: () -> Unit,
    onBuild: (String) -> Unit,
) {
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // Current browsed directory (relative path, "" = root).
    var path by rememberSaveable { mutableStateOf("") }
    // Navigation direction for the enter animation: true = deeper, false = back.
    var navForward by remember { mutableStateOf(true) }
    // Bumped to force a re-read of the current directory (after create/delete/save).
    var filesVersion by remember { mutableStateOf(0) }
    // The editor bumps this counter after a save so the list re-reads on return
    // (the screen is kept alive on the NavHost back stack, so its `remember`
    // value would otherwise stay stale).
    val refreshSignal by savedStateHandle.getStateFlow("uide_refresh", 0).collectAsState()
    var showNewDialog by remember { mutableStateOf(false) }
    var showTemplateDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("untitled.c") }
    var newIsDir by remember { mutableStateOf(false) }
    var newError by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<File?>(null) }

    fun refresh() {
        filesVersion++
    }

    fun goInto(entryName: String) {
        val next = if (path.isEmpty()) entryName else "$path/$entryName"
        // A tap queued before the list re-read still fires on a row that has
        // moved: ignore targets that are not a directory instead of browsing
        // into a path that does not exist.
        if (repository.dirAt(next) == null) return
        navForward = true
        path = next
        refresh()
    }

    fun goUp() {
        navForward = false
        path = path.substringBeforeLast('/', "")
        refresh()
    }

    LaunchedEffect(refreshSignal) {
        refresh()
    }

    // System back (gesture / button) exits folders first; only at the root
    // does it fall through to the activity's default back behavior.
    BackHandler(enabled = path.isNotEmpty()) {
        goUp()
    }

    val dirName = path.substringAfterLast('/')

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(if (path.isEmpty()) "UIDE" else dirName)
                        if (path.isNotEmpty()) {
                            Text(
                                text = path,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    if (path.isNotEmpty()) {
                        IconButton(onClick = { goUp() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回上级")
                        }
                    }
                },
                actions = {
                    if (repository.dirAt(path)?.resolve("CMakeLists.txt")?.isFile == true) {
                        IconButton(onClick = { onBuild(path) }) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = "构建")
                        }
                    }
                    IconButton(onClick = onOpenToolchain) {
                        Icon(Icons.Filled.Terminal, contentDescription = "构建环境")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                SmallFloatingActionButton(onClick = { showTemplateDialog = true }) {
                    Icon(Icons.Filled.Build, contentDescription = "新建示例项目")
                }
                Spacer(Modifier.height(12.dp))
                FloatingActionButton(
                    onClick = {
                        newName = if (newIsDir) "new_folder" else "untitled.c"
                        newError = null
                        showNewDialog = true
                    },
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "新建")
                }
            }
        },
    ) { padding ->
        key(path) {
            // Exactly one directory listing is composed at a time and its rows
            // stay inert until the slide finished.  AnimatedContent kept the
            // outgoing listing composed and clickable next to the incoming one,
            // so a fast tap landed on a moving row - often on its delete button.
            var moving by remember { mutableStateOf(true) }
            val slide = remember { Animatable(1f) }
            val direction = if (navForward) 1f else -1f
            LaunchedEffect(path) {
                slide.snapTo(1f)
                moving = true
                slide.animateTo(0f, tween(durationMillis = DIR_SLIDE_MS))
                moving = false
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationX = direction * slide.value * size.width },
            ) {
                // Read on IO: SD card listings can block, and a failed read must
                // not be reported as "this folder is empty".  null = loading.
                val entries by produceState<List<File>?>(
                    initialValue = null,
                    path,
                    filesVersion,
                ) {
                    value = withContext(Dispatchers.IO) {
                        runCatching { repository.listEntries(path) }.getOrDefault(emptyList())
                    }
                }

                val loaded = entries
                when {
                    loaded == null -> CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                    )
                    loaded.isEmpty() -> Text(
                        text = "暂无文件\n点击右下角 ＋ 新建文件，或扳手按钮新建示例项目",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp),
                    )
                    else -> LazyColumn(
                        contentPadding = padding,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(items = loaded, key = { it.name }) { entry ->
                            val isDir = entry.isDirectory
                            FileRow(
                                file = entry,
                                icon = if (isDir) Icons.Filled.Folder else null,
                                enabled = !moving,
                                onClick = {
                                    if (isDir) {
                                        goInto(entry.name)
                                    } else {
                                        onOpenFile(
                                            if (path.isEmpty()) entry.name else "$path/${entry.name}"
                                        )
                                    }
                                },
                                onDelete = { deleteTarget = entry },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showTemplateDialog) {
        AlertDialog(
            onDismissRequest = { showTemplateDialog = false },
            title = { Text("新建示例项目") },
            text = {
                Column {
                    Text(
                        "在当前目录创建一个可直接构建运行的 CMake 项目。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TemplateProject.templates.forEachIndexed { index, template ->
                        TextButton(
                            onClick = {
                                // dirAt(), not resolve(): the root ("") is a valid
                                // target but has no non-empty relative path.
                                val parent = repository.dirAt(path)
                                if (parent == null) {
                                    showTemplateDialog = false
                                    scope.launch { snackbarHost.showSnackbar("当前目录不可用") }
                                    return@TextButton
                                }
                                val name = if (index == 0) "hello-c" else "hello-cpp"
                                val created = TemplateProject.create(parent.resolve(name), template)
                                showTemplateDialog = false
                                refresh()
                                scope.launch {
                                    snackbarHost.showSnackbar(
                                        if (created.isEmpty()) "同名文件已存在，未覆盖"
                                        else "已创建 $name（${created.size} 个文件）",
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(template.name) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showTemplateDialog = false }) { Text("取消") }
            },
        )
    }

    if (showNewDialog) {
        AlertDialog(
            onDismissRequest = { showNewDialog = false },
            title = { Text(if (newIsDir) "新建文件夹" else "新建文件") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        // The window is adjustNothing, so lift the dialog above
                        // the keyboard here instead of resizing the page.
                        .imePadding(),
                ) {
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = !newIsDir,
                            onClick = {
                                if (newIsDir && newName == "new_folder") newName = "untitled.c"
                                newIsDir = false
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                            modifier = Modifier.weight(1f),
                        ) { Text("文件") }
                        SegmentedButton(
                            selected = newIsDir,
                            onClick = {
                                if (!newIsDir && newName == "untitled.c") newName = "new_folder"
                                newIsDir = true
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                            modifier = Modifier.weight(1f),
                        ) { Text("文件夹") }
                    }
                    OutlinedTextField(
                        value = newName,
                        onValueChange = {
                            newName = it
                            newError = null
                        },
                        singleLine = true,
                        label = {
                            Text(
                                if (newIsDir) "文件夹名"
                                else "文件名 (.c / .h / .cpp / .hpp / CMakeLists.txt …)"
                            )
                        },
                        isError = newError != null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                    )
                    // Reported inline: a snackbar would be hidden behind the dialog.
                    newError?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = newName.trim()
                    if (name.isEmpty() || name.contains('/') || name.contains('\\') || name.contains(':')) {
                        newError = "名称不能为空，且不能包含 / \\ :"
                    } else {
                        val ok = if (newIsDir) repository.createDirectory(path, name)
                        else repository.create(path, name)
                        if (ok) {
                            refresh()
                            showNewDialog = false
                            newError = null
                        } else {
                            newError = "创建失败：名称已存在或名称非法"
                        }
                    }
                }) { Text("创建") }
            },
            dismissButton = {
                TextButton(onClick = { showNewDialog = false }) { Text("取消") }
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除") },
            text = {
                Text(
                    if (target.isDirectory)
                        "确定删除 ${target.name} 吗？将递归删除其中所有内容，此操作不可撤销。"
                    else
                        "确定删除 ${target.name} 吗？此操作不可撤销。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val rel = if (path.isEmpty()) target.name else "$path/${target.name}"
                    if (repository.delete(rel)) {
                        refresh()
                        deleteTarget = null
                    } else {
                        scope.launch { snackbarHost.showSnackbar("删除失败") }
                        deleteTarget = null
                    }
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun FileRow(
    file: File,
    icon: ImageVector?,
    enabled: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 12.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(file.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = if (file.isDirectory)
                    "文件夹 · ${formatTime(file.lastModified())}"
                else
                    "${formatSize(file.length())} · ${formatTime(file.lastModified())}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete, enabled = enabled) {
            Icon(Icons.Filled.Delete, contentDescription = "删除")
        }
    }
    HorizontalDivider()
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024))
}

private fun formatTime(time: Long): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    return sdf.format(Date(time))
}
