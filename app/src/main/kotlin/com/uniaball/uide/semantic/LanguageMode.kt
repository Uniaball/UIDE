package com.uniaball.uide.semantic

/**
 * Language dialect mode for C/C++ analysis and highlighting.
 *
 * Replaces the `Boolean isCpp` flag used throughout the codebase.  An
 * enum is self-documenting, type-safe, and extensible (e.g. future
 * support for Objective-C, C23, C++26, etc.).
 */
enum class LanguageMode {
    /** C language (C89 / C99 / C11 / C17 / C23). */
    C,

    /** C++ language (C++98 through C++23). */
    CPP,
}
