cmake_minimum_required(VERSION 3.22.1)
project(oneemu LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(oboe REQUIRED CONFIG)

add_library(oneemu SHARED
    core.cpp
    audio.cpp
    video_gl.cpp
    frontend.cpp
    jni.cpp
)
target_include_directories(oneemu PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_options(oneemu PRIVATE -Wall -Wextra -Wno-unused-parameter -fvisibility=hidden)
target_link_libraries(oneemu PRIVATE oboe::oboe android log GLESv3 EGL)
