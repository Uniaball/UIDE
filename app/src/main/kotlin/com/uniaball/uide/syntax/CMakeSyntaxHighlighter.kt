package com.uniaball.uide.syntax

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import com.uniaball.uide.semantic.SemanticError
import com.uniaball.uide.semantic.TextScanner
import com.uniaball.uide.ui.theme.SyntaxColors

/**
 * CMake syntax highlighter for `CMakeLists.txt` files.
 *
 * Mirrors the architecture of [CSyntaxHighlighter]: a single forward scan
 * **tokenizes** the source into categorized spans, then a paint pass maps
 * each category to a color.  Unlike C/C++, CMake needs no semantic
 * pre-pass — its tokens are fully determined by the grammar itself.
 *
 * Recognized constructs:
 * - line comments  `# ...` (start anywhere outside strings)
 * - quoted strings `"..."` (with `\` escapes)
 * - bracket arguments  `[[ ... ]]`, `[=[ ... ]=]`, ...
 * - numbers
 * - commands  `name( ... )` and flow-control keywords (`if`/`elseif`/...)
 * - variable references  `${var}`, `$ENV{...}` / `$CACHE{...}` — including
 *   references embedded inside quoted strings
 * - condition operators  `AND` / `OR` / `NOT`
 *
 * Unterminated strings, bracket arguments and variable references are
 * reported as [SemanticError]s, which [com.uniaball.uide.ui.EditorScreen]
 * renders as wavy underlines (same behavior as C/C++).
 */
object CMakeSyntaxHighlighter {

    /** Token categories — the bridge between scan and paint. */
    private enum class Category {
        COMMENT, STRING, NUMBER, KEYWORD, FUNCTION, VARIABLE, OPERATOR,
        TEXT_NORMAL,
    }

    /** Flow-control keywords (also command names, but colored as keywords). */
    private val KEYWORDS = setOf(
        "if", "elseif", "else", "endif",
        "while", "endwhile",
        "foreach", "endforeach",
        "function", "endfunction",
        "macro", "endmacro",
        "block", "endblock",
        "return", "break", "continue",
    )

    /** Condition operators used inside `if(...)` expressions. */
    private val OPERATORS = setOf("AND", "OR", "NOT")

    private data class Token(val start: Int, val end: Int, val category: Category)

    private val s = TextScanner  // reuse shared scanning primitives

    /**
     * Highlight [text] as a CMake script.
     * [match] is an optional literal search term for background highlight.
     */
    fun highlight(
        text: String,
        colors: SyntaxColors,
        match: String = "",
    ): AnnotatedString {
        val tokens = tokenize(text)
        return paint(text, tokens, colors, match)
    }

    // ---- scan: source text -> categorized tokens (order-preserving) ----

    private fun tokenize(text: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        val n = text.length

        while (i < n) {
            val c = text[i]
            when {
                // line comment  #...
                c == '#' -> {
                    val start = i
                    i = s.skipLineComment(text, i, n)
                    tokens += Token(start, i, Category.COMMENT)
                }

                // quoted string  "..."  (with \ escapes)
                c == '"' -> {
                    val start = i
                    val inner = mutableListOf<Pair<Int, Int>>()
                    i = scanString(text, i, n, inner)
                    tokens += Token(start, i, Category.STRING)
                    // ${var} inside the string gets its own (overriding) spans
                    for ((vs, ve) in inner) {
                        tokens += Token(vs, ve, Category.VARIABLE)
                    }
                }

                // bracket argument  [[ ... ]] / [=[ ... ]=] / ...
                c == '[' && isBracketOpen(text, i, n) != -1 -> {
                    val start = i
                    i = scanBracket(text, i, n)
                    tokens += Token(start, i, Category.STRING)
                }

                // variable reference  ${var} / $ENV{...} / $CACHE{...}
                c == '$' && i + 1 < n && text[i + 1] == '{' -> {
                    val start = i
                    i = scanVariable(text, i + 1, n)
                    tokens += Token(start, i, Category.VARIABLE)
                }
                c == '$' && i + 1 < n &&
                (text[i + 1].isLetter() || text[i + 1] == '_') -> {
                    val start = i
                    val nameEnd = s.endOfIdentifier(text, i + 1, n)
                    var j = nameEnd
                    while (j < n && text[j].isWhitespace()) j++
                    if (j < n && text[j] == '{') {
                        i = scanVariable(text, j, n)
                        tokens += Token(start, i, Category.VARIABLE)
                    } else {
                        i = nameEnd
                    }
                }

                // number literal
                c.isDigit() -> {
                    val start = i
                    while (i < n && (text[i].isLetterOrDigit() || text[i] == '.' ||
                        text[i] == '_' || text[i] == '+' || text[i] == '-')
                    ) {
                        i++
                    }
                    while (i > start && !text[i - 1].isLetterOrDigit() && text[i - 1] != '_') i--
                    tokens += Token(start, i, Category.NUMBER)
                }

                // identifier: keyword / condition operator / command call
                c.isLetter() || c == '_' -> {
                    val start = i
                    i = s.endOfIdentifier(text, i, n)
                    val word = text.substring(start, i)
                    var j = i
                    while (j < n && text[j].isWhitespace()) j++
                    val isCommand = j < n && text[j] == '('
                    val category = when {
                        word in KEYWORDS -> Category.KEYWORD
                        word in OPERATORS -> Category.OPERATOR
                        isCommand -> Category.FUNCTION
                        else -> Category.TEXT_NORMAL
                    }
                    tokens += Token(start, i, category)
                }

                else -> i++
            }
        }

        return tokens
    }

