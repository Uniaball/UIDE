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
 */
data class SemanticInfo(
    val declaredVariables: Set<String>,
    val typedefAliases: Set<String>,
) {
    /**
     * Every user-introduced identifier the IDE should be aware of
     * (variables + typedef aliases).  Useful when the consumer just
     * needs "all declared names" without caring about the category.
     */
    val allDeclared: Set<String> get() = declaredVariables + typedefAliases
}
