#define lutilib_c
#define LUA_LIB

#include "lprefix.h"
#include <stdlib.h>
#include <string.h>
#include <ctype.h>

#include "lua.h"
#include "lauxlib.h"
#include "lualib.h"

#include "b64.h"
#include "aes.h"

static int l_base64_encode(lua_State *L) {
    size_t len = 0;
    const char *str = luaL_checklstring(L, 1, &len);
    char *encoded = b64_encode((const unsigned char *)str, len);
    if (encoded == NULL) {
        lua_pushnil(L);
        return 1;
    }
    lua_pushstring(L, encoded);
    free(encoded);
    return 1;
}

static int l_base64_decode(lua_State *L) {
    size_t len = 0;
    const char *str = luaL_checklstring(L, 1, &len);
    size_t out_len = 0;
    unsigned char *decoded = b64_decode_ex(str, len, &out_len);
    if (decoded == NULL) {
        lua_pushnil(L);
        return 1;
    }
    lua_pushlstring(L, (const char *)decoded, out_len);
    free(decoded);
    return 1;
}

static int l_url_encode(lua_State *L) {
    size_t len = 0;
    const char *str = luaL_checklstring(L, 1, &len);
    // Ignore optional charset argument 2 for standard RFC 3986 / x-www-form-urlencoded
    luaL_Buffer b;
    luaL_buffinit(L, &b);

    static const char hex[] = "0123456789ABCDEF";

    for (size_t i = 0; i < len; i++) {
        unsigned char c = (unsigned char)str[i];
        if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') ||
            c == '-' || c == '_' || c == '.' || c == '~') {
            luaL_addchar(&b, c);
        } else if (c == ' ') {
            luaL_addchar(&b, '+');
        } else {
            luaL_addchar(&b, '%');
            luaL_addchar(&b, hex[(c >> 4) & 0x0F]);
            luaL_addchar(&b, hex[c & 0x0F]);
        }
    }

    luaL_pushresult(&b);
    return 1;
}

static int l_aes_decrypt(lua_State *L) {
    size_t cipher_b64_len = 0, key_len = 0, iv_len = 0;
    const char *cipher_b64 = luaL_checklstring(L, 1, &cipher_b64_len);
    const char *key = luaL_checklstring(L, 2, &key_len);
    const char *iv = luaL_checklstring(L, 3, &iv_len);

    if (key_len != 16) {
        return luaL_error(L, "aes_decrypt: Key must be exactly 16 bytes for AES-128, got %d", (int)key_len);
    }
    if (iv_len != 16) {
        return luaL_error(L, "aes_decrypt: IV must be exactly 16 bytes, got %d", (int)iv_len);
    }

    size_t raw_len = 0;
    unsigned char *raw_buf = b64_decode_ex(cipher_b64, cipher_b64_len, &raw_len);
    if (raw_buf == NULL || raw_len == 0 || (raw_len % 16) != 0) {
        if (raw_buf) free(raw_buf);
        lua_pushnil(L);
        lua_pushliteral(L, "aes_decrypt: Invalid Base64 ciphertext or length not multiple of 16");
        return 2;
    }

    struct AES_ctx ctx;
    AES_init_ctx_iv(&ctx, (const uint8_t *)key, (const uint8_t *)iv);
    AES_CBC_decrypt_buffer(&ctx, raw_buf, raw_len);

    // PKCS#7 / PKCS#5 Unpadding
    uint8_t pad = raw_buf[raw_len - 1];
    size_t plain_len = raw_len;
    if (pad >= 1 && pad <= 16) {
        int valid_pad = 1;
        for (size_t i = raw_len - pad; i < raw_len; i++) {
            if (raw_buf[i] != pad) {
                valid_pad = 0;
                break;
            }
        }
        if (valid_pad) {
            plain_len = raw_len - pad;
        }
    }

    lua_pushlstring(L, (const char *)raw_buf, plain_len);
    free(raw_buf);
    return 1;
}

static int l_string_trim(lua_State *L) {
    size_t len = 0;
    const char *str = luaL_checklstring(L, 1, &len);
    size_t start = 0;
    while (start < len && isspace((unsigned char)str[start])) start++;
    size_t end = len;
    while (end > start && isspace((unsigned char)str[end - 1])) end--;
    lua_pushlstring(L, str + start, end - start);
    return 1;
}

static int l_string_normalize(lua_State *L) {
    size_t len = 0;
    const char *str = luaL_checklstring(L, 1, &len);
    luaL_Buffer b;
    luaL_buffinit(L, &b);

    size_t start = 0;
    while (start < len && isspace((unsigned char)str[start])) start++;
    size_t end = len;
    while (end > start && isspace((unsigned char)str[end - 1])) end--;

    int in_space = 0;
    for (size_t i = start; i < end; i++) {
        unsigned char c = (unsigned char)str[i];
        if (isspace(c)) {
            if (!in_space) {
                luaL_addchar(&b, ' ');
                in_space = 1;
            }
        } else {
            luaL_addchar(&b, c);
            in_space = 0;
        }
    }
    luaL_pushresult(&b);
    return 1;
}

static int l_string_starts_with(lua_State *L) {
    size_t slen = 0, plen = 0;
    const char *s = luaL_checklstring(L, 1, &slen);
    const char *p = luaL_checklstring(L, 2, &plen);
    if (plen > slen) {
        lua_pushboolean(L, 0);
    } else {
        lua_pushboolean(L, memcmp(s, p, plen) == 0);
    }
    return 1;
}

