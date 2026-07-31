package com.uniaball.uide.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.SavedStateHandle
import com.uniaball.uide.data.FileRepository
import com.uniaball.uide.syntax.CSyntaxHighlighter
import com.uniaball.uide.ui.theme.EditorFontFamily
import com.uniaball.uide.ui.theme.syntaxColors
import kotlinx.coroutines.launch

@Composable
fun EditorScreen(
    fileName: String,
    repository: FileRepository,
    fileListHandle: SavedStateHandle? = null,
    onBack: () -> Unit,
) {
    val dark = isSystemInDarkTheme()
    val colors = remember(dark) { syntaxColors(dark) }
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var text by remember(fileName) { mutableStateOf(TextFieldValue(repository.read(fileName))) }
    val isCpp = remember(fileName) { CSyntaxHighlighter.isCppFile(fileName) }
    val highlighted = remember(text.text, isCpp) {
        CSyntaxHighlighter.highlight(text.text, colors, isCpp)
    }

    val editorStyle = remember {
        TextStyle(
            fontFamily = EditorFontFamily,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        )
    }
    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()

    val layerModifier = Modifier
        .fillMaxSize()
        .verticalScroll(vScroll)
        .horizontalScroll(hScroll)
        .padding(12.dp)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(fileName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        if (repository.write(fileName, text.text)) {
                            scope.launch { snackbarHost.showSnackbar("已保存") }
                            // Tell the file list to re-read when we return.
                            val key = "uide_refresh"
                            fileListHandle?.set(key, (fileListHandle.get<Int>(key) ?: 0) + 1)
                        }
                    }) {
                        Icon(Icons.Filled.Save, contentDescription = "保存")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.surface),
        ) {
            // Bottom layer: syntax-highlighted, read-only text.
            Text(
                text = highlighted,
                style = editorStyle,
                softWrap = false,
                modifier = layerModifier,
            )
            // Top layer: actual editable input, text rendered transparent so the
            // highlighted layer shows through. Both share the scroll states.
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = editorStyle.copy(color = Color.Transparent),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                modifier = layerModifier,
            )
        }
    }
}
