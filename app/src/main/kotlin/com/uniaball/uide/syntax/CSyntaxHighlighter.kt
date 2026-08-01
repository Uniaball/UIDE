package com.uniaball.uide.syntax

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import com.uniaball.uide.semantic.CSemanticAnalyzer
import com.uniaball.uide.semantic.LanguageMode
import com.uniaball.uide.semantic.SemanticError
import com.uniaball.uide.semantic.TextScanner
import com.uniaball.uide.ui.theme.SyntaxColors

/**
 * C / C++ syntax highlighter.
 *
 * Depends on [CSemanticAnalyzer] (in `com.uniaball.uide.semantic`) for
 * declaration-level information; the analyzer is the **authority** — this
 * highlighter consumes its output.
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

    // ---- vocabulary (sourced from CSemanticAnalyzer.Vocab — single truth) ----
    private val V = CSemanticAnalyzer.Vocab

    private data class Token(val start: Int, val end: Int, val category: Category)

    private val s = TextScanner  // reuse shared scanning primitives

    /**
     * Highlight [text]. Pass [mode] = [LanguageMode.CPP] for C++ sources
     * so the C++ keyword / type vocabulary is used; otherwise C is assumed.
     * [match] is an optional literal search term for background highlight.
     */
    fun highlight(
        text: String,
        colors: SyntaxColors,
        mode: LanguageMode = LanguageMode.C,
        match: String = "",
    ): AnnotatedString {
        val keywords = V.keywords(mode)
        val types = V.types(mode)
        val semantic = CSemanticAnalyzer.analyze(text, mode)
        val tokens = tokenize(text, keywords, types, semantic.allDeclared)
        return paint(text, tokens, colors, match, semantic.errors)
    }

    /** True for file names that should be highlighted as C++. */
    fun isCppFile(name: String): LanguageMode {
        val lower = name.lowercase()
        return if (lower.endsWith(".cpp") || lower.endsWith(".cc") ||
            lower.endsWith(".cxx") || lower.endsWith(".c++") ||
            lower.endsWith(".hpp") || lower.endsWith(".hxx") ||
            lower.endsWith(".hh") || lower.endsWith(".h++")
        ) LanguageMode.CPP else LanguageMode.C
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
                    i = s.skipLineComment(text, i, n)
                    tokens += Token(start, i, Category.COMMENT)
                }

                // block comment  /* ... */
                c == '/' && i + 1 < n && text[i + 1] == '*' -> {
                    val start = i
                    i = s.skipBlockComment(text, i, n)
                    tokens += Token(start, i, Category.COMMENT)
                }

                // string literal: plain "...", encoding-prefixed (L/u8/u/U), or raw R"(...)"
                c == '"' ||
                (c == 'R' && i + 1 < n && text[i + 1] == '"') ||
                ((c == 'L' || c == 'u' || c == 'U') && i + 1 < n && text[i + 1] == '"') ||
                (c == 'u' && i + 2 < n && text[i + 1] == '8' && text[i + 2] == '"') -> {
                    val start = i
                    i = s.skipCStringLiteral(text, i, n)
                    tokens += Token(start, i, Category.STRING)
                }

                // char literal  '...'
                c == '\'' -> {
                    val start = i
                    i = s.skipStringLiteral(text, i, n, '\'')
                    tokens += Token(start, i, Category.STRING)
                }

                // preprocessor directive  #...  (only at line start / after blanks)
                c == '#' -> {
                    val lineStart = text.lastIndexOf('\n', i - 1) + 1
                    val before = if (lineStart < i) text.substring(lineStart, i) else ""
                    if (lineStart == i || before.all { it == ' ' || it == '\t' }) {
                        val start = i
                        i = s.skipPreprocessor(text, i, n)
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

                // member access  .   (obj.field) — tokenize the dot as operator, mark the following identifier
                c == '.' -> {
                    if (i + 1 < n && (text[i + 1].isLetter() || text[i + 1] == '_')) {
                        tokens += Token(i, i + 1, Category.OPERATOR)
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
                    i = s.endOfIdentifier(text, i, n)
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
                        word in V.BOOLEANS -> Category.BOOLEAN
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
                    if (word in V.DECL_KEYWORDS && word in keywords) pendingDecl = true
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
        errors: List<SemanticError> = emptyList(),
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
                    SpanStyle(background = colors.searchMatchBg, color = colors.searchMatchFg),
                    idx,
                    end,
                )
                idx = text.indexOf(match, end, ignoreCase = false)
            }
        }

        // syntax-error spans — marked with string annotations for the
        // drawWithContent wavy-underline pass in EditorScreen.
        for (err in errors) {
            val end = err.end.coerceAtMost(text.length)
            if (end > err.start) {
                builder.addStringAnnotation("uide_error", err.message, err.start, end)
            }
        }

        return builder.toAnnotatedString()
    }

    /** True for macro-like identifiers: ALL_CAPS with at least one letter. */
    private fun String.isAllCaps(): Boolean =
        length >= 2 && any { it.isUpperCase() } && none { it.isLowerCase() }
}
