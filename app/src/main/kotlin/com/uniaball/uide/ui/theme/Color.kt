package com.uniaball.uide.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Color roles for C / C++ syntax highlighting.
 * Tuned to be readable on both light and dark surfaces (VS Code-like).
 */
data class SyntaxColors(
    val comment: Color,
    val string: Color,
    val preprocessor: Color,
    val number: Color,
    val keyword: Color,
    val type: Color,
    val function: Color,
    val operator: Color,
)

fun syntaxColors(dark: Boolean): SyntaxColors = if (dark) {
    SyntaxColors(
        comment = Color(0xFF6A9955),
        string = Color(0xFFCE9178),
        preprocessor = Color(0xFFC586C0),
        number = Color(0xFFB5CEA8),
        keyword = Color(0xFF569CD6),
        type = Color(0xFF4EC9B0),
        function = Color(0xFFDCDCAA),
        operator = Color(0xFFD4D4D4),
    )
} else {
    SyntaxColors(
        comment = Color(0xFF008000),
        string = Color(0xFFA31515),
        preprocessor = Color(0xFFAF00DB),
        number = Color(0xFF098658),
        keyword = Color(0xFF0000FF),
        type = Color(0xFF267F99),
        function = Color(0xFF795E26),
        operator = Color(0xFF5A5A5A),
    )
}
