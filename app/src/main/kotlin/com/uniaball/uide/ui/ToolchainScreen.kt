package com.uniaball.uide.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uniaball.uide.build.CheckResult
import com.uniaball.uide.build.EnvironmentChecker
import com.uniaball.uide.build.NativeExec
import com.uniaball.uide.build.ProcessRunner
import com.uniaball.uide.build.ToolchainManager
import com.uniaball.uide.build.ToolchainState
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolchainScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val nativeExec = remember { NativeExec(context) }
    val toolchains = remember { ToolchainManager(context, nativeExec) }
    val runner = remember { ProcessRunner(nativeExec) }
    val checker = remember { EnvironmentChecker(context, nativeExec, runner) }
    val scope = rememberCoroutineScope()
    val toolchain by toolchains.state.collectAsStateWithLifecycle()
    var selfTestRunning by remember { mutableStateOf(false) }
    var selfTestResults by remember { mutableStateOf<List<CheckResult>>(emptyList()) }

    // Installs that predate the cached size in the marker file get measured
    // here, on IO, instead of blocking the first frame.
    LaunchedEffect(Unit) { toolchains.refreshMeasured() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("构建环境") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            ToolchainCard(
                state = toolchain,
                onInstall = { scope.launch { toolchains.install() } },
                onRemove = { toolchains.remove() },
            )

            Spacer(Modifier.height(16.dp))
            Text("执行链路自检", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            Button(
                onClick = {
                    scope.launch {
                        selfTestRunning = true
                        selfTestResults = checker.runAndReport()
                        selfTestRunning = false
                    }
                },
                enabled = !selfTestRunning && nativeExec.isSelfTestAvailable,
            ) {
                Text(if (selfTestRunning) "自检中…" else "运行环境自检")
            }

            if (selfTestRunning) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator()
                    Spacer(Modifier.width(16.dp))
                    Text("正在验证程序执行链路…")
                }
            }

            if (selfTestResults.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        selfTestResults.forEachIndexed { index, result ->
                            CheckRow(result)
                            if (index != selfTestResults.lastIndex) HorizontalDivider()
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = "自检会在应用数据目录中释放一个测试程序，验证它能否被系统动态链接器拉起、" +
                    "子进程 exec / posix_spawn 是否被钩子正确改写，以及 #! 脚本能否运行。" +
                    "结果同时写入 ${EnvironmentChecker.REPORT_NAME}。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ToolchainCard(
    state: ToolchainState,
    onInstall: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("C/C++ 工具链", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                text = when (state.status) {
                    ToolchainState.Status.NOT_INSTALLED -> "尚未安装（clang / cmake / ninja）"
                    ToolchainState.Status.INSTALLING -> state.message.ifEmpty { "正在安装…" }
                    ToolchainState.Status.READY ->
                        "已安装 · 占用 ${formatSize(state.installedBytes)}"

                    ToolchainState.Status.FAILED -> "安装失败：${state.message}"
                    ToolchainState.Status.UNSUPPORTED -> state.message
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (state.status == ToolchainState.Status.INSTALLING) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state.totalBytes > 0) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "${formatSize(state.extractedBytes)} / ${formatSize(state.totalBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.End,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                when (state.status) {
                    ToolchainState.Status.READY -> OutlinedButton(onClick = onRemove) {
                        Text("删除工具链")
                    }

                    ToolchainState.Status.INSTALLING -> Unit
                    else -> Button(onClick = onInstall) { Text("安装工具链") }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CheckRow(result: CheckResult) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = if (result.ok) Icons.Filled.CheckCircle else Icons.Filled.Error,
            contentDescription = null,
            tint = if (result.ok) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(result.name, style = MaterialTheme.typography.bodyMedium)
            Text(
                result.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes <= 0 -> "0 B"
    bytes < 1024L * 1024 -> "${bytes / 1024} KB"
    else -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
}