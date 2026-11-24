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
    # Third-party and built-in extensions (JSON, PCRE2 Regex, Util/Crypto, HTML/Lexbor)
    ${CPP_DIR}/src/extensions/json/cJSON.c
    ${CPP_DIR}/src/extensions/json/ljsonlib.c
    ${CPP_DIR}/src/extensions/pcre2/lpcre2lib.c
    ${CPP_DIR}/src/extensions/pcre2/src/pcre2_auto_possess.c
    ${CPP_DIR}/src/extensions/pcre2/src/pcre2_chartables.c
    ${CPP_DIR}/src/extensions/pcre2/src/pcre2_chkdint.c
    ${CPP_DIR}/src/extensions/pcre2/src/pcre2_compile.c
    ${CPP_DIR}/src/extensions/pcre2/src/pcre2_compile_cgroup.c
    ${CPP_DIR}/src/extensions/pcre2/src/pcre2_compile_class.c
    ${CPP_DIR}/src/extensions/pcre2/src/pcre2_config.c
    ${CPP_DIR}/src/extensions/pcre2/src/pcre2_context.c
    ${CPP_DIR}/src/extensions/pcre2/src/pcre2_convert.c
    ${CPP_DIR}/src/extensions/pcre2/src/pcre2_dfa_match.c
    ${CPP_DIR}/src/extensions/pcre2/src/pcre2_error.c
    ${CPP_DIR}/src/extensions/pcre2/src/pcre2_extuni.c
    ${CPP_DIR}/src/extensions/pcre2/src/pcre2_find_bracket.c
    ${CPP_DIR}/src/extensions/pcre2/src/pcre2_jit_compile.c
    ${CPP_DIR}/src/extensions/pcre2/src/pcre2_maketables.c
    ${CPP_DIR}/src/extensions/pcre2/src/pcre2_match.c
    ${CPP_DIR}/src/extensions/pcre2/src/pcre2_match_data.c
    ${CPP_DIR}/src/extensions/pcre2/src/pcre2_match_next.c
    ${CPP_DIR}/src/extensions/pcre2/src/pcre2_newline.c
    ${CPP_DIR}/src/extensions/pcre2/src/pcre2_ord2utf.c
    ${CPP_DIR}/src/extensions/pcre2/src/pcre2_pattern_info.c
    ${CPP_DIR}/src/extensions/pcre2/src/pcre2_script_run.c
    ${CPP_DIR}/src/extensions/pcre2/src/pcre2_serialize.c
    ${CPP_DIR}/src/extensions/pcre2/src/pcre2_string_utils.c
    ${CPP_DIR}/src/extensions/pcre2/src/pcre2_study.c
    ${CPP_DIR}/src/extensions/pcre2/src/pcre2_substitute.c
    ${CPP_DIR}/src/extensions/pcre2/src/pcre2_substring.c
    ${CPP_DIR}/src/extensions/pcre2/src/pcre2_tables.c
    ${CPP_DIR}/src/extensions/pcre2/src/pcre2_ucd.c
    ${CPP_DIR}/src/extensions/pcre2/src/pcre2_valid_utf.c
    ${CPP_DIR}/src/extensions/pcre2/src/pcre2_xclass.c
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
        ${CPP_DIR}/src/extensions/pcre2
        ${CPP_DIR}/src/extensions/pcre2/src
        ${CPP_DIR}/src/extensions/util
        ${CPP_DIR}/src/extensions/html
)

target_compile_definitions(${PROJECT_NAME} PRIVATE
    LUA_USE_LINUX
    HAVE_CONFIG_H
    PCRE2_CODE_UNIT_WIDTH=8
    SUPPORT_UNICODE=1
)

# Compiler & Linker optimizations for ARM64 (high-performance vectorization, LTO, 16KB page alignment)
target_compile_options(${PROJECT_NAME} PRIVATE
    -ffunction-sections
    -fdata-sections
    -fvisibility=hidden
    $<$<CONFIG:Release>:-O3>
    $<$<CONFIG:Release>:-flto=thin>
    $<$<CONFIG:Release>:-fno-semantic-interposition>
    $<$<CONFIG:Release>:-fomit-frame-pointer>
    $<$<CONFIG:Release>:-fno-math-errno>
)

target_link_options(${PROJECT_NAME} PRIVATE
    -Wl,--gc-sections
    -Wl,-z,max-page-size=16384
    $<$<CONFIG:Release>:-flto=thin>
    $<$<CONFIG:Release>:-Wl,--strip-all>
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
