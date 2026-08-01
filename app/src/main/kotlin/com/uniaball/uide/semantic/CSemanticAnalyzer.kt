package com.uniaball.uide.semantic

/**
 * Lightweight C / C++ semantic analyzer — the **authoritative source** of
 * declaration-level information about source text.
 *
 * Other IDE features (syntax highlighter, autocomplete, linter, outline
 * navigator) consume [SemanticInfo] produced by this analyzer.  The analyzer
 * itself has zero project dependencies — it only uses Kotlin stdlib.
 *
 * ## Capabilities
 *
 * - **Declared variable detection**: identifies every variable-like name
 *   introduced by a declaration pattern (e.g. `int x;`, `const char* msg;`,
 *   `vector<int> v;`, `MyClass obj;`, `struct Foo s;`, comma-separated
 *   lists, function parameters).  Result → [SemanticInfo.declaredVariables].
 *
 * - **typedef alias capture**: extracts the new type name from `typedef`
 *   statements (e.g. `typedef unsigned long long uint64_t;` → `"uint64_t"`).
 *   Result → [SemanticInfo.typedefAliases].
 *
 * ## Design
 *
 * The analyzer is **stateless and self-contained**.  The [analyze] method
 * takes plain source text + a language-mode flag and returns a typed
 * [SemanticInfo] — nothing more.  Consumers decide how to use the result;
 * the analyzer does not know or care about them.
 *
 * ## Future expansion
 *
 * The single-pass scan infrastructure can be extended to collect:
 * - Function signatures (for outline / go-to-definition)
 * - `#include` directives (for dependency tracking)
 * - Undefined-identifier warnings (for light linting)
 */
object CSemanticAnalyzer {

    // ---- type-introducing words ----
    private val TYPE_INTRO = setOf(
        "int", "char", "float", "double", "void", "bool", "auto",
        "long", "short", "wchar_t", "char8_t", "char16_t", "char32_t",
        "size_t", "int8_t", "int16_t", "int32_t", "int64_t",
        "uint8_t", "uint16_t", "uint32_t", "uint64_t",
        "ssize_t", "ptrdiff_t", "time_t", "FILE",
        "string", "string_view", "vector", "map", "set", "typename",
    )

    // ---- type modifiers (can precede the type word) ----
    private val MODIFIERS = setOf(
        "const", "unsigned", "signed", "static", "extern", "volatile",
        "register", "inline", "virtual", "mutable",
        "constexpr", "consteval", "constinit", "thread_local",
    )

    // ---- struct / class / enum / union ----
    private val STRUCT_C = setOf("struct", "enum", "union")
    private val STRUCT_CPP = setOf("struct", "class", "enum", "union")

    // ---- scanning helpers (delegated to [TextScanner] for reuse) ----

    private val s = TextScanner  // short alias for readability

    // ---- public vocabulary (single source of truth for all C/C++ keywords / types) ----

    /**
     * C/C++ keyword and type vocabulary shared across the IDE.
     *
     * [CSyntaxHighlighter] obtains its keyword/type sets from here so
     * that vocabulary is defined once and consumed everywhere.
     */
    object Vocab {
        // ---- C base vocabulary ----

        val C_KEYWORDS = setOf(
            "auto", "break", "case", "char", "const", "continue", "default", "do",
            "double", "else", "enum", "extern", "float", "for", "goto", "if",
            "inline", "int", "long", "register", "restrict", "return", "short",
            "signed", "sizeof", "static", "struct", "switch", "typedef", "union",
            "unsigned", "void", "volatile", "while",
            "_Bool", "_Complex", "_Imaginary",
        )

        val C_TYPES = setOf(
            "int", "char", "float", "double", "void", "long", "short",
            "unsigned", "signed", "bool", "size_t",
            "int8_t", "int16_t", "int32_t", "int64_t",
            "uint8_t", "uint16_t", "uint32_t", "uint64_t", "wchar_t",
        )

        // ---- C++ extras ----

