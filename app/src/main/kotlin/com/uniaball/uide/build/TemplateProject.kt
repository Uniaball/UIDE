package com.uniaball.uide.build

import java.io.File

/** Ready-to-build project skeletons offered from the file list. */
object TemplateProject {

    data class Template(val name: String, val files: Map<String, String>)

    private val HELLO_C = """#include <stdio.h>

int main(void) {
    printf("Hello from UIDE!\n");
    return 0;
}
"""

    private val HELLO_CPP = """#include <cstdio>
#include <string>
#include <vector>

int main() {
    std::vector<std::string> words{"Hello", "from", "UIDE"};
    for (const auto& word : words) {
        std::printf("%s ", word.c_str());
    }
    std::printf("\n");
    return 0;
}
"""

    private val CMAKE_C = """cmake_minimum_required(VERSION 3.20)
project(uide_demo C)

add_executable(uide_demo main.c)
"""

    private val CMAKE_CPP = """cmake_minimum_required(VERSION 3.20)
project(uide_demo CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_executable(uide_demo main.cpp)
"""

    val templates: List<Template> = listOf(
        Template(
            name = "C 示例",
            files = mapOf(
                "CMakeLists.txt" to CMAKE_C,
                "main.c" to HELLO_C,
            ),
        ),
        Template(
            name = "C++ 示例",
            files = mapOf(
                "CMakeLists.txt" to CMAKE_CPP,
                "main.cpp" to HELLO_CPP,
            ),
        ),
    )

    /** Writes [template] into [directory], never overwriting existing files. */
    fun create(directory: File, template: Template): List<File> {
        if (!directory.exists()) directory.mkdirs()
        return template.files.mapNotNull { (name, content) ->
            val target = File(directory, name)
            if (target.exists()) {
                null
            } else {
                target.writeText(content)
                target
            }
        }
    }
}