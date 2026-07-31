package com.uniaball.uide.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Color roles for C / C++ syntax highlighting.
 * Tuned to be readable on both light and dark surfaces (VS Code-like).
 *
 * The base 8 roles (comment/string/preprocessor/number/keyword/type/function/
 * operator) keep a stable color consensus. The extra 3 roles (constant/member/
 * boolean) only add more distinct colors for *different kinds of words*; they
 * never change the established base palette.
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
    // ---- extra roles: more color variety for different words ----
    val constant: Color,   // macros / ALL_CAPS identifiers, e.g. MAX, BUFFER_SIZE
    val member: Color,     // member / namespace access, e.g. obj.field, ns::name
    val boolean: Color,    // boolean / null literals: true, false, NULL, nullptr
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
        // extra roles (distinct hues, dark)
        constant = Color(0xFFFFB86C),   // amber/orange
        member = Color(0xFF9CDCFE),     // light sky blue
        boolean = Color(0xFFE06C9F),    // pink/rose
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
        // extra roles (distinct, readable on white)
        constant = Color(0xFFB45309),   // dark orange
        member = Color(0xFF0A6EBE),     // blue
        boolean = Color(0xFFC2185B),    // rose
    )
}
