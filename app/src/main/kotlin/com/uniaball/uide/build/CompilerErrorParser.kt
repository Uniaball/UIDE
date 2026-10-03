package com.uniaball.uide.build

/**
 * Parses clang / ninja / CMake diagnostics so that they can be listed and
 * jumped to from the editor.
 *
 * clang:   /path/to/main.c:12:5: error: use of undeclared identifier 'x'
 * CMake:   CMake Error at /path/CMakeLists.txt:3 (message): ...
 * ninja:   FAILED: CMakeFiles/app.dir/main.c.o
 * linker:  /path/to/CMakeFiles/app.dir/main.c.o: undefined reference to `f'
 */
object CompilerErrorParser {

    private val CLANG_PATTERN =
        Regex("""^(?<file>[^:\n]+\.[ch](?:pp|xx|cc)?):(?<line>\d+):(?<column>\d+):\s*(?<sev>fatal error|error|warning|note):\s*(?<msg>.*)$""")
    private val GCC_PATTERN =
        Regex("""^(?<file>[^\s:]+):(?<line>\d+):(?<column>\d+):\s*(?<sev>error|warning|note):\s*(?<msg>.*)$""")
    private val CMAKE_PATTERN =
        Regex("""CMake Error at (?<file>[^\n:]+):(?<line>\d+)(?::\d+)?(?: \((?<kind>[^)]*)\))?:\s*(?<msg>.*)$""")
    /** Undefined references point at an object file, so there is nothing to jump to. */
    private val LINK_ERROR_PATTERN =
        Regex("""^(?<file>[^\s:]+\.o):\s*(?<msg>.*(?:undefined reference|undefined symbol|relocation truncated).*)$""")

    fun parseAll(output: String): List<BuildIssue> {
        val issues = mutableListOf<BuildIssue>()
        for (raw in output.lineSequence()) {
            val line = raw.trimEnd()
            if (line.isBlank()) continue

            CLANG_PATTERN.find(line)?.let { match ->
                val groups = match.groups
                issues += BuildIssue(
                    file = groups["file"]!!.value,
                    line = groups["line"]!!.value.toIntOrNull() ?: 0,
                    column = groups["column"]!!.value.toIntOrNull() ?: 0,
                    severity = severityOf(groups["sev"]!!.value),
                    message = groups["msg"]!!.value.trim(),
                )
                return@let
            }

            GCC_PATTERN.find(line)?.let { match ->
                val groups = match.groups
                issues += BuildIssue(
                    file = groups["file"]!!.value,
                    line = groups["line"]!!.value.toIntOrNull() ?: 0,
                    column = groups["column"]!!.value.toIntOrNull() ?: 0,
                    severity = severityOf(groups["sev"]!!.value),
                    message = groups["msg"]!!.value.trim(),
                )
                return@let
            }

            CMAKE_PATTERN.find(line)?.let { match ->
                val groups = match.groups
                issues += BuildIssue(
                    file = groups["file"]!!.value,
                    line = groups["line"]!!.value.toIntOrNull() ?: 0,
                    column = 0,
                    severity = BuildIssue.Severity.ERROR,
                    message = groups["msg"]!!.value.trim(),
                )
                return@let
            }

            LINK_ERROR_PATTERN.find(line)?.let { match ->
                val groups = match.groups
                issues += BuildIssue(
                    file = groups["file"]!!.value,
                    line = 0,
                    column = 0,
                    severity = BuildIssue.Severity.ERROR,
                    message = groups["msg"]!!.value.trim(),
                )
            }
        }
        return issues.distinct()
    }

    /** Maps an absolute path reported by the compiler back to the user's file. */
    fun resolveSource(issue: BuildIssue, workspaceRoot: java.io.File): String? {
        val reported = runCatching { java.io.File(issue.file).canonicalPath }.getOrNull()
            ?: return null
        val root = runCatching { workspaceRoot.canonicalPath }.getOrNull() ?: return null
        if (!reported.startsWith("$root${java.io.File.separator}")) return null
        return reported.removePrefix("$root${java.io.File.separator}").replace('\\', '/')
    }

    private fun severityOf(token: String): BuildIssue.Severity = when {
        token.contains("error") -> BuildIssue.Severity.ERROR
        token.contains("warning") -> BuildIssue.Severity.WARNING
        else -> BuildIssue.Severity.NOTE
    }
}