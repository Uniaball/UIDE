cmake_minimum_required(VERSION 3.22.1)

project(uidexec C)

add_library(uidexec SHARED uidexec.c uidepty.c)
target_compile_options(uidexec PRIVATE -Wall -Wextra -Os)
target_link_options(uidexec PRIVATE -Wl,--gc-sections)
target_link_libraries(uidexec dl)

add_executable(uidexec-selftest selftest.c)
target_compile_options(uidexec-selftest PRIVATE -Wall -Wextra -Os)
target_link_libraries(uidexec-selftest dl)

add_executable(uidexec-probe probe.c)
target_compile_options(uidexec-probe PRIVATE -Wall -Wextra -Os)

foreach(target uidexec-selftest uidexec-probe)
    set_target_properties(${target} PROPERTIES
        RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/uide-out")
endforeach()