static int l_string_ends_with(lua_State *L) {
    size_t slen = 0, elen = 0;
    const char *s = luaL_checklstring(L, 1, &slen);
    const char *e = luaL_checklstring(L, 2, &elen);
    if (elen > slen) {
        lua_pushboolean(L, 0);
    } else {
        lua_pushboolean(L, memcmp(s + (slen - elen), e, elen) == 0);
    }
    return 1;
}

static int l_string_split(lua_State *L) {
    size_t slen = 0, seplen = 0;
    const char *s = luaL_checklstring(L, 1, &slen);
    const char *sep = luaL_optlstring(L, 2, " ", &seplen);

    lua_newtable(L);
    int idx = 1;

    if (seplen == 0) {
        for (size_t i = 0; i < slen; i++) {
            lua_pushlstring(L, s + i, 1);
            lua_rawseti(L, -2, idx++);
        }
        return 1;
    }

    const char *curr = s;
    const char *end = s + slen;
    while (curr < end) {
        const char *next = strstr(curr, sep);
        if (next == NULL || next > end - seplen) {
            lua_pushlstring(L, curr, end - curr);
            lua_rawseti(L, -2, idx++);
            break;
        }
        lua_pushlstring(L, curr, next - curr);
        lua_rawseti(L, -2, idx++);
        curr = next + seplen;
        if (curr == end) {
            lua_pushliteral(L, "");
            lua_rawseti(L, -2, idx++);
            break;
        }
    }
    return 1;
}

static void append_utf8_codepoint(luaL_Buffer *b, unsigned int cp) {
    if (cp <= 0x7F) {
        luaL_addchar(b, (char)cp);
    } else if (cp <= 0x7FF) {
        luaL_addchar(b, (char)(0xC0 | ((cp >> 6) & 0x1F)));
        luaL_addchar(b, (char)(0x80 | (cp & 0x3F)));
    } else if (cp <= 0xFFFF) {
        luaL_addchar(b, (char)(0xE0 | ((cp >> 12) & 0x0F)));
        luaL_addchar(b, (char)(0x80 | ((cp >> 6) & 0x3F)));
        luaL_addchar(b, (char)(0x80 | (cp & 0x3F)));
    } else if (cp <= 0x10FFFF) {
        luaL_addchar(b, (char)(0xF0 | ((cp >> 18) & 0x07)));
        luaL_addchar(b, (char)(0x80 | ((cp >> 12) & 0x3F)));
        luaL_addchar(b, (char)(0x80 | ((cp >> 6) & 0x3F)));
        luaL_addchar(b, (char)(0x80 | (cp & 0x3F)));
    }
}

static int l_unescape_unicode(lua_State *L) {
    size_t len = 0;
    const char *str = luaL_checklstring(L, 1, &len);
    luaL_Buffer b;
    luaL_buffinit(L, &b);

    size_t i = 0;
    while (i < len) {
        if (str[i] == '\\' && i + 5 < len && (str[i + 1] == 'u' || str[i + 1] == 'U')) {
            unsigned int cp = 0;
            int valid = 1;
            for (int k = 2; k < 6; k++) {
                char c = str[i + k];
                cp <<= 4;
                if (c >= '0' && c <= '9') cp |= (c - '0');
                else if (c >= 'a' && c <= 'f') cp |= (c - 'a' + 10);
                else if (c >= 'A' && c <= 'F') cp |= (c - 'A' + 10);
                else { valid = 0; break; }
            }
            if (valid) {
                if (cp >= 0xD800 && cp <= 0xDBFF && i + 11 < len &&
                    str[i + 6] == '\\' && (str[i + 7] == 'u' || str[i + 7] == 'U')) {
                    unsigned int lo = 0;
                    int lo_valid = 1;
                    for (int k = 8; k < 12; k++) {
                        char c = str[i + k];
                        lo <<= 4;
                        if (c >= '0' && c <= '9') lo |= (c - '0');
                        else if (c >= 'a' && c <= 'f') lo |= (c - 'a' + 10);
                        else if (c >= 'A' && c <= 'F') lo |= (c - 'A' + 10);
                        else { lo_valid = 0; break; }
                    }
                    if (lo_valid && lo >= 0xDC00 && lo <= 0xDFFF) {
                        unsigned int full_cp = 0x10000 + ((cp - 0xD800) << 10) + (lo - 0xDC00);
                        append_utf8_codepoint(&b, full_cp);
                        i += 12;
                        continue;
                    }
                }
                append_utf8_codepoint(&b, cp);
                i += 6;
                continue;
            }
        }
        luaL_addchar(&b, str[i]);
        i++;
    }

    luaL_pushresult(&b);
    return 1;
}

static const struct luaL_Reg utillib[] = {
    {"base64_encode", l_base64_encode},
    {"base64_decode", l_base64_decode},
    {"url_encode", l_url_encode},
    {"url_encode_charset", l_url_encode},
    {"aes_decrypt", l_aes_decrypt},
    {"string_trim", l_string_trim},
    {"string_clean", l_string_normalize},
    {"string_normalize", l_string_normalize},
    {"string_starts_with", l_string_starts_with},
    {"string_ends_with", l_string_ends_with},
    {"string_split", l_string_split},
    {"unescape_unicode", l_unescape_unicode},
    {NULL, NULL}
};

LUAMOD_API int luaopen_util(lua_State *L) {
    luaL_newlib(L, utillib);
    return 1;
}
