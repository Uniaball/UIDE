package com.uniaball.uide.syntax

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import com.uniaball.uide.ui.theme.SyntaxColors

/**
 * Minimal C syntax highlighter.
 *
 * Implementation: a single forward scan with a small state machine so that
 * tokens inside comments / strings are NOT mistaken for keywords, etc.
 * Good enough for an initial editor; not a full C parser.
 */
object CSyntaxHighlighter {

    private val KEYWORDS = setOf(
        "auto", "break", "case", "char", "const", "continue", "default", "do",
        "double", "else", "enum", "extern", "float", "for", "goto", "if",
        "inline", "int", "long", "register", "restrict", "return", "short",
        "signed", "sizeof", "static", "struct", "switch", "typedef", "union",
        "unsigned", "void", "volatile", "while",
        "_Bool", "_Complex", "_Imaginary",
    )

    private val TYPES = setOf(
        "int", "char", "float", "double", "void", "long", "short",
        "unsigned", "signed", "bool", "size_t",
        "int8_t", "int16_t", "int32_t", "int64_t",
        "uint8_t", "uint16_t", "uint32_t", "uint64_t", "wchar_t",
    )

    fun highlight(text: String, colors: SyntaxColors): AnnotatedString {
        val builder = AnnotatedString.Builder(text.length)
        builder.append(text)
        var i = 0
        val n = text.length

        while (i < n) {
            val c = text[i]
            when {
                // line comment  //...
                c == '/' && i + 1 < n && text[i + 1] == '/' -> {
                    val start = i
                    while (i < n && text[i] != '\n') i++
                    style(builder, start, i, colors.comment)
                }

                // block comment  /* ... */
                c == '/' && i + 1 < n && text[i + 1] == '*' -> {
                    val start = i
                    i += 2
                    while (i < n && !(text[i] == '*' && i + 1 < n && text[i + 1] == '/')) i++
                    if (i < n) i += 2 else i = n
                    style(builder, start, i, colors.comment)
                }

                // string literal  "..."
                c == '"' -> {
                    val start = i
                    i++
                    while (i < n) {
                        if (text[i] == '\\' && i + 1 < n) { i += 2; continue }
                        if (text[i] == '"') { i++; break }
                        i++
                    }
                    style(builder, start, i, colors.string)
                }

                // char literal  '...'
                c == '\'' -> {
                    val start = i
                    i++
                    while (i < n) {
                        if (text[i] == '\\' && i + 1 < n) { i += 2; continue }
                        if (text[i] == '\'') { i++; break }
                        i++
                    }
                    style(builder, start, i, colors.string)
                }

                // preprocessor directive  #...  (only at line start / after blanks)
                c == '#' -> {
                    val lineStart = text.lastIndexOf('\n', i - 1) + 1
                    val before = if (lineStart < i) text.substring(lineStart, i) else ""
                    if (lineStart == i || before.all { it == ' ' || it == '\t' }) {
                        val start = i
                        while (i < n && text[i] != '\n') i++
                        style(builder, start, i, colors.preprocessor)
                    } else {
                        i++
                    }
                }

                // number literal
                c.isDigit() -> {
                    val start = i
                    while (i < n && (text[i].isLetterOrDigit() || text[i] == '.' ||
                        text[i] == '_' || text[i] == 'x' || text[i] == 'X' ||
                        text[i] == '+' || text[i] == '-')
                    ) {
                        i++
                    }
                    // drop a trailing lone '.' or sign from the colored range
                    while (i > start && !text[i - 1].isLetterOrDigit() && text[i - 1] != '_') i--
                    style(builder, start, i, colors.number)
                }

                // identifier: keyword / type / function-call
                c.isLetter() || c == '_' || c == '$' -> {
                    val start = i
                    while (i < n && (text[i].isLetterOrDigit() || text[i] == '_' || text[i] == '$')) i++
                    val word = text.substring(start, i)
                    // function call? skip blanks then '('
                    var j = i
                    while (j < n && (text[j] == ' ' || text[j] == '\t')) j++
                    val isFunc = j < n && text[j] == '('
                    val color = when {
                        word in KEYWORDS -> colors.keyword
                        word in TYPES -> colors.type
                        isFunc -> colors.function
                        else -> null
                    }
                    if (color != null) style(builder, start, i, color)
                }

                else -> i++
            }
        }

        return builder.toAnnotatedString()
    }

    private fun style(builder: AnnotatedString.Builder, start: Int, end: Int, color: androidx.compose.ui.graphics.Color) {
        if (end > start) builder.addStyle(SpanStyle(color = color), start, end)
    }
}