        val CPP_EXTRA_KEYWORDS = setOf(
            "alignas", "alignof", "asm", "bool", "catch", "class", "compl",
            "concept", "const_cast", "consteval", "constexpr", "constinit",
            "decltype", "delete", "dynamic_cast", "explicit", "export", "false",
            "friend", "mutable", "namespace", "new", "noexcept", "nullptr",
            "operator", "private", "protected", "public", "reinterpret_cast",
            "requires", "static_assert", "static_cast", "template", "this",
            "thread_local", "throw", "true", "try", "typeid", "typename", "using",
            "virtual", "wchar_t", "co_await", "co_return", "co_yield", "char8_t",
            "char16_t", "char32_t",
            "and", "and_eq", "bitand", "bitor", "not", "not_eq", "or", "or_eq",
            "xor", "xor_eq",
        )

        val CPP_EXTRA_TYPES = setOf(
            "char8_t", "char16_t", "char32_t", "nullptr_t",
            "string", "string_view", "vector", "map", "set", "list", "array",
            "pair", "tuple", "queue", "stack", "deque", "bitset",
            "unordered_map", "unordered_set", "initializer_list",
            "shared_ptr", "unique_ptr", "weak_ptr",
            "iostream", "ostream", "istream", "stringstream", "ofstream",
            "ifstream", "complex", "valarray", "atomic",
        )

        // ---- mode-dependent accessors ----

        fun keywords(mode: LanguageMode): Set<String> = when (mode) {
            LanguageMode.C -> C_KEYWORDS
            LanguageMode.CPP -> C_KEYWORDS + CPP_EXTRA_KEYWORDS
        }

        fun types(mode: LanguageMode): Set<String> = when (mode) {
            LanguageMode.C -> C_TYPES
            LanguageMode.CPP -> C_TYPES + CPP_EXTRA_TYPES
        }

        // ---- shared word sets ----

        val BOOLEANS = setOf(
            "true", "false", "TRUE", "FALSE", "NULL", "nullptr",
        )

        val DECL_KEYWORDS = setOf(
            "class", "struct", "namespace", "enum", "union", "typedef",
        )
    }

    /**
     * Perform a single-pass semantic scan of [text] and return a typed
     * [SemanticInfo] with all discovered declaration-level information.
     *
     * @param mode [LanguageMode.C] for C-only vocabulary, [LanguageMode.CPP]
     *   for C++ (recognises `class`, `typename`, `string`, etc.).
     */
    fun analyze(text: String, mode: LanguageMode): SemanticInfo {
        val variables = mutableSetOf<String>()
        val typedefs = mutableSetOf<String>()
        var i = 0; val n = text.length
        var afterType = false
        var inDecl = false
        val structKeys = when (mode) {
            LanguageMode.C -> STRUCT_C
            LanguageMode.CPP -> STRUCT_CPP
        }

        while (i < n) {
            i = s.skipWs(text, i, n)
            if (i >= n) break
            val c = text[i]

            when {
                // ---- comments ----
                c == '/' && i + 1 < n && text[i + 1] == '/' ->
                    i = s.skipLineComment(text, i, n)
                c == '/' && i + 1 < n && text[i + 1] == '*' ->
                    i = s.skipBlockComment(text, i, n)

                // ---- strings / chars ----
                c == '"' || c == '\'' ->
                    i = s.skipStringLiteral(text, i, n, c)

                // ---- preprocessor ----
                c == '#' ->
                    i = s.skipPreprocessor(text, i, n)

                // ---- terminators (full reset) ----
                c == ';' || c == '{' || c == '}' || c == '(' || c == ')' ||
                c == '[' || c == ':' -> {
                    afterType = false; inDecl = false; i++
                }

                // ---- comma: more declarations follow ----
                c == ',' -> {
                    if (inDecl) { afterType = true; inDecl = false }
                    else afterType = false
                    i++
                }

                // ---- pointer / reference ----
                c == '*' || c == '&' -> { i++ }

                // ---- assignment — keep inDecl so `int x = 1, y = 2;` works ----
                c == '=' -> { afterType = false; i++ }

                // ---- scope ::   (consume ::name — may be a type like std::string) ----
                c == ':' && i + 1 < n && text[i + 1] == ':' -> {
                    i = s.skipWs(text, i + 2, n)
                    i = s.endOfCIdentifier(text, i, n)
                }

                // ---- template args  <...> ----
                c == '<' && (afterType || inDecl) -> {
                    i++; var depth = 1
                    while (i < n && depth > 0) {
                        when {
                            text[i] == '<' -> { depth++; i++ }
                            text[i] == '>' -> { depth--; i++ }
                            text[i] == '/' && i + 1 < n && text[i + 1] == '/' ->
                                i = s.skipLineComment(text, i, n)
                            text[i] == '/' && i + 1 < n && text[i + 1] == '*' ->
                                i = s.skipBlockComment(text, i, n)
                            else -> i++
                        }
                    }
                }

                // ---- identifier ----
                c.isLetter() || c == '_' -> {
                    val start = i
                    i = s.endOfCIdentifier(text, i, n)
                    val word = text.substring(start, i)
                    val r = processIdentifier(word, i, n, text, structKeys, afterType, inDecl, variables, typedefs)
                    i = r.nextI
                    afterType = r.afterType
                    inDecl = r.inDecl
                }
                else -> i++
            }
        }
        return SemanticInfo(
            declaredVariables = variables,
            typedefAliases = typedefs,
        )
    }

