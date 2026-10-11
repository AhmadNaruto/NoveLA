cmake_minimum_required(VERSION 3.22.1)

project("lexbor_native" C CXX)

set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(LEXBOR_BUILD_SHARED OFF CACHE BOOL "" FORCE)
set(LEXBOR_BUILD_STATIC ON CACHE BOOL "" FORCE)
set(LEXBOR_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LEXBOR_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LEXBOR_INSTALL_HEADERS OFF CACHE BOOL "" FORCE)

add_subdirectory(lexbor)

add_library(
    lexbor_jni
    SHARED
    lexbor_jni.c
)

target_include_directories(
    lexbor_jni PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/lexbor/source
)

find_library(log-lib log)

if(log-lib)
    target_link_libraries(
        lexbor_jni
        lexbor_static
        ${log-lib}
    )
else()
    target_link_libraries(
        lexbor_jni
        lexbor_static
    )
endif()
