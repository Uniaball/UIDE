package com.uniaball.uide.syntax

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import com.uniaball.uide.ui.theme.SyntaxColors

/**
 * C / C++ syntax highlighter.
 *
 * Architecture ported from AndroidIDE's `Highlighter` design (see
 * AndroidCSOfficial/android-code-studio): a single forward scan **tokenizes**
 * the source into categorized spans, then a paint pass maps each category to a
 * color. A trailing `match` pass (also from AndroidIDE's `JavaHighlighter`)
 * highlights an arbitrary search term.
 *
 * C++ is a syntactic superset of C, so the scan is identical for both; only the
 * recognised keyword / type vocabulary differs (see [isCpp]).
 *
 * NOTE: AndroidIDE's original uses an ANTLR lexer + sora `EditorColorScheme`
 * and Android `SpannableStringBuilder`. Those are not portable to Compose, so
 * this is a faithful Kotlin/Compose re-implementation of the same design.
 */
object CSyntaxHighlighter {

    /** Token categories — the bridge between scan and paint, like AndroidIDE's token types. */
    private enum class Category {
        COMMENT, STRING, PREPROCESSOR, NUMBER,
        KEYWORD, TYPE, FUNCTION, VARIABLE, OPERATOR,
        CONSTANT, MEMBER, BOOLEAN, CLASSNAME,
        TEXT_NORMAL,
    }

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

    // ---- declaration keywords whose following identifier is a type/namespace name ----
    private val DECL_KEYWORDS = setOf(
        "class", "struct", "namespace", "enum", "union", "typedef",
    )

    private data class Token(val start: Int, val end: Int, val category: Category)

    /**
     * Highlight [text]. Pass [isCpp] = true for C++ sources (.cpp/.hpp/...)
     * so the C++ keyword / type vocabulary is used; otherwise C is assumed.
     * [match] is an optional literal search term that gets a yellow background.
     */
    fun highlight(
        text: String,
        colors: SyntaxColors,
        isCpp: Boolean = false,
        match: String = "",
    ): AnnotatedString {
        val keywords = if (isCpp) CPP_KEYWORDS else C_KEYWORDS
        val types = if (isCpp) CPP_TYPES else C_TYPES
        val declared = MiniSyntaxChecker.findDeclared(text, isCpp)
        val tokens = tokenize(text, keywords, types, declared)
        return paint(text, tokens, colors, match)
    }

