package com.uniaball.uide.syntax

/**
 * Lightweight C / C++ syntax checker for IDE features.
 *
 * Scans source text and collects semantic information that the IDE can use
 * for syntax highlighting, autocomplete suggestions, outline navigation,
 * and light linting — all without a full compiler frontend.
 *
 * Current capabilities:
 * - **Declared variable detection** via [findDeclared]: identifies every
 *   variable-like name introduced by a declaration pattern (e.g.
 *   `int x;`, `const char* msg;`, `vector<int> v;`, `MyClass obj;`,
 *   `struct Foo s;`, comma-separated lists, function parameters).
 *   The returned set is used by the highlighter to colour declared variables
 *   differently from random typing, and can also drive autocomplete scoping
 *   or unused-variable warnings.
 *
 * Future expansion points (same scan infrastructure):
 * - Collect function signatures for outline / go-to-definition
 * - Detect undefined identifiers for light linting
 * - Extract `#include` / import directives for dependency tracking
 */
object MiniSyntaxChecker {

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

    /**
     * Scan [text] for variable-like declarations and return the set of
     * declared names.  When [isCpp] is `true`, C++ keywords and types
     * (e.g. `class`, `typename`, `string`) are recognised; otherwise
     * only the C subset is used.
     *
     * Recognised declaration patterns:
     * ```
     * TYPE name;              TYPE name = expr;
     * TYPE name, name2;       const TYPE* name;
     * struct Tag name;        MyClass obj;
     * TYPE name[10];          TYPE& name;
     * vector<T> v;            TYPE (*fp)(...);
     * ```
     * Function parameters (inside parentheses) and for-loop init
     * declarators are also collected.
     */
    fun findDeclared(text: String, isCpp: Boolean): Set<String> {
        val declared = mutableSetOf<String>()
        var i = 0; val n = text.length
        var afterType = false
        var inDecl = false
        val structKeys = if (isCpp) STRUCT_CPP else STRUCT_C

        fun skipWs() { while (i < n && text[i].isWhitespace()) i++ }

        while (i < n) {
            skipWs()
            if (i >= n) break
            val c = text[i]

            when {
                // ---- comments ----
                c == '/' && i + 1 < n && text[i + 1] == '/' -> {
                    while (i < n && text[i] != '\n') i++
                }
                c == '/' && i + 1 < n && text[i + 1] == '*' -> {
                    i += 2
                    while (i < n && !(text[i] == '*' && i + 1 < n && text[i + 1] == '/')) i++
                    if (i < n) i += 2
                }
                // ---- strings / chars ----
                c == '"' || c == '\'' -> {
                    val delim = c; i++
                    while (i < n) {
                        if (text[i] == '\\') { i += 2; continue }
                        if (text[i] == delim) { i++; break }
                        i++
                    }
                }
                // ---- preprocessor ----
                c == '#' -> {
                    while (i < n && text[i] != '\n') i++
                }
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
                // ---- assignment — keep inDecl so  int x = 1, y = 2;  works ----
                c == '=' -> { afterType = false; i++ }
                // ---- scope ::   (consume ::name — may be a type like std::string) ----
                c == ':' && i + 1 < n && text[i + 1] == ':' -> {
                    i += 2; skipWs()
                    while (i < n && (text[i].isLetterOrDigit() || text[i] == '_')) i++
                }
                // ---- template args  <...> ----
                c == '<' && (afterType || inDecl) -> {
                    i++; var depth = 1
                    while (i < n && depth > 0) {
                        when {
                            text[i] == '<' -> { depth++; i++ }
                            text[i] == '>' -> { depth--; i++ }
                            text[i] == '/' && i + 1 < n && text[i + 1] == '/' -> {
                                while (i < n && text[i] != '\n') i++
                            }
                            text[i] == '/' && i + 1 < n && text[i + 1] == '*' -> {
                                i += 2
                                while (i < n && !(text[i] == '*' && i + 1 < n && text[i + 1] == '/')) i++
                                if (i < n) i += 2
                            }
                            else -> i++
                        }
                    }
                }
                // ---- identifier ----
                c.isLetter() || c == '_' -> {
                    val start = i
                    while (i < n && (text[i].isLetterOrDigit() || text[i] == '_')) i++
                    val word = text.substring(start, i)

                    when {
                        word in TYPE_INTRO || word in MODIFIERS -> {
                            afterType = true; inDecl = false
                        }
                        word == "typedef" -> {
                            afterType = false; inDecl = false
                            while (i < n && text[i] != ';') i++
                            if (i < n) i++
                        }
                        word in structKeys -> {
                            afterType = true; inDecl = false
                            skipWs()
                            if (i < n && (text[i].isLetter() || text[i] == '_'))
                                while (i < n && (text[i].isLetterOrDigit() || text[i] == '_')) i++
                        }
                        word.isNotEmpty() && word[0].isUpperCase() -> {
                            // Potential user type (PascalCase, e.g. "MyClass")
                            val saved = i; skipWs()
                            if (i < n && (text[i] == '*' || text[i] == '&')) { i++; skipWs() }
                            if (i < n && (text[i].isLetter() || text[i] == '_')) {
                                val ps = i
                                while (i < n && (text[i].isLetterOrDigit() || text[i] == '_')) i++
                                if (text.substring(ps, i) !in TYPE_INTRO &&
                                    text.substring(ps, i) !in MODIFIERS) {
                                    afterType = true; inDecl = false
                                    i = ps  // rewind: next iteration collects the identifier
                                    continue
                                }
                            }
                            i = saved
                            if (afterType || inDecl) {
                                declared += word; inDecl = true; afterType = false
                            }
                        }
                        afterType || inDecl -> {
                            // Peek: if next non-ws is '(' it's a function
                            // declaration, not a variable — skip the name.
                            var peek = i
                            while (peek < n && text[peek].isWhitespace()) peek++
                            if (peek < n && text[peek] == '(') {
                                afterType = false; inDecl = false
                            } else {
                                declared += word; inDecl = true; afterType = false
                            }
                        }
                        else -> { afterType = false; inDecl = false }
                    }
                }
                else -> i++
            }
        }
        return declared
    }
}
