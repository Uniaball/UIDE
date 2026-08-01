package com.uniaball.uide.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Color roles for C / C++ syntax highlighting.
 *
 * Palette ported from VS Code's default themes (`Dark+` / `Light+`,
 * microsoft/vscode `extensions/theme-defaults/themes/`). Dark values are taken
 * from `dark_vs.json` + `dark_plus.json`; light values from `light_vs.json` +
 * `light_plus.json`.
 *
 * [CSemanticAnalyzer] collects declared variable names before highlighting.
 * before highlighting. Only identifiers that were previously declared (e.g.
 * `int count;` → `count`) are colored as variable. Random typing still stays
 * default-colored — matching AndroidIDE's behavior.
 *
 * Roles: 9 base (incl. variable) plus 4 extra (constant/member/boolean/classname).
 *
 * NOTE — some roles intentionally share the same colour (matching VS Code's
 * default behaviour): keyword = boolean, type = classname, variable = member.
 */
data class SyntaxColors(
    val comment: Color,
    val string: Color,
    val preprocessor: Color,
    val number: Color,
    val keyword: Color,
    val type: Color,
    val function: Color,
    val variable: Color,    // declared variables (identified by semantic analyzer)
    val operator: Color,
    // ---- extra roles: more color variety for different words ----
    val constant: Color,   // macros / ALL_CAPS identifiers, e.g. MAX, BUFFER_SIZE
    val member: Color,     // member / namespace access, e.g. obj.field, ns::name
    val boolean: Color,    // boolean / null literals: true, false, NULL, nullptr
    val classname: Color,  // classes / namespaces: PascalCase ids & qualifiers before ::
    // ---- search match ----
    val searchMatchBg: Color,  // background for search-term highlight
    val searchMatchFg: Color,  // foreground (text) color on search-match background
)

fun syntaxColors(dark: Boolean): SyntaxColors = if (dark) {
    // VS Code "Dark+" (dark_vs.json + dark_plus.json)
    SyntaxColors(
        comment = Color(0xFF6A9955),      // comment
        string = Color(0xFFCE9178),       // string
        preprocessor = Color(0xFF569CD6), // meta.preprocessor (blue, like keyword)
        number = Color(0xFFB5CEA8),       // constant.numeric
        keyword = Color(0xFF569CD6),      // keyword
        type = Color(0xFF4EC9B0),         // entity.name.type
        function = Color(0xFFDCDCAA),     // entity.name.function
        variable = Color(0xFF9CDCFE),     // variable
        operator = Color(0xFFD4D4D4),     // keyword.operator (light gray)
        // extra roles
        constant = Color(0xFF4FC1FF),     // variable.other.constant (macros)
        member = Color(0xFF9CDCFE),       // variable (member access)
        boolean = Color(0xFF569CD6),      // constant.language (true/false/NULL)
        classname = Color(0xFF4EC9B0),    // entity.name.type/class/namespace
        // search match
        searchMatchBg = Color(0x44FFF59D),    // translucent yellow, readable on dark surfaces
        searchMatchFg = Color(0xFF1E1E1E),    // near-black, readable on yellow bg
    )
} else {
    // VS Code "Light+" (light_vs.json + light_plus.json)
    SyntaxColors(
        comment = Color(0xFF008000),      // comment
        string = Color(0xFFA31515),       // string
        preprocessor = Color(0xFF0000FF), // meta.preprocessor (blue, like keyword)
        number = Color(0xFF098658),       // constant.numeric
        keyword = Color(0xFF0000FF),      // keyword
        type = Color(0xFF267F99),         // entity.name.type
        function = Color(0xFF795E26),     // entity.name.function
        variable = Color(0xFF001080),     // variable
        operator = Color(0xFF000000),     // keyword.operator (black, like default text)
        // extra roles
        constant = Color(0xFF0070C1),     // variable.other.constant (macros)
        member = Color(0xFF001080),       // variable (member access)
        boolean = Color(0xFF0000FF),      // constant.language (true/false/NULL)
        classname = Color(0xFF267F99),    // entity.name.type/class/namespace
        // search match
        searchMatchBg = Color(0x44FFEB3B),    // translucent yellow, readable on light surfaces
        searchMatchFg = Color(0xFF000000),    // black, readable on yellow bg
    )
}