    // ---- identifier classification ----

    /** Result state returned by [processIdentifier] for the main loop to consume. */
    private data class IdResult(
        val nextI: Int,
        val afterType: Boolean,
        val inDecl: Boolean,
    )

    /**
     * Classify [word] in its declaration context and update [variables] /
     * [typedefs] accordingly.  Returns updated scan position and state flags
     * for the caller to resume the main loop.
     */
    private fun processIdentifier(
        word: String, i: Int, n: Int, text: String,
        structKeys: Set<String>,
        afterType: Boolean, inDecl: Boolean,
        variables: MutableSet<String>,
        typedefs: MutableSet<String>,
    ): IdResult {
        var pos = i
        var at = afterType
        var id = inDecl

        when {
            word in TYPE_INTRO || word in MODIFIERS -> {
                at = true; id = false
            }
            word == "typedef" -> {
                at = false; id = false
                var lastIdent = ""
                while (pos < n && text[pos] != ';') {
                    if (text[pos].isLetter() || text[pos] == '_') {
                        val start = pos
                        pos = s.endOfCIdentifier(text, pos, n)
                        lastIdent = text.substring(start, pos)
                    } else {
                        pos++
                    }
                }
                if (lastIdent.isNotEmpty()) {
                    typedefs += lastIdent
                }
                if (pos < n) pos++
            }
            word in structKeys -> {
                at = true; id = false
                pos = s.skipWs(text, pos, n)
                if (pos < n && (text[pos].isLetter() || text[pos] == '_'))
                    pos = s.endOfCIdentifier(text, pos, n)
            }
            word.isNotEmpty() && word[0].isUpperCase() -> {
                // Known limitation: PascalCase words are always treated as
                // type introducers — see class-level KDoc for details.
                val saved = pos
                pos = s.skipWs(text, pos, n)
                if (pos < n && (text[pos] == '*' || text[pos] == '&')) {
                    pos++; pos = s.skipWs(text, pos, n)
                }
                if (pos < n && (text[pos].isLetter() || text[pos] == '_')) {
                    val ps = pos
                    pos = s.endOfCIdentifier(text, pos, n)
                    if (text.substring(ps, pos) !in TYPE_INTRO &&
                        text.substring(ps, pos) !in MODIFIERS) {
                        at = true; id = false
                        // Return with pos rewound so caller re-collects the identifier
                        return IdResult(nextI = ps, afterType = at, inDecl = id)
                    }
                }
                pos = saved
                if (at || id) {
                    variables += word; id = true; at = false
                }
            }
            at || id -> {
                var peek = pos
                while (peek < n && text[peek].isWhitespace()) peek++
                if (peek < n && text[peek] == '(') {
                    at = false; id = false
                } else {
                    variables += word; id = true; at = false
                }
            }
            else -> { at = false; id = false }
        }

        return IdResult(nextI = pos, afterType = at, inDecl = id)
    }
}
