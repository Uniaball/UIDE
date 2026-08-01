package com.uniaball.uide.syntax

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import com.uniaball.uide.ui.theme.SyntaxColors

/**
 * Lightweight C / C++ syntax highlighter.
 *
 * Implementation: a single forward scan with a small state machine so that
 * tokens inside comments / strings are NOT mistaken for keywords, etc.
 * Not a full parser — good enough for an initial code editor.
 *
 * C++ is syntactically a superset of C, so the scanning logic is identical
 * for both; only the recognised keyword / type sets differ (see [isCpp]).
 */
object CSyntaxHighlighter {

    // ---- C keywords ----
    private val C_KEYWORDS = setOf(
        "auto", "break", "case", "char", "const", "continue", "default", "do",
        "double", "else", "enum", "extern", "float", "for", "goto", "if",
        "inline", "int", "long", "register", "restrict", "return", "short",
        "signed", "sizeof", "static", "struct", "switch", "typedef", "union",
        "unsigned", "void", "volatile", "while",
        "_Bool", "_Complex", "_Imaginary",
    )

    // ---- C++ keywords (includes the C set above) ----
    private val CPP_KEYWORDS = setOf(
        // C keywords
        "auto", "break", "case", "char", "const", "continue", "default", "do",
        "double", "else", "enum", "extern", "float", "for", "goto", "if",
        "inline", "int", "long", "register", "restrict", "return", "short",
        "signed", "sizeof", "static", "struct", "switch", "typedef", "union",
        "unsigned", "void", "volatile", "while",
        "_Bool", "_Complex", "_Imaginary",
        // C++ additions
        "alignas", "alignof", "asm", "bool", "catch", "class", "compl",
        "concept", "const_cast", "consteval", "constexpr", "constinit",
        "decltype", "delete", "dynamic_cast", "explicit", "export", "false",
        "friend", "mutable", "namespace", "new", "noexcept", "nullptr",
        "operator", "private", "protected", "public", "reinterpret_cast",
        "requires", "static_assert", "static_cast", "template", "this",
        "thread_local", "throw", "true", "try", "typeid", "typename", "using",
        "virtual", "wchar_t", "co_await", "co_return", "co_yield", "char8_t",
        "char16_t", "char32_t",
        // alternative tokens
        "and", "and_eq", "bitand", "bitor", "not", "not_eq", "or", "or_eq",
        "xor", "xor_eq",
    )

    // ---- C built-in / common types ----
    private val C_TYPES = setOf(
        "int", "char", "float", "double", "void", "long", "short",
        "unsigned", "signed", "bool", "size_t",
        "int8_t", "int16_t", "int32_t", "int64_t",
        "uint8_t", "uint16_t", "uint32_t", "uint64_t", "wchar_t",
    )

    // ---- C++ additional types (std + built-ins) ----
    private val CPP_TYPES = setOf(
        "int", "char", "float", "double", "void", "long", "short",
        "unsigned", "signed", "bool", "size_t", "wchar_t",
        "int8_t", "int16_t", "int32_t", "int64_t",
        "uint8_t", "uint16_t", "uint32_t", "uint64_t",
        "char8_t", "char16_t", "char32_t", "nullptr_t",
        "string", "string_view", "vector", "map", "set", "list", "array",
        "pair", "tuple", "queue", "stack", "deque", "bitset",
        "unordered_map", "unordered_set", "initializer_list",
        "shared_ptr", "unique_ptr", "weak_ptr",
        "iostream", "ostream", "istream", "stringstream", "ofstream",
        "ifstream", "complex", "valarray", "atomic",
    )

    // ---- boolean / null literals (distinct color from keywords) ----
    private val BOOLEANS = setOf(
        "true", "false", "TRUE", "FALSE", "NULL", "nullptr",
    )

    /**
     * Highlight [text]. Pass [isCpp] = true for C++ sources (.cpp/.hpp/...)
     * so the C++ keyword / type vocabulary is used; otherwise C is assumed.
     */
    fun highlight(text: String, colors: SyntaxColors, isCpp: Boolean = false): AnnotatedString {
        val keywords = if (isCpp) CPP_KEYWORDS else C_KEYWORDS
        val types = if (isCpp) CPP_TYPES else C_TYPES
        return scan(text, colors, keywords, types)
    }

