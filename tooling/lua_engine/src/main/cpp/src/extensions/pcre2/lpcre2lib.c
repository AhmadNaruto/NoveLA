#define lpcre2lib_c
#define LUA_LIB

#include "lprefix.h"
#include <stdlib.h>
#include <string.h>
#include <pthread.h>

#define PCRE2_CODE_UNIT_WIDTH 8
#include "pcre2.h"

#include "lua.h"
#include "lauxlib.h"
#include "lualib.h"

#define REGEX_CACHE_SIZE 64

typedef struct {
    char *pattern;
    size_t pat_len;
    pcre2_code *code;
} regex_cache_entry_t;

static regex_cache_entry_t s_regex_cache[REGEX_CACHE_SIZE];
static int s_cache_head = 0;
static pthread_mutex_t s_cache_mutex = PTHREAD_MUTEX_INITIALIZER;

static pcre2_code *get_cached_regex(const char *pat, size_t pat_len) {
    if (!pat || pat_len == 0) return NULL;

    pthread_mutex_lock(&s_cache_mutex);
    for (int i = 0; i < REGEX_CACHE_SIZE; i++) {
        if (s_regex_cache[i].code &&
            s_regex_cache[i].pat_len == pat_len &&
            memcmp(s_regex_cache[i].pattern, pat, pat_len) == 0) {
            pcre2_code *cached = s_regex_cache[i].code;
            pthread_mutex_unlock(&s_cache_mutex);
            return cached;
        }
    }

    int errornumber = 0;
    PCRE2_SIZE erroroffset = 0;
    pcre2_code *re = pcre2_compile(
        (PCRE2_SPTR)pat,
        pat_len,
        PCRE2_UTF | PCRE2_UCP,
        &errornumber,
        &erroroffset,
        NULL
    );

    if (!re) {
        pthread_mutex_unlock(&s_cache_mutex);
        return NULL;
    }

    int slot = s_cache_head;
    s_cache_head = (s_cache_head + 1) % REGEX_CACHE_SIZE;

    if (s_regex_cache[slot].code) {
        pcre2_code_free(s_regex_cache[slot].code);
        free(s_regex_cache[slot].pattern);
        s_regex_cache[slot].code = NULL;
        s_regex_cache[slot].pattern = NULL;
    }

    s_regex_cache[slot].pattern = (char *)malloc(pat_len + 1);
    if (s_regex_cache[slot].pattern) {
        memcpy(s_regex_cache[slot].pattern, pat, pat_len);
        s_regex_cache[slot].pattern[pat_len] = '\0';
        s_regex_cache[slot].pat_len = pat_len;
        s_regex_cache[slot].code = re;
    }

    pthread_mutex_unlock(&s_cache_mutex);
    return re;
}

/*
 * regex_match(text, pattern) -> table of matched substrings
 * Compatible with NoveLA & Shosetsu Regex(pattern).findAll(text).
 */
static int l_regex_match(lua_State *L) {
    size_t text_len = 0, pat_len = 0;
    const char *text = luaL_checklstring(L, 1, &text_len);
    const char *pat = luaL_checklstring(L, 2, &pat_len);

    lua_newtable(L);
    if (!text || !pat || pat_len == 0) {
        return 1;
    }

    pcre2_code *re = get_cached_regex(pat, pat_len);
    if (!re) {
        return 1; /* Return empty table on invalid regex */
    }

    pcre2_match_data *match_data = pcre2_match_data_create_from_pattern(re, NULL);
    if (!match_data) {
        return 1;
    }

    PCRE2_SIZE offset = 0;
    int count = 0;

    while (offset <= text_len) {
        int rc = pcre2_match(re, (PCRE2_SPTR)text, text_len, offset, 0, match_data, NULL);
        if (rc < 0) {
            break;
        }

        PCRE2_SIZE *ovector = pcre2_get_ovector_pointer(match_data);
        PCRE2_SIZE start = ovector[0];
        PCRE2_SIZE end = ovector[1];

        if (start != PCRE2_UNSET && end != PCRE2_UNSET && end >= start) {
            count++;
            lua_pushlstring(L, text + start, end - start);
            lua_rawseti(L, -2, count);
        }

        if (end > offset) {
            offset = end;
        } else {
            offset++;
        }
    }

    pcre2_match_data_free(match_data);
    return 1;
}

