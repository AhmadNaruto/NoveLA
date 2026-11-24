cmake_minimum_required(VERSION 3.10)

project(lua LANGUAGES C)

set(CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp)

add_library(${PROJECT_NAME} SHARED
    ${CPP_DIR}/src/lapi.c
    ${CPP_DIR}/src/lauxlib.c
    ${CPP_DIR}/src/lbaselib.c
    ${CPP_DIR}/src/lcode.c
    ${CPP_DIR}/src/lcorolib.c
    ${CPP_DIR}/src/lctype.c
    ${CPP_DIR}/src/ldblib.c
    ${CPP_DIR}/src/ldebug.c
    ${CPP_DIR}/src/ldo.c
    ${CPP_DIR}/src/ldump.c
    ${CPP_DIR}/src/lfunc.c
    ${CPP_DIR}/src/lgc.c
    ${CPP_DIR}/src/linit.c
    ${CPP_DIR}/src/liolib.c
    ${CPP_DIR}/src/llex.c
    ${CPP_DIR}/src/lmathlib.c
    ${CPP_DIR}/src/lmem.c
    ${CPP_DIR}/src/loadlib.c
    ${CPP_DIR}/src/lobject.c
    ${CPP_DIR}/src/lopcodes.c
    ${CPP_DIR}/src/loslib.c
    ${CPP_DIR}/src/lparser.c
    ${CPP_DIR}/src/lstate.c
    ${CPP_DIR}/src/lstring.c
    ${CPP_DIR}/src/lstrlib.c
    ${CPP_DIR}/src/ltable.c
    ${CPP_DIR}/src/ltablib.c
    ${CPP_DIR}/src/ltm.c
    ${CPP_DIR}/src/lundump.c
    ${CPP_DIR}/src/lutf8lib.c
    ${CPP_DIR}/src/lvm.c
    ${CPP_DIR}/src/lzio.c
    # Third-party and built-in extensions (JSON, Regex, Util/Crypto, HTML/Lexbor)
    ${CPP_DIR}/src/extensions/json/cJSON.c
    ${CPP_DIR}/src/extensions/json/ljsonlib.c
    ${CPP_DIR}/src/extensions/regex/lregexlib.c
    ${CPP_DIR}/src/extensions/util/aes.c
    ${CPP_DIR}/src/extensions/util/b64_buffer.c
    ${CPP_DIR}/src/extensions/util/b64_encode.c
    ${CPP_DIR}/src/extensions/util/b64_decode.c
    ${CPP_DIR}/src/extensions/util/lutilib.c
    ${CPP_DIR}/src/extensions/html/lexbor.c
    ${CPP_DIR}/src/extensions/html/lhtmllib.c
)

if(ANDROID)
    target_sources(${PROJECT_NAME} PRIVATE ${CPP_DIR}/src/lua_jni.c)
endif()

target_include_directories(${PROJECT_NAME}
    PUBLIC
        ${CPP_DIR}/include
    PRIVATE
        ${CPP_DIR}/src
        ${CPP_DIR}/src/extensions/json
        ${CPP_DIR}/src/extensions/regex
        ${CPP_DIR}/src/extensions/util
        ${CPP_DIR}/src/extensions/html
)

target_compile_definitions(${PROJECT_NAME} PRIVATE
    LUA_USE_LINUX
)

# Compiler & Linker optimizations (dead-code elimination and symbol visibility)
target_compile_options(${PROJECT_NAME} PRIVATE
    -ffunction-sections
    -fdata-sections
    -fvisibility=hidden
    $<$<CONFIG:Release>:-Os>
)

target_link_options(${PROJECT_NAME} PRIVATE
    -Wl,--gc-sections
)

find_library(LIBDL dl)
find_library(LIBLOG log)

set(EXTRA_LIBS m)
if(LIBDL)
    list(APPEND EXTRA_LIBS ${LIBDL})
endif()
if(LIBLOG)
    list(APPEND EXTRA_LIBS ${LIBLOG})
endif()

target_link_libraries(${PROJECT_NAME} PRIVATE ${EXTRA_LIBS})