    /** True for file names that should be highlighted as C++. */
    fun isCppFile(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".cpp") || lower.endsWith(".cc") ||
            lower.endsWith(".cxx") || lower.endsWith(".c++") ||
            lower.endsWith(".hpp") || lower.endsWith(".hxx") ||
            lower.endsWith(".hh") || lower.endsWith(".h++")
    }

    private fun scan(
        text: String,
        colors: SyntaxColors,
        keywords: Set<String>,
        types: Set<String>,
    ): AnnotatedString {
        val builder = AnnotatedString.Builder(text.length)
        builder.append(text)
        var i = 0
        var pendingMember = false
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

                // string literal: plain "...", encoding-prefixed (L/u8/u/U), or raw R"(...)"
                c == '"' ||
                (c == 'R' && i + 1 < n && text[i + 1] == '"') ||
                ((c == 'L' || c == 'u' || c == 'U') && i + 1 < n && text[i + 1] == '"') ||
                (c == 'u' && i + 2 < n && text[i + 1] == '8' && text[i + 2] == '"') -> {
                    val start = i
                    // consume the optional prefix so the body scan starts at the quote
                    if (c != '"') {
                        if (c == 'R') {
                            i += 2                 // R"(...)
                        } else if (c == 'u' && i + 2 < n && text[i + 1] == '8') {
                            i += 3                 // u8"(...)
                        } else {
                            i += 2                 // L"(...) / u"(...) / U"(...)
                        }
                    } else {
                        i += 1                     // plain "..."
                    }
                    if (c == 'R' && i < n && text[i] == '(') {
                        // raw string: read until the closing )"
                        i++
                        while (i < n) {
                            if (text[i] == ')' && i + 1 < n && text[i + 1] == '"') { i += 2; break }
                            i++
                        }
                    } else {
                        while (i < n) {
                            if (text[i] == '\\' && i + 1 < n) { i += 2; continue }
                            if (text[i] == '"') { i++; break }
                            i++
                        }
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

                // operators that are characteristic of C++ (and common in C)
                // scope resolution  ::   (ns::name — name is a member)
                c == ':' && i + 1 < n && text[i + 1] == ':' -> {
                    style(builder, i, i + 2, colors.operator)
                    pendingMember = true
                    i += 2
                }
                // member access  ->   (and the rare ->*)
                c == '-' && i + 1 < n && text[i + 1] == '>' -> {
                    val start = i
                    i += 2
                    if (i < n && text[i] == '*') i++   // ->*
                    style(builder, start, i, colors.operator)
                    pendingMember = true
                }
                // shift / stream  <<
                c == '<' && i + 1 < n && text[i + 1] == '<' -> {
                    style(builder, i, i + 2, colors.operator)
                    i += 2
                }
                // shift / closing template  >>
                c == '>' && i + 1 < n && text[i + 1] == '>' -> {
                    style(builder, i, i + 2, colors.operator)
                    i += 2
                }

                // member access  .   (obj.field) — mark the following identifier
                c == '.' -> {
                    if (i + 1 < n && (text[i + 1].isLetter() || text[i + 1] == '_')) {
                        pendingMember = true
                    }
                    i++
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
                    val isMember = pendingMember
                    pendingMember = false
                    // Priority: boolean literal > keyword > type > function call >
                    // member access > ALL_CAPS constant (macros) > plain identifier
                    // (plain identifiers reuse the function color).
                    val color = when {
                        word in BOOLEANS -> colors.boolean
                        word in keywords -> colors.keyword
                        word in types -> colors.type
                        isFunc -> colors.function
                        isMember -> colors.member
                        word.isAllCaps() -> colors.constant
                        else -> colors.function
                    }
                    if (color != null) style(builder, start, i, color)
                }

                else -> i++
            }
        }

        return builder.toAnnotatedString()
    }

    private fun style(
        builder: AnnotatedString.Builder,
        start: Int,
        end: Int,
        color: Color,
    ) {
        if (end > start) builder.addStyle(SpanStyle(color = color), start, end)
    }

    /** True for macro-like identifiers: ALL_CAPS with at least one letter. */
    private fun String.isAllCaps(): Boolean {
        if (length < 2) return false
        var hasUpper = false
        for (ch in this) {
            when {
                ch.isUpperCase() -> hasUpper = true
                ch.isLowerCase() -> return false   // mixed/lower → not a macro
                // digits and '_' are allowed
            }
        }
        return hasUpper
    }
}