    /** True for file names that should be highlighted as C++. */
    fun isCppFile(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".cpp") || lower.endsWith(".cc") ||
            lower.endsWith(".cxx") || lower.endsWith(".c++") ||
            lower.endsWith(".hpp") || lower.endsWith(".hxx") ||
            lower.endsWith(".hh") || lower.endsWith(".h++")
    }

    // ---- scan: source text -> categorized tokens (order-preserving) ----
    private fun tokenize(text: String, keywords: Set<String>, types: Set<String>, declared: Set<String> = emptySet()): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        var pendingMember = false
        var pendingDecl = false   // set after class/struct/namespace/enum/...
        val n = text.length

        while (i < n) {
            val c = text[i]
            when {
                // line comment  //...
                c == '/' && i + 1 < n && text[i + 1] == '/' -> {
                    val start = i
                    while (i < n && text[i] != '\n') i++
                    tokens += Token(start, i, Category.COMMENT)
                }

                // block comment  /* ... */
                c == '/' && i + 1 < n && text[i + 1] == '*' -> {
                    val start = i
                    i += 2
                    while (i < n && !(text[i] == '*' && i + 1 < n && text[i + 1] == '/')) i++
                    if (i < n) i += 2 else i = n
                    tokens += Token(start, i, Category.COMMENT)
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
                    tokens += Token(start, i, Category.STRING)
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
                    tokens += Token(start, i, Category.STRING)
                }

                // preprocessor directive  #...  (only at line start / after blanks)
                c == '#' -> {
                    val lineStart = text.lastIndexOf('\n', i - 1) + 1
                    val before = if (lineStart < i) text.substring(lineStart, i) else ""
                    if (lineStart == i || before.all { it == ' ' || it == '\t' }) {
                        val start = i
                        while (i < n && text[i] != '\n') i++
                        tokens += Token(start, i, Category.PREPROCESSOR)
                    } else {
                        i++
                    }
                }

                // scope resolution  ::   (ns::name — name is a member)
                c == ':' && i + 1 < n && text[i + 1] == ':' -> {
                    tokens += Token(i, i + 2, Category.OPERATOR)
                    pendingMember = true
                    i += 2
                }
                // member access  ->   (and the rare ->*)
                c == '-' && i + 1 < n && text[i + 1] == '>' -> {
                    val start = i
                    i += 2
                    if (i < n && text[i] == '*') i++   // ->*
                    tokens += Token(start, i, Category.OPERATOR)
                    pendingMember = true
                }
                // shift / stream  <<
                c == '<' && i + 1 < n && text[i + 1] == '<' -> {
                    tokens += Token(i, i + 2, Category.OPERATOR)
                    i += 2
                }
                // shift / closing template  >>
                c == '>' && i + 1 < n && text[i + 1] == '>' -> {
                    tokens += Token(i, i + 2, Category.OPERATOR)
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
                    tokens += Token(start, i, Category.NUMBER)
                }

                // identifier: keyword / type / function-call / class-or-namespace
                c.isLetter() || c == '_' || c == '$' -> {
                    val start = i
                    while (i < n && (text[i].isLetterOrDigit() || text[i] == '_' || text[i] == '$')) i++
                    val word = text.substring(start, i)
                    // function call? skip blanks then '('
                    var j = i
                    while (j < n && (text[j] == ' ' || text[j] == '\t')) j++
                    val isFunc = j < n && text[j] == '('
                    // class / namespace qualifier? id immediately followed by ::
                    var k = i
                    while (k < n && (text[k] == ' ' || text[k] == '\t')) k++
                    val isQualifier = k < n && text[k] == ':' && k + 1 < n && text[k + 1] == ':'
                    // PascalCase identifier → class / type name
                    val isClassName = word.isNotEmpty() && word[0].isUpperCase() && !word.isAllCaps()
                    val isMember = pendingMember
                    pendingMember = false
                    val isDecl = pendingDecl
                    pendingDecl = false
                    // Priority: boolean > keyword > type > function call >
                    // class/namespace name (decl keyword / qualifier / PascalCase) >
                    // member access > ALL_CAPS constant (macros) >
                    // declared variable (mini syntax checker) > plain text (uncolored).
                    val category = when {
                        word in BOOLEANS -> Category.BOOLEAN
                        word in keywords -> Category.KEYWORD
                        word in types -> Category.TYPE
                        isFunc -> Category.FUNCTION
                        isDecl -> Category.CLASSNAME
                        isQualifier -> Category.CLASSNAME
                        isClassName -> Category.CLASSNAME
                        isMember -> Category.MEMBER
                        word.isAllCaps() -> Category.CONSTANT
                        word in declared -> Category.VARIABLE
                        else -> Category.TEXT_NORMAL
                    }
                    // the next identifier after a type/namespace decl keyword is a name,
                    // but only if the keyword is valid in the current language mode
                    // (e.g. 'namespace' is C++ only — don't trigger in .c files)
                    if (word in DECL_KEYWORDS && word in keywords) pendingDecl = true
                    tokens += Token(start, i, category)
                }

                else -> i++
            }
        }

        return tokens
    }

    // ---- paint: categorized tokens -> AnnotatedString ----
    private fun paint(
        text: String,
        tokens: List<Token>,
        colors: SyntaxColors,
        match: String,
    ): AnnotatedString {
        val builder = AnnotatedString.Builder(text.length)
        builder.append(text)
        for (token in tokens) {
            val color = when (token.category) {
                Category.COMMENT -> colors.comment
                Category.STRING -> colors.string
                Category.PREPROCESSOR -> colors.preprocessor
                Category.NUMBER -> colors.number
                Category.KEYWORD -> colors.keyword
                Category.TYPE -> colors.type
                Category.FUNCTION -> colors.function
                Category.VARIABLE -> colors.variable
                Category.OPERATOR -> colors.operator
                Category.CONSTANT -> colors.constant
                Category.MEMBER -> colors.member
                Category.BOOLEAN -> colors.boolean
                Category.CLASSNAME -> colors.classname
                Category.TEXT_NORMAL -> null
            }
            if (color != null && token.end > token.start) {
                builder.addStyle(SpanStyle(color = color), token.start, token.end)
            }
        }

        // search-term highlight — ported from AndroidIDE's JavaHighlighter `match` pass
        if (match.isNotBlank()) {
            var idx = text.indexOf(match, 0, ignoreCase = false)
            while (idx >= 0) {
                val end = idx + match.length
                builder.addStyle(
                    SpanStyle(background = Color(0xFFFFFF00), color = Color(0xFF000000)),
                    idx,
                    end,
                )
                idx = text.indexOf(match, end, ignoreCase = false)
            }
        }

        return builder.toAnnotatedString()
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
