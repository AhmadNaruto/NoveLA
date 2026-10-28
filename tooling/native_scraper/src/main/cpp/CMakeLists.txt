cmake_minimum_required(VERSION 3.22.1)

project("native_scraper" C CXX)

set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Configure Lexbor
set(LEXBOR_OPTIMIZATION_LEVEL "-O3" CACHE STRING "" FORCE)
set(LEXBOR_BUILD_SHARED OFF CACHE BOOL "" FORCE)
set(LEXBOR_BUILD_STATIC ON CACHE BOOL "" FORCE)
set(LEXBOR_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LEXBOR_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LEXBOR_INSTALL_HEADERS OFF CACHE BOOL "" FORCE)

add_subdirectory(lexbor)

# Configure Lua 5.4 static library
file(GLOB LUA_SOURCES "lua/*.c")
add_library(lua_static STATIC ${LUA_SOURCES})
target_compile_definitions(lua_static PRIVATE LUA_USE_POSIX)
target_include_directories(lua_static PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/lua)

# Main Native Scraper shared library
add_library(
    native_scraper
    SHARED
    lexbor_jni.c
    lua_lexbor.c
    lua_jni.c
)

target_include_directories(
    native_scraper PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/lua
    ${CMAKE_CURRENT_SOURCE_DIR}/lexbor/source
)

find_library(log-lib log)

if(log-lib)
    target_link_libraries(
        native_scraper
        lexbor_static
        lua_static
        m
        ${log-lib}
    )
else()
    target_link_libraries(
        native_scraper
        lexbor_static
        lua_static
        m
    )
endif()
