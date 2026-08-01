package com.uniaball.uide.semantic

/**
 * Structured result of a semantic analysis pass over C/C++ source text.
 *
 * This type is the public API contract of [CSemanticAnalyzer].  Consumers
 * (syntax highlighter, autocomplete, linter, outline) depend on this type
 * — the analyzer **never** depends on its consumers.
 *
 * @property declaredVariables variable-like names introduced by declarations
 *   (e.g. `int x;` → `"x"`, `MyClass obj;` → `"obj"`).
 * @property typedefAliases new type names introduced by `typedef`
 *   (e.g. `typedef unsigned long long uint64_t;` → `"uint64_t"`).
 * @property errors syntax-level problems detected during the scan
 *   (unterminated comments/strings, mismatched braces, etc.).
 */
data class SemanticInfo(
    val declaredVariables: Set<String>,
    val typedefAliases: Set<String>,
    val errors: List<SemanticError> = emptyList(),
) {
    /**
     * Every user-introduced identifier the IDE should be aware of
     * (variables + typedef aliases).  Useful when the consumer just
     * needs "all declared names" without caring about the category.
     */
    val allDeclared: Set<String> get() = declaredVariables + typedefAliases

    /** Convenience: true when [errors] is non-empty. */
    val hasErrors: Boolean get() = errors.isNotEmpty()
}

/**
 * A syntax-level problem detected during light-weight scanning.
 *
 * @property start  character index (inclusive) where the problem begins.
 * @property end    character index (exclusive) where the problem ends.
 *                  For point errors (e.g. extra brace) start == end - 1.
 * @property message human-readable description shown to the user.
 */
data class SemanticError(
    val start: Int,
    val end: Int,
    val message: String,
)
