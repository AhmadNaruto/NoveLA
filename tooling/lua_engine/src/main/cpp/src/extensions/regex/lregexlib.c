#define lregexlib_c
#define LUA_LIB

#include "lprefix.h"
#include <stdlib.h>
#include <string.h>
#include <regex.h>
#include "lua.h"
#include "lauxlib.h"
#include "lualib.h"

#define MAX_CAPTURES 32

static char *preprocess_pattern(const char *pat) {
    size_t len = strlen(pat);
    size_t cap = len * 8 + 64;
    char *out = (char *)malloc(cap);
    if (out == NULL) return NULL;

    size_t j = 0;
    int in_bracket = 0;
    for (size_t i = 0; i < len; i++) {
        if (pat[i] == '[' && (i == 0 || pat[i - 1] != '\\')) {
            in_bracket = 1;
            out[j++] = pat[i];
            continue;
        }
        if (pat[i] == ']' && in_bracket && (i == 0 || pat[i - 1] != '\\')) {
            in_bracket = 0;
            out[j++] = pat[i];
            continue;
        }

        if (!in_bracket && pat[i] == '\\' && i + 1 < len) {
            char next = pat[i + 1];
            const char *rep = NULL;
            size_t replen = 0;
            switch (next) {
                case 'd': rep = "[0-9]"; replen = 5; break;
                case 'D': rep = "[^0-9]"; replen = 6; break;
                case 's': rep = "[ \t\r\n\v\f]"; replen = 10; break;
                case 'S': rep = "[^ \t\r\n\v\f]"; replen = 11; break;
                case 'w': rep = "[a-zA-Z0-9_]"; replen = 12; break;
                case 'W': rep = "[^a-zA-Z0-9_]"; replen = 13; break;
                default: break;
            }
            if (rep != NULL) {
                memcpy(out + j, rep, replen);
                j += replen;
                i++;
                continue;
            }
        }
        out[j++] = pat[i];
    }
    out[j] = '\0';
    return out;
}

static int parse_flags(const char *flags) {
    int cflags = REG_EXTENDED;
    if (flags == NULL) return cflags;
    for (const char *p = flags; *p; p++) {
        if (*p == 'i' || *p == 'I') cflags |= REG_ICASE;
        if (*p == 'm' || *p == 'M') cflags |= REG_NEWLINE;
    }
    return cflags;
}

static int compile_regex(lua_State *L, regex_t *re, const char *pattern, const char *flags) {
    char *pre = preprocess_pattern(pattern);
    if (pre == NULL) {
        return luaL_error(L, "Out of memory preprocessing regex");
    }
    int cflags = parse_flags(flags);
    int res = regcomp(re, pre, cflags);
    free(pre);
    if (res != 0) {
        char errbuf[256];
        regerror(res, re, errbuf, sizeof(errbuf));
        return luaL_error(L, "Regex error: %s", errbuf);
    }
    return 0;
}

static int regex_test(lua_State *L) {
    const char *str = luaL_checkstring(L, 1);
    const char *pat = luaL_checkstring(L, 2);
    const char *flags = luaL_optstring(L, 3, NULL);

    regex_t re;
    compile_regex(L, &re, pat, flags);

    int match = (regexec(&re, str, 0, NULL, 0) == 0);
    regfree(&re);

    lua_pushboolean(L, match);
    return 1;
}

static int regex_match(lua_State *L) {
    const char *str = luaL_checkstring(L, 1);
    const char *pat = luaL_checkstring(L, 2);
    const char *flags = luaL_optstring(L, 3, NULL);

    regex_t re;
    compile_regex(L, &re, pat, flags);

    regmatch_t pm[MAX_CAPTURES];
    int res = regexec(&re, str, MAX_CAPTURES, pm, 0);
    if (res != 0) {
        regfree(&re);
        lua_pushnil(L);
        return 1;
    }

    size_t nsubs = re.re_nsub;
    if (nsubs >= MAX_CAPTURES) nsubs = MAX_CAPTURES - 1;

    // Push full match
    lua_pushlstring(L, str + pm[0].rm_so, pm[0].rm_eo - pm[0].rm_so);

    // Push captured groups if any
    for (size_t i = 1; i <= nsubs; i++) {
        if (pm[i].rm_so != -1) {
            lua_pushlstring(L, str + pm[i].rm_so, pm[i].rm_eo - pm[i].rm_so);
        } else {
            lua_pushnil(L);
        }
    }
    regfree(&re);
    return (int)(1 + nsubs);
}

static int regex_find(lua_State *L) {
    const char *str = luaL_checkstring(L, 1);
    const char *pat = luaL_checkstring(L, 2);
    lua_Integer init = luaL_optinteger(L, 3, 1);
    const char *flags = luaL_optstring(L, 4, NULL);

    size_t len = strlen(str);
    if (init < 1) init = 1;
    if ((size_t)init > len + 1) {
        lua_pushnil(L);
        return 1;
    }

    regex_t re;
    compile_regex(L, &re, pat, flags);

    regmatch_t pm[1];
    const char *search_start = str + (init - 1);
    int res = regexec(&re, search_start, 1, pm, 0);
    regfree(&re);

    if (res == 0) {
        lua_pushinteger(L, (init - 1) + pm[0].rm_so + 1); // 1-based start
        lua_pushinteger(L, (init - 1) + pm[0].rm_eo);     // 1-based end
        return 2;
    }
    lua_pushnil(L);
    return 1;
}

static int regex_find_all(lua_State *L) {
    const char *str = luaL_checkstring(L, 1);
    const char *pat = luaL_checkstring(L, 2);
    const char *flags = luaL_optstring(L, 3, NULL);

    regex_t re;
    compile_regex(L, &re, pat, flags);

    lua_newtable(L);
    int match_idx = 1;

    const char *cursor = str;
    regmatch_t pm[1];

    while (*cursor && regexec(&re, cursor, 1, pm, 0) == 0) {
        regoff_t match_len = pm[0].rm_eo - pm[0].rm_so;
        lua_pushlstring(L, cursor + pm[0].rm_so, match_len);
        lua_rawseti(L, -2, match_idx++);

        cursor += pm[0].rm_eo;
        if (match_len == 0) {
            if (*cursor) cursor++;
            else break;
        }
    }

    regfree(&re);
    return 1;
}

static void append_replacement(luaL_Buffer *b, const char *repl, const char *orig, regmatch_t *pm, size_t nsubs) {
    for (size_t i = 0; repl[i]; i++) {
        if ((repl[i] == '$' || repl[i] == '\\') && repl[i + 1] >= '0' && repl[i + 1] <= '9') {
            int grp = repl[i + 1] - '0';
            i++;
            if ((size_t)grp <= nsubs && pm[grp].rm_so != -1) {
                luaL_addlstring(b, orig + pm[grp].rm_so, pm[grp].rm_eo - pm[grp].rm_so);
            }
        } else {
            luaL_addchar(b, repl[i]);
        }
    }
}

static int regex_replace(lua_State *L) {
    const char *str = luaL_checkstring(L, 1);
    const char *pat = luaL_checkstring(L, 2);
    const char *repl = luaL_checkstring(L, 3);
    lua_Integer limit = luaL_optinteger(L, 4, 0); // 0 means unlimited
    const char *flags = luaL_optstring(L, 5, NULL);

    regex_t re;
    compile_regex(L, &re, pat, flags);

    luaL_Buffer b;
    luaL_buffinit(L, &b);

    size_t nsubs = re.re_nsub;
    if (nsubs >= MAX_CAPTURES) nsubs = MAX_CAPTURES - 1;

    regmatch_t pm[MAX_CAPTURES];
    const char *cursor = str;
    int count = 0;

    while (*cursor && (limit <= 0 || count < limit)) {
        if (regexec(&re, cursor, MAX_CAPTURES, pm, 0) != 0) {
            break;
        }

        // Add prefix before match
        luaL_addlstring(&b, cursor, pm[0].rm_so);

        // Add replacement
        append_replacement(&b, repl, cursor, pm, nsubs);
        count++;

        regoff_t match_len = pm[0].rm_eo - pm[0].rm_so;
        cursor += pm[0].rm_eo;
        if (match_len == 0) {
            if (*cursor) {
                luaL_addchar(&b, *cursor);
                cursor++;
            } else {
                break;
            }
        }
    }

    // Add trailing remaining string
    luaL_addstring(&b, cursor);
    luaL_pushresult(&b);

    regfree(&re);
    lua_pushinteger(L, count);
    return 2;
}

static int regex_split(lua_State *L) {
    const char *str = luaL_checkstring(L, 1);
    const char *pat = luaL_checkstring(L, 2);
    lua_Integer limit = luaL_optinteger(L, 3, 0);
    const char *flags = luaL_optstring(L, 4, NULL);

    regex_t re;
    compile_regex(L, &re, pat, flags);

    lua_newtable(L);
    int part_idx = 1;
    const char *cursor = str;
    regmatch_t pm[1];

    while (*cursor && (limit <= 0 || part_idx < limit)) {
        if (regexec(&re, cursor, 1, pm, 0) != 0) {
            break;
        }
        lua_pushlstring(L, cursor, pm[0].rm_so);
        lua_rawseti(L, -2, part_idx++);

        regoff_t match_len = pm[0].rm_eo - pm[0].rm_so;
        cursor += pm[0].rm_eo;
        if (match_len == 0) {
            if (*cursor) cursor++;
            else break;
        }
    }

    // Push last part
    lua_pushstring(L, cursor);
    lua_rawseti(L, -2, part_idx);

    regfree(&re);
    return 1;
}

static const struct luaL_Reg regexlib[] = {
    {"test", regex_test},
    {"match", regex_match},
    {"find", regex_find},
    {"find_all", regex_find_all},
    {"replace", regex_replace},
    {"split", regex_split},
    {NULL, NULL}
};

LUAMOD_API int luaopen_regex(lua_State *L) {
    luaL_newlib(L, regexlib);
    return 1;
}