    /**
     * Scan a `"..."` string from [i] (the opening quote) to its end.
     * Records any `${...}` variable references found inside into [inner]
     * as (start, end) pairs relative to [text].
     */
    private fun scanString(text: String, i: Int, n: Int, inner: MutableList<Pair<Int, Int>>): Int {
        var pos = i + 1
        while (pos < n) {
            when {
                text[pos] == '\\' && pos + 1 < n -> pos += 2      // escape
                text[pos] == '"' -> return pos + 1                // closing quote
                text[pos] == '$' && pos + 1 < n && text[pos + 1] == '{' -> {
                    val vs = pos
                    pos = scanVariable(text, pos + 1, n)
                    inner += vs to pos
                }
                else -> pos++
            }
        }
        return pos  // unterminated — runs to EOF
    }

    /**
     * Scan a bracket argument from [i] (the opening `[`).
     * Caller has verified `[` + (`=`)* + `[` at [i].
     */
    private fun scanBracket(text: String, i: Int, n: Int): Int {
        val eq = isBracketOpen(text, i, n)
        var pos = i + 1 + eq + 1
        while (pos < n) {
            if (text[pos] == ']' && pos + 1 + eq < n) {
                var k = 1
                while (k <= eq && text[pos + k] == '=') k++
                if (k > eq && text[pos + k] == ']') return pos + k + 1
            }
            pos++
        }
        return pos  // unterminated — runs to EOF
    }

    /** Number of `=` in a bracket opener at [i], or -1 if not one. */
    private fun isBracketOpen(text: String, i: Int, n: Int): Int {
        var pos = i + 1
        var eq = 0
        while (pos < n && text[pos] == '=') { eq++; pos++ }
        return if (pos < n && text[pos] == '[') eq else -1
    }

    /**
     * Scan a `{...}` variable reference from [i] (the opening `{`).
     * Supports nesting: `${outer_${inner}}`.
     */
    private fun scanVariable(text: String, i: Int, n: Int): Int {
        var pos = i + 1
        var depth = 1
        while (pos < n) {
            when {
                text[pos] == '{' -> depth++
                text[pos] == '}' -> {
                    depth--
                    if (depth == 0) return pos + 1
                }
            }
            pos++
        }
        return pos  // unterminated — runs to EOF
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
                Category.NUMBER -> colors.number
                Category.KEYWORD -> colors.keyword
                Category.FUNCTION -> colors.function
                Category.VARIABLE -> colors.variable
                Category.OPERATOR -> colors.operator
                Category.TEXT_NORMAL -> null
            }
            if (color != null && token.end > token.start) {
                val span = if (token.category == Category.COMMENT) {
                    SpanStyle(color = color, fontStyle = FontStyle.Italic)
                } else {
                    SpanStyle(color = color)
                }
                builder.addStyle(span, token.start, token.end)
            }
        }

        // search-term highlight — same `match` pass as CSyntaxHighlighter
        if (match.isNotBlank()) {
            var idx = text.indexOf(match, 0, ignoreCase = false)
            while (idx >= 0) {
                val end = idx + match.length
                builder.addStyle(
                    SpanStyle(background = colors.searchMatchBg, color = colors.searchMatchFg),
                    idx,
                    end,
                )
                idx = text.indexOf(match, end, ignoreCase = false)
            }
        }

        // unterminated constructs -> wavy-underline spans
        val errors = collectErrors(text)
        for (err in errors) {
            val end = err.end.coerceAtMost(text.length)
            if (end >= err.start) {
                builder.addStringAnnotation("uide_error", err.message, err.start, end)
            }
        }

        return builder.toAnnotatedString()
    }

    /** Detect unterminated strings, bracket arguments and variable refs. */
    private fun collectErrors(text: String): List<SemanticError> {
        val errors = mutableListOf<SemanticError>()
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            when {
                c == '#' -> i = s.skipLineComment(text, i, n)
                c == '"' -> {
                    val start = i
                    val inner = mutableListOf<Pair<Int, Int>>()
                    val end = scanString(text, i, n, inner)
                    if (end >= n) errors += SemanticError(start, n, "未闭合的字符串")
                    i = end
                }
                c == '[' && isBracketOpen(text, i, n) != -1 -> {
                    val start = i
                    val end = scanBracket(text, i, n)
                    if (end >= n) errors += SemanticError(start, n, "未闭合的括号参数 '[[…]]'")
                    i = end
                }
                c == '$' && i + 1 < n && text[i + 1] == '{' -> {
                    val start = i
                    val end = scanVariable(text, i + 1, n)
                    if (end >= n) errors += SemanticError(start, n, "未闭合的变量引用 '\${'")
                    i = end
                }
                else -> i++
            }
        }
        return errors
    }
}