/*
 * regex_replace(text, pattern, replacement) -> replaced string
 * Compatible with NoveLA & Shosetsu text.replace(Regex(pattern), replacement).
 */
static int l_regex_replace(lua_State *L) {
    size_t text_len = 0, pat_len = 0, repl_len = 0;
    const char *text = luaL_checklstring(L, 1, &text_len);
    const char *pat = luaL_checklstring(L, 2, &pat_len);
    const char *repl = luaL_checklstring(L, 3, &repl_len);

    if (!text || !pat || pat_len == 0) {
        lua_pushvalue(L, 1);
        return 1;
    }

    pcre2_code *re = get_cached_regex(pat, pat_len);
    if (!re) {
        /* On invalid regex pattern, safely return original string unchanged */
        lua_pushvalue(L, 1);
        return 1;
    }

    pcre2_match_data *match_data = pcre2_match_data_create_from_pattern(re, NULL);
    if (!match_data) {
        lua_pushvalue(L, 1);
        return 1;
    }

    char stack_buf[1024];
    size_t out_cap = text_len * 2 + repl_len + 128;
    char *out_buf = (out_cap <= sizeof(stack_buf)) ? stack_buf : (char *)malloc(out_cap);
    if (!out_buf) {
        pcre2_match_data_free(match_data);
        lua_pushvalue(L, 1);
        return 1;
    }

    PCRE2_SIZE out_len = out_cap;
    uint32_t sub_options = PCRE2_SUBSTITUTE_GLOBAL | PCRE2_SUBSTITUTE_EXTENDED |
                           PCRE2_SUBSTITUTE_UNSET_EMPTY | PCRE2_SUBSTITUTE_OVERFLOW_LENGTH;
    int rc = pcre2_substitute(
        re,
        (PCRE2_SPTR)text,
        text_len,
        0,
        sub_options,
        match_data,
        NULL,
        (PCRE2_SPTR)repl,
        repl_len,
        (PCRE2_UCHAR *)out_buf,
        &out_len
    );

    if (rc == PCRE2_ERROR_NOMEMORY) {
        if (out_buf != stack_buf) free(out_buf);
        out_cap = out_len;
        out_buf = (char *)malloc(out_cap);
        if (out_buf) {
            rc = pcre2_substitute(
                re,
                (PCRE2_SPTR)text,
                text_len,
                0,
                sub_options,
                match_data,
                NULL,
                (PCRE2_SPTR)repl,
                repl_len,
                (PCRE2_UCHAR *)out_buf,
                &out_len
            );
        }
    }

    if (rc >= 0 && out_buf) {
        lua_pushlstring(L, out_buf, out_len);
    } else {
        /* On replacement error, safely return original string */
        lua_pushvalue(L, 1);
    }

    if (out_buf && out_buf != stack_buf) free(out_buf);
    pcre2_match_data_free(match_data);
    return 1;
}

/*
 * regex.test(text, pattern) -> boolean
 */
static int l_regex_test(lua_State *L) {
    size_t text_len = 0, pat_len = 0;
    const char *text = luaL_checklstring(L, 1, &text_len);
    const char *pat = luaL_checklstring(L, 2, &pat_len);

    if (!text || !pat || pat_len == 0) {
        lua_pushboolean(L, 0);
        return 1;
    }

    pcre2_code *re = get_cached_regex(pat, pat_len);
    if (!re) {
        lua_pushboolean(L, 0);
        return 1;
    }

    pcre2_match_data *match_data = pcre2_match_data_create_from_pattern(re, NULL);
    if (!match_data) {
        lua_pushboolean(L, 0);
        return 1;
    }

    int rc = pcre2_match(re, (PCRE2_SPTR)text, text_len, 0, 0, match_data, NULL);
    pcre2_match_data_free(match_data);

    lua_pushboolean(L, rc >= 0);
    return 1;
}

/*
 * regex.find(text, pattern, [init]) -> start, end (1-based) or nil
 */
