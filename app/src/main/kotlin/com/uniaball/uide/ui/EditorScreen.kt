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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.SavedStateHandle
import com.uniaball.uide.data.FileRepository
import com.uniaball.uide.syntax.CSyntaxHighlighter
import com.uniaball.uide.ui.theme.EditorFontFamily
import com.uniaball.uide.ui.theme.syntaxColors
import kotlinx.coroutines.launch

private val EDITOR_FONT_SIZE = 14.sp
private val EDITOR_LINE_HEIGHT = 20.sp
private val EDITOR_PADDING = 12.dp

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
    // Notify if file read produced an empty result (possible I/O error)
    LaunchedEffect(fileName) {
        if (text.text.isEmpty() && repository.listFiles().any { it.name == fileName }) {
            snackbarHost.showSnackbar("文件可能为空或读取失败")
        }
    }
    val mode = remember(fileName) { CSyntaxHighlighter.isCppFile(fileName) }
    val highlighted = remember(text.text, mode) {
        CSyntaxHighlighter.highlight(text.text, colors, mode)
    }

    val editorStyle = remember {
        TextStyle(
            fontFamily = EditorFontFamily,
            fontSize = EDITOR_FONT_SIZE,
            lineHeight = EDITOR_LINE_HEIGHT,
        )
    }
    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()
    var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

    val layerModifier = Modifier
        .fillMaxSize()
        .verticalScroll(vScroll)
        .horizontalScroll(hScroll)
        .padding(EDITOR_PADDING)

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
                            val key = "uide_refresh"
                            fileListHandle?.set(key, (fileListHandle.get<Int>(key) ?: 0) + 1)
                        } else {
                            scope.launch { snackbarHost.showSnackbar("保存失败") }
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
            // Bottom layer: syntax-highlighted text with wavy error underlines.
            Text(
                text = highlighted,
                style = editorStyle,
                softWrap = false,
                overflow = TextOverflow.Visible,
                onTextLayout = { textLayoutResult = it },
                modifier = layerModifier.drawWithContent {
                    drawContent()
                    val layout = textLayoutResult ?: return@drawWithContent
                    val errs = highlighted.getStringAnnotations("uide_error", 0, highlighted.length)
                    if (errs.isNotEmpty()) {
                        val waveColor = colors.error
                        for (err in errs) {
                            val startLine = layout.getLineForOffset(err.start)
                            val endLine = layout.getLineForOffset(
                                (err.end - 1).coerceAtLeast(err.start)
                            )
                            for (line in startLine..endLine) {
                                val left = if (line == startLine)
                                    layout.getHorizontalPosition(err.start, true)
                                else
                                    layout.getLineLeft(line)
                                val right = if (line == endLine)
                                    layout.getHorizontalPosition(err.end, true)
                                else
                                    layout.getLineRight(line)
                                val bottom = layout.getLineBottom(line)
                                drawWavyLine(left, right, bottom, waveColor)
                            }
                        }
                    }
                },
            )
            // Top layer: transparent editable input with auto-indent on Enter.
            BasicTextField(
                value = text,
                onValueChange = { newValue ->
                    val oldLen = text.text.length
                    val newLen = newValue.text.length
                    if (newLen == oldLen + 1) {
                        val cursor = newValue.selection.start
                        if (cursor > 0 && newValue.text[cursor - 1] == '\n') {
                            val t = newValue.text
                            val lineStart = t.lastIndexOf('\n', cursor - 2) + 1
                            val prevLine = t.substring(lineStart, cursor - 1)
                            val indent = prevLine.takeWhile { it == ' ' || it == '\t' }
                            val trimmed = prevLine.trimEnd()

                            if (trimmed.endsWith("{")) {
                                // VS Code-style auto-close brace on Enter
                                val inner = indent + "    "
                                var after = t.substring(cursor)
                                if (after.startsWith("}")) after = after.substring(1)
                                val newText = t.substring(0, cursor) + "$inner\n$indent}" + after
                                text = newValue.copy(
                                    text = newText,
                                    selection = TextRange(cursor + inner.length),
                                )
                            } else {
                                // Normal Enter: copy previous line's indent
                                val newText = t.substring(0, cursor) + indent + t.substring(cursor)
                                text = newValue.copy(
                                    text = newText,
                                    selection = TextRange(cursor + indent.length),
                                )
                            }
                            return@BasicTextField
                        }
                    }
                    text = newValue
                },
                textStyle = editorStyle.copy(color = Color.Transparent),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                modifier = layerModifier,
            )
        }
    }
}

/** Draw a bold red wavy (squiggly) underline — two-pass for extra visibility. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawWavyLine(
    startX: Float, endX: Float, y: Float, color: Color,
) {
    if (endX <= startX) return
    val path = Path()
    val waveLen = 5f
    val amp = 4f
    path.moveTo(startX, y)
    var x = startX
    while (x < endX) {
        val w = minOf(waveLen, endX - x)
        path.relativeCubicTo(w * 0.25f, amp, w * 0.75f, -amp, w, 0f)
        x += w
    }
    // Shadow pass: thicker, semi-transparent
    drawPath(path, color.copy(alpha = 0.35f), style = Stroke(width = 4f, cap = StrokeCap.Round))
    // Main pass: bold core line
    drawPath(path, color, style = Stroke(width = 2f, cap = StrokeCap.Round))
}
