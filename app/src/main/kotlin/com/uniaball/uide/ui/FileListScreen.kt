package com.uniaball.uide.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import com.uniaball.uide.data.FileRepository
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun FileListScreen(
    repository: FileRepository,
    savedStateHandle: SavedStateHandle,
    onOpenFile: (String) -> Unit,
) {
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // Current browsed directory (relative path, "" = root).
    var path by remember { mutableStateOf("") }
    var files by remember(path) { mutableStateOf(repository.listEntries(path)) }
    // The editor bumps this counter after a save so the list re-reads on return
    // (the screen is kept alive on the NavHost back stack, so its `remember`
    // value would otherwise stay stale).
    val refreshSignal by savedStateHandle.getStateFlow("uide_refresh", 0).collectAsState()
    var showNewDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("untitled.c") }
    var newIsDir by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<File?>(null) }

    fun refresh() {
        files = repository.listEntries(path)
    }

    fun joinPath(entryName: String): String =
        if (path.isEmpty()) entryName else "$path/$entryName"

    LaunchedEffect(refreshSignal) {
        refresh()
    }

    val dirName = path.substringAfterLast('/')

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (path.isEmpty()) "UIDE" else dirName) },
                navigationIcon = {
                    if (path.isNotEmpty()) {
                        IconButton(onClick = {
                            path = path.substringBeforeLast('/', "")
                            refresh()
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回上级")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showNewDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = "新建")
            }
        },
    ) { padding ->
        if (files.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "暂无文件\n点击 + 创建新文件",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                contentPadding = padding,
                modifier = Modifier.fillMaxSize(),
            ) {
                items(items = files, key = { it.relativePath(path) }) { entry ->
                    val isDir = entry.isDirectory
                    FileRow(
                        file = entry,
                        icon = if (isDir) Icons.Filled.Folder else null,
                        onClick = {
                            if (isDir) {
                                path = joinPath(entry.name)
                                refresh()
                            } else {
                                onOpenFile(joinPath(entry.name))
                            }
                        },
                        onDelete = { deleteTarget = entry },
                    )
                }
            }
        }
    }

    if (showNewDialog) {
        AlertDialog(
            onDismissRequest = { showNewDialog = false },
            title = { Text(if (newIsDir) "新建文件夹" else "新建文件") },
            text = {
                Column {
                    SingleChoiceSegmentedButtonRow {
                        SegmentedButton(
                            selected = !newIsDir,
                            onClick = {
                                if (newIsDir && newName == "new_folder") newName = "untitled.c"
                                newIsDir = false
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        ) { Text("文件") }
                        SegmentedButton(
                            selected = newIsDir,
                            onClick = {
                                if (!newIsDir && newName == "untitled.c") newName = "new_folder"
                                newIsDir = true
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        ) { Text("文件夹") }
                    }
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        singleLine = true,
                        label = { Text(if (newIsDir) "文件夹名" else "文件名 (.c / .h / .cpp / .hpp / CMakeLists.txt …)") },
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = newName.trim()
                    if (name.isEmpty() || name.contains('/') || name.contains('\\') || name.contains(':')) {
                        scope.launch { snackbarHost.showSnackbar("名称不能包含 / 且不能为空") }
                    } else {
                        val ok = if (newIsDir) repository.createDirectory(path, name)
                        else repository.create(path, name)
                        if (ok) {
                            refresh()
                            showNewDialog = false
                        } else {
                            scope.launch { snackbarHost.showSnackbar("创建失败（名称已存在或非法）") }
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
                    if (repository.delete(joinPath(target.name))) {
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

/** Relative path of a listed entry, used as a stable LazyColumn key. */
private fun File.relativePath(current: String): String =
    if (current.isEmpty()) name else "$current/$name"

@Composable
private fun FileRow(
    file: File,
    icon: ImageVector?,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
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
        IconButton(onClick = onDelete) {
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