static int l_regex_find(lua_State *L) {
    size_t text_len = 0, pat_len = 0;
    const char *text = luaL_checklstring(L, 1, &text_len);
    const char *pat = luaL_checklstring(L, 2, &pat_len);
    lua_Integer init = luaL_optinteger(L, 3, 1);

    if (!text || !pat || pat_len == 0) {
        lua_pushnil(L);
        return 1;
    }

    if (init < 1) init = 1;
    if ((size_t)init > text_len + 1) {
        lua_pushnil(L);
        return 1;
    }

    pcre2_code *re = get_cached_regex(pat, pat_len);
    if (!re) {
        lua_pushnil(L);
        return 1;
    }

    pcre2_match_data *match_data = pcre2_match_data_create_from_pattern(re, NULL);
    if (!match_data) {
        lua_pushnil(L);
        return 1;
    }

    PCRE2_SIZE start_offset = (PCRE2_SIZE)(init - 1);
    int rc = pcre2_match(re, (PCRE2_SPTR)text, text_len, start_offset, 0, match_data, NULL);
    if (rc >= 0) {
        PCRE2_SIZE *ovector = pcre2_get_ovector_pointer(match_data);
        lua_pushinteger(L, (lua_Integer)(ovector[0] + 1));
        lua_pushinteger(L, (lua_Integer)ovector[1]);
        pcre2_match_data_free(match_data);
        return 2;
    }

    pcre2_match_data_free(match_data);
    lua_pushnil(L);
    return 1;
}

/*
 * regex.split(text, pattern, [limit]) -> table of string parts
 */
static int l_regex_split(lua_State *L) {
    size_t text_len = 0, pat_len = 0;
    const char *text = luaL_checklstring(L, 1, &text_len);
    const char *pat = luaL_checklstring(L, 2, &pat_len);
    lua_Integer limit = luaL_optinteger(L, 3, 0);

    lua_newtable(L);
    if (!text || text_len == 0) {
        lua_pushliteral(L, "");
        lua_rawseti(L, -2, 1);
        return 1;
    }
    if (!pat || pat_len == 0) {
        lua_pushlstring(L, text, text_len);
        lua_rawseti(L, -2, 1);
        return 1;
    }

    pcre2_code *re = get_cached_regex(pat, pat_len);
    if (!re) {
        lua_pushlstring(L, text, text_len);
        lua_rawseti(L, -2, 1);
        return 1;
    }

    pcre2_match_data *match_data = pcre2_match_data_create_from_pattern(re, NULL);
    if (!match_data) {
        lua_pushlstring(L, text, text_len);
        lua_rawseti(L, -2, 1);
        return 1;
    }

    PCRE2_SIZE offset = 0;
    PCRE2_SIZE last_offset = 0;
    int part_idx = 1;

    while (offset <= text_len && (limit <= 0 || part_idx < limit)) {
        int rc = pcre2_match(re, (PCRE2_SPTR)text, text_len, offset, 0, match_data, NULL);
        if (rc < 0) {
            break;
        }

        PCRE2_SIZE *ovector = pcre2_get_ovector_pointer(match_data);
        PCRE2_SIZE start = ovector[0];
        PCRE2_SIZE end = ovector[1];

        if (start != PCRE2_UNSET && end != PCRE2_UNSET) {
            lua_pushlstring(L, text + last_offset, start - last_offset);
            lua_rawseti(L, -2, part_idx++);
            last_offset = end;
        }

        if (end > offset) {
            offset = end;
        } else {
            offset++;
        }
    }

    /* Append remainder */
    lua_pushlstring(L, text + last_offset, text_len - last_offset);
    lua_rawseti(L, -2, part_idx);

    pcre2_match_data_free(match_data);
    return 1;
}

static const struct luaL_Reg regexlib[] = {
    {"match", l_regex_match},
    {"replace", l_regex_replace},
    {"test", l_regex_test},
    {"find", l_regex_find},
    {"find_all", l_regex_match},
    {"split", l_regex_split},
    {NULL, NULL}
};

LUAMOD_API int luaopen_regex(lua_State *L) {
    luaL_newlib(L, regexlib);

    /* Export regex_match and regex_replace globally for NoveLA / Shosetsu scripts */
    lua_pushcfunction(L, l_regex_match);
    lua_setglobal(L, "regex_match");

    lua_pushcfunction(L, l_regex_replace);
    lua_setglobal(L, "regex_replace");

    return 1;
}
