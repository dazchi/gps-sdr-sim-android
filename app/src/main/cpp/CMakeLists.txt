cmake_minimum_required(VERSION 3.22.1)
project("livegpssdrsim")

# The whole simulator + JNI bridge. gpssim.c's main() is renamed to
# gpssim_main() via -Dmain=gpssim_main so it can be called from JNI.
add_library(${CMAKE_PROJECT_NAME} SHARED
        native-lib.cpp
        gpssim.c
        hackrf_stub.c)

# -Dmain=... only applies to gpssim.c; the JNI wrapper uses its real symbol.
set_source_files_properties(gpssim.c PROPERTIES
        COMPILE_DEFINITIONS "main=gpssim_main;ANDROID_JNI_STREAMING=1")

target_compile_options(${CMAKE_PROJECT_NAME} PRIVATE
        -O3
        -ffast-math
        -Wno-unused-parameter
        -Wno-unused-variable
        -Wno-unused-but-set-variable
        -Wno-format
        -Wno-parentheses)

target_link_libraries(${CMAKE_PROJECT_NAME}
        android
        log
        m)
