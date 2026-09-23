cmake_minimum_required(VERSION 3.22.1)

project(octelium_jni C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

add_library(octelium_jni SHARED octelium_jni.c)

target_compile_options(octelium_jni PRIVATE -Wall -Wextra -Werror -fvisibility=hidden)

if(ANDROID)
    target_link_options(octelium_jni PRIVATE "-Wl,-z,max-page-size=16384")
    target_link_libraries(octelium_jni PRIVATE dl)
else()
    find_package(JNI REQUIRED)
    target_include_directories(octelium_jni PRIVATE ${JNI_INCLUDE_DIRS})
    target_link_libraries(octelium_jni PRIVATE dl pthread)
endif()
