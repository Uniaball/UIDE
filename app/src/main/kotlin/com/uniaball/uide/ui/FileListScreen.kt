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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
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
    var files by remember { mutableStateOf(repository.listFiles()) }
    // The editor bumps this counter after a save so the list re-reads on return
    // (the screen is kept alive on the NavHost back stack, so its `remember`
    // value would otherwise stay stale).
    val refreshSignal by savedStateHandle.getStateFlow("uide_refresh", 0).collectAsState()
    var showNewDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("untitled.c") }
    var deleteTarget by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        files = repository.listFiles()
    }

    LaunchedEffect(refreshSignal) {
        refresh()
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("UIDE") }) },
        snackbarHost = { SnackbarHost(snackbarHost) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showNewDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = "新建文件")
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
                items(items = files, key = { it.name }) { file ->
                    FileRow(
                        file = file,
                        onClick = { onOpenFile(file.name) },
                        onDelete = { deleteTarget = file.name },
                    )
                }
            }
        }
    }

    if (showNewDialog) {
        AlertDialog(
            onDismissRequest = { showNewDialog = false },
            title = { Text("新建文件") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    label = { Text("文件名 (.c / .h / .cpp / .hpp …)") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = newName.trim()
                    if (name.isNotEmpty() && repository.create(name)) {
                        refresh()
                        showNewDialog = false
                    } else if (name.isNotEmpty()) {
                        scope.launch { snackbarHost.showSnackbar("创建失败") }
                    }
                }) { Text("创建") }
            },
            dismissButton = {
                TextButton(onClick = { showNewDialog = false }) { Text("取消") }
            },
        )
    }

    deleteTarget?.let { name ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除文件") },
            text = { Text("确定删除 $name 吗？此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    if (repository.delete(name)) {
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
        Column(modifier = Modifier.weight(1f)) {
            Text(file.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = "${formatSize(file.length())} · ${formatTime(file.lastModified())}",
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
