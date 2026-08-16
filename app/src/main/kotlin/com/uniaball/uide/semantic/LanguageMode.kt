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

    /** CMake script language (`CMakeLists.txt`). */
    CMAKE,
    ;

    companion object {
        /** Exact file name recognized as a CMake script. */
        private const val CMAKE_LISTS = "CMakeLists.txt"

        /**
         * Detect the language mode for [name] by file name.
         *
         * `CMakeLists.txt` matches exactly (case-sensitive); otherwise the
         * extension decides between C and C++.
         */
        fun detect(name: String): LanguageMode = when {
            name == CMAKE_LISTS -> CMAKE
            name.lowercase().endsWith(".cpp") || name.lowercase().endsWith(".cc") ||
                name.lowercase().endsWith(".cxx") || name.lowercase().endsWith(".c++") ||
                name.lowercase().endsWith(".hpp") || name.lowercase().endsWith(".hxx") ||
                name.lowercase().endsWith(".hh") || name.lowercase().endsWith(".h++") -> CPP
            else -> C
        }
    }
}