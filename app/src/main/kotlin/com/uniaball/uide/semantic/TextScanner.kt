package com.uniaball.uide.semantic

/**
 * Pure, stateless character-scanning utilities for C/C++ source text.
 *
 * Every function advances a position index past a recognised syntactic
 * construct and returns the new index.  Callers use the returned position
 * to either skip noise (semantic analysis) or capture token boundaries
 * (syntax highlighting).
 *
 * This is the **single source of truth** for low-level scans — both
 * [CSemanticAnalyzer] and the syntax highlighter consume it.
 */
object TextScanner {

    /** Advance [i] past whitespace characters. */
    fun skipWs(text: String, i: Int, n: Int): Int {
        var pos = i
        while (pos < n && text[pos].isWhitespace()) pos++
        return pos
    }

    /** Advance [i] past a `//` line comment. Caller has verified `//` at [i]. */
    fun skipLineComment(text: String, i: Int, n: Int): Int {
        var pos = i
        while (pos < n && text[pos] != '\n') pos++
        return pos
    }

    /** Advance [i] past a `/* ... */` block comment. Caller has verified `/ *` at [i]. */
    fun skipBlockComment(text: String, i: Int, n: Int): Int {
        var pos = i + 2
        while (pos < n && !(text[pos] == '*' && pos + 1 < n && text[pos + 1] == '/')) pos++
        return if (pos < n) pos + 2 else n
    }

    /** Advance [i] past a `"..."` or `'...'` literal. [delim] is the opening quote. */
    fun skipStringLiteral(text: String, i: Int, n: Int, delim: Char): Int {
        var pos = i + 1
        while (pos < n) {
            if (text[pos] == '\\') { pos += 2; continue }
            if (text[pos] == delim) { pos++; break }
            pos++
        }
        return pos
    }

    /** Advance [i] past a preprocessor `#...` line. */
    fun skipPreprocessor(text: String, i: Int, n: Int): Int {
        var pos = i
        while (pos < n && text[pos] != '\n') pos++
        return pos
    }

    /**
     * Scan [text] from [i] and return the end position of the identifier
     * that starts at [i] (i.e. a contiguous run of `[a-zA-Z0-9_$]`).
     * Does not advance [i] for the caller; the caller must update its own
     * index if desired.
     */
    fun endOfIdentifier(text: String, i: Int, n: Int): Int {
        var pos = i
        while (pos < n && (text[pos].isLetterOrDigit() || text[pos] == '_' || text[pos] == '$')) pos++
        return pos
    }

    /**
     * Scan [text] from [i] and return the end position of an identifier
     * (C/C++ identifier chars only: `[a-zA-Z0-9_]`, no `$`).
     */
    fun endOfCIdentifier(text: String, i: Int, n: Int): Int {
        var pos = i
        while (pos < n && (text[pos].isLetterOrDigit() || text[pos] == '_')) pos++
        return pos
    }

    /**
     * Advance [i] past a C/C++ string or char literal, including encoding
     * prefixes (`L"`, `u"`, `U"`, `u8"`) and raw strings (`R"(...)"`).
     * Caller has verified that [text[i]] is a valid literal start character
     * (`"`, `'`, `L`, `u`, `U`, `R`).
     */
    fun skipCStringLiteral(text: String, i: Int, n: Int): Int {
        val c = text[i]
        var pos = i
        val isChar = c == '\''
        // consume optional prefix
        when {
            c == '\'' -> pos += 1                    // char literal '...'
            c == '"' -> pos += 1                     // plain "..."
            c == 'R' -> pos += 2                     // R"(...)
            c == 'u' && i + 2 < n && text[i + 1] == '8' -> pos += 3  // u8"(...)
            else -> pos += 2                         // L"(...) / u"(...) / U"(...)
        }
        if (!isChar && c == 'R' && pos < n && text[pos] == '(') {
            // raw string: read until closing )"
            pos++
            while (pos < n) {
                if (text[pos] == ')' && pos + 1 < n && text[pos + 1] == '"') { pos += 2; break }
                pos++
            }
        } else {
            val delim = if (isChar) '\'' else '"'
            while (pos < n) {
                if (text[pos] == '\\' && pos + 1 < n) { pos += 2; continue }
                if (text[pos] == delim) { pos++; break }
                pos++
            }
        }
        return pos
    }
}
