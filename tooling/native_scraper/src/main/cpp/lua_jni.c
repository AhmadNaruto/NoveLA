#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <time.h>
#include <unistd.h>
#include <android/log.h>

#include "lua/lua.h"
#include "lua/lauxlib.h"
#include "lua/lualib.h"
#include "lua_lexbor.h"

#define LOG_TAG "NativeScraperLua"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

static JavaVM *g_jvm = NULL;

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    g_jvm = vm;
    return JNI_VERSION_1_6;
}

// -----------------------------------------------------------------------------
// Bridge Holder & JNI Utilities
// -----------------------------------------------------------------------------

typedef struct {
    jobject bridge_ref;
    jmethodID http_get_mid;
    jmethodID http_post_mid;
    jmethodID http_get_batch_mid;
    jmethodID get_pref_mid;
    jmethodID set_pref_mid;
    jmethodID get_cookies_mid;
    jmethodID set_cookies_mid;
    jmethodID get_storage_mid;
    jmethodID show_error_mid;
    jmethodID log_mid;
    jmethodID sleep_mid;
} lua_bridge_holder_t;

static lua_bridge_holder_t *get_bridge_holder(lua_State *L) {
    if (!L) return NULL;
    return *(lua_bridge_holder_t **) lua_getextraspace(L);
}

static JNIEnv *get_jni_env(int *should_detach) {
    *should_detach = 0;
    if (!g_jvm) return NULL;
    JNIEnv *env = NULL;
    jint rc = (*g_jvm)->GetEnv(g_jvm, (void **) &env, JNI_VERSION_1_6);
    if (rc == JNI_EDETACHED) {
        if ((*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL) == 0) {
            *should_detach = 1;
        } else {
            return NULL;
        }
    }
    return env;
}

static void release_jni_env(int should_detach) {
    if (should_detach && g_jvm) {
        (*g_jvm)->DetachCurrentThread(g_jvm);
    }
}

// -----------------------------------------------------------------------------
// Fast JSON Serializer: Lua -> JSON string
// -----------------------------------------------------------------------------

typedef struct {
    char *data;
    size_t length;
    size_t capacity;
} json_buf_t;

static void json_buf_init(json_buf_t *buf) {
    buf->capacity = 128;
    buf->length = 0;
    buf->data = (char *) malloc(buf->capacity);
    if (buf->data) buf->data[0] = '\0';
}

static void json_buf_append(json_buf_t *buf, const char *str) {
    if (!str) return;
    size_t slen = strlen(str);
    if (buf->length + slen + 1 >= buf->capacity) {
        size_t new_cap = (buf->capacity + slen) * 2;
        char *new_data = (char *) realloc(buf->data, new_cap);
        if (!new_data) return;
        buf->data = new_data;
        buf->capacity = new_cap;
    }
    memcpy(buf->data + buf->length, str, slen);
    buf->length += slen;
    buf->data[buf->length] = '\0';
}

static void json_buf_append_string(json_buf_t *buf, const char *str, size_t len) {
    json_buf_append(buf, "\"");
    for (size_t i = 0; i < len; i++) {
        char c = str[i];
        switch (c) {
            case '"':  json_buf_append(buf, "\\\""); break;
            case '\\': json_buf_append(buf, "\\\\"); break;
            case '\b': json_buf_append(buf, "\\b");  break;
            case '\f': json_buf_append(buf, "\\f");  break;
            case '\n': json_buf_append(buf, "\\n");  break;
            case '\r': json_buf_append(buf, "\\r");  break;
            case '\t': json_buf_append(buf, "\\t");  break;
            default:
                if ((unsigned char) c < 0x20) {
                    char esc[8];
                    snprintf(esc, sizeof(esc), "\\u%04x", (unsigned char) c);
                    json_buf_append(buf, esc);
                } else {
                    char single[2] = {c, '\0'};
                    json_buf_append(buf, single);
                }
                break;
        }
    }
    json_buf_append(buf, "\"");
}

static void lua_to_json(lua_State *L, int index, json_buf_t *buf) {
    int type = lua_type(L, index);
    switch (type) {
        case LUA_TNIL:
            json_buf_append(buf, "null");
            break;
        case LUA_TBOOLEAN:
            json_buf_append(buf, lua_toboolean(L, index) ? "true" : "false");
            break;
        case LUA_TNUMBER:
            if (lua_isinteger(L, index)) {
                char tmp[32];
                snprintf(tmp, sizeof(tmp), "%lld", (long long) lua_tointeger(L, index));
                json_buf_append(buf, tmp);
            } else {
                char tmp[64];
                snprintf(tmp, sizeof(tmp), "%.14g", lua_tonumber(L, index));
                json_buf_append(buf, tmp);
            }
            break;
        case LUA_TSTRING: {
            size_t len = 0;
            const char *s = lua_tolstring(L, index, &len);
            json_buf_append_string(buf, s, len);
            break;
        }
        case LUA_TTABLE: {
            lua_len(L, index);
            lua_Integer len = lua_tointeger(L, -1);
            lua_pop(L, 1);
            if (len > 0) {
                json_buf_append(buf, "[");
                for (lua_Integer i = 1; i <= len; i++) {
                    if (i > 1) json_buf_append(buf, ",");
                    lua_rawgeti(L, index, i);
                    lua_to_json(L, lua_gettop(L), buf);
                    lua_pop(L, 1);
                }
                json_buf_append(buf, "]");
            } else {
                json_buf_append(buf, "{");
                lua_pushnil(L);
                int first = 1;
                int abs_idx = lua_absindex(L, index);
                while (lua_next(L, abs_idx) != 0) {
                    if (!first) json_buf_append(buf, ",");
                    first = 0;
                    size_t klen = 0;
                    const char *k = lua_tolstring(L, -2, &klen);
                    if (k) {
                        json_buf_append_string(buf, k, klen);
                    } else {
                        json_buf_append(buf, "\"\"");
                    }
                    json_buf_append(buf, ":");
                    lua_to_json(L, lua_gettop(L), buf);
                    lua_pop(L, 1);
                }
                json_buf_append(buf, "}");
            }
            break;
        }
        default:
            json_buf_append(buf, "null");
            break;
    }
}

// -----------------------------------------------------------------------------
// Fast Native JSON Parser: JSON string -> Lua Stack
// -----------------------------------------------------------------------------

static const char *parse_json_value(lua_State *L, const char *p);

static const char *skip_ws(const char *p) {
    while (*p && ((unsigned char) *p <= ' ')) p++;
    return p;
}

static const char *parse_json_string(lua_State *L, const char *p) {
    if (*p != '"') return NULL;
    p++;
    luaL_Buffer b;
    luaL_buffinit(L, &b);
    while (*p) {
        if (*p == '"') {
            luaL_pushresult(&b);
            return p + 1;
        } else if (*p == '\\') {
            p++;
            switch (*p) {
                case '"':  luaL_addchar(&b, '"'); break;
                case '\\': luaL_addchar(&b, '\\'); break;
                case '/':  luaL_addchar(&b, '/'); break;
                case 'b':  luaL_addchar(&b, '\b'); break;
                case 'f':  luaL_addchar(&b, '\f'); break;
                case 'n':  luaL_addchar(&b, '\n'); break;
                case 'r':  luaL_addchar(&b, '\r'); break;
                case 't':  luaL_addchar(&b, '\t'); break;
                case 'u': {
                    unsigned int codepoint = 0;
                    for (int i = 0; i < 4; i++) {
                        p++;
                        char c = *p;
                        codepoint <<= 4;
                        if (c >= '0' && c <= '9') codepoint |= (c - '0');
                        else if (c >= 'a' && c <= 'f') codepoint |= (c - 'a' + 10);
                        else if (c >= 'A' && c <= 'F') codepoint |= (c - 'A' + 10);
                    }
                    if (codepoint < 0x80) {
                        luaL_addchar(&b, (char) codepoint);
                    } else if (codepoint < 0x800) {
                        luaL_addchar(&b, (char) (0xC0 | (codepoint >> 6)));
                        luaL_addchar(&b, (char) (0x80 | (codepoint & 0x3F)));
                    } else {
                        luaL_addchar(&b, (char) (0xE0 | (codepoint >> 12)));
                        luaL_addchar(&b, (char) (0x80 | ((codepoint >> 6) & 0x3F)));
                        luaL_addchar(&b, (char) (0x80 | (codepoint & 0x3F)));
                    }
                    break;
                }
                default:
                    luaL_addchar(&b, *p);
                    break;
            }
            p++;
        } else {
            luaL_addchar(&b, *p);
            p++;
        }
    }
    return NULL;
}

static const char *parse_json_object(lua_State *L, const char *p) {
    if (*p != '{') return NULL;
    p++;
    lua_newtable(L);
    p = skip_ws(p);
    if (*p == '}') return p + 1;
    while (*p) {
        p = skip_ws(p);
        if (*p != '"') return NULL;
        p = parse_json_string(L, p);
        if (!p) return NULL;
        p = skip_ws(p);
        if (*p != ':') { lua_pop(L, 1); return NULL; }
        p++;
        p = skip_ws(p);
        p = parse_json_value(L, p);
        if (!p) { lua_pop(L, 1); return NULL; }
        lua_settable(L, -3);
        p = skip_ws(p);
        if (*p == ',') {
            p++;
        } else if (*p == '}') {
            return p + 1;
        } else {
            return NULL;
        }
    }
    return NULL;
}

static const char *parse_json_array(lua_State *L, const char *p) {
    if (*p != '[') return NULL;
    p++;
    lua_newtable(L);
    p = skip_ws(p);
    if (*p == ']') return p + 1;
    lua_Integer idx = 1;
    while (*p) {
        p = skip_ws(p);
        p = parse_json_value(L, p);
        if (!p) return NULL;
        lua_rawseti(L, -2, idx++);
        p = skip_ws(p);
        if (*p == ',') {
            p++;
        } else if (*p == ']') {
            return p + 1;
        } else {
            return NULL;
        }
    }
    return NULL;
}

static const char *parse_json_number(lua_State *L, const char *p) {
    char *end = NULL;
    long long lval = strtoll(p, &end, 10);
    if (end && (*end == '.' || *end == 'e' || *end == 'E')) {
        double dval = strtod(p, &end);
        lua_pushnumber(L, dval);
        return end;
    } else {
        lua_pushinteger(L, (lua_Integer) lval);
        return end;
    }
}

static const char *parse_json_value(lua_State *L, const char *p) {
    p = skip_ws(p);
    if (!*p) return NULL;
    if (*p == '{') return parse_json_object(L, p);
    if (*p == '[') return parse_json_array(L, p);
    if (*p == '"') return parse_json_string(L, p);
    if (*p == 't' && strncmp(p, "true", 4) == 0) { lua_pushboolean(L, 1); return p + 4; }
    if (*p == 'f' && strncmp(p, "false", 5) == 0) { lua_pushboolean(L, 0); return p + 5; }
    if (*p == 'n' && strncmp(p, "null", 4) == 0) { lua_pushnil(L); return p + 4; }
    if (*p == '-' || (*p >= '0' && *p <= '9')) return parse_json_number(L, p);
    return NULL;
}

static int json_to_lua(lua_State *L, const char *json) {
    if (!json) { lua_pushnil(L); return 1; }
    const char *end = parse_json_value(L, json);
    if (!end) { lua_pushnil(L); return 0; }
    return 1;
}

// -----------------------------------------------------------------------------
// Pure C Utility Functions
// -----------------------------------------------------------------------------

static const char b64_table[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

static int lua_api_base64_encode(lua_State *L) {
    size_t len = 0;
    const unsigned char *src = (const unsigned char *) luaL_checklstring(L, 1, &len);
    if (len == 0) {
        lua_pushliteral(L, "");
        return 1;
    }
    size_t out_len = 4 * ((len + 2) / 3);
    char *out = (char *) malloc(out_len + 1);
    if (!out) return luaL_error(L, "Out of memory in base64_encode");
    size_t i = 0, j = 0;
    for (; i + 2 < len; i += 3) {
        out[j++] = b64_table[(src[i] >> 2) & 0x3F];
        out[j++] = b64_table[((src[i] & 0x3) << 4) | ((src[i + 1] >> 4) & 0xF)];
        out[j++] = b64_table[((src[i + 1] & 0xF) << 2) | ((src[i + 2] >> 6) & 0x3)];
        out[j++] = b64_table[src[i + 2] & 0x3F];
    }
    if (i < len) {
        out[j++] = b64_table[(src[i] >> 2) & 0x3F];
        if (i + 1 < len) {
            out[j++] = b64_table[((src[i] & 0x3) << 4) | ((src[i + 1] >> 4) & 0xF)];
            out[j++] = b64_table[(src[i + 1] & 0xF) << 2];
        } else {
            out[j++] = b64_table[(src[i] & 0x3) << 4];
            out[j++] = '=';
        }
        out[j++] = '=';
    }
    out[j] = '\0';
    lua_pushlstring(L, out, j);
    free(out);
    return 1;
}

static int lua_api_base64_decode(lua_State *L) {
    size_t len = 0;
    const char *src = luaL_checklstring(L, 1, &len);
    if (len == 0) {
        lua_pushliteral(L, "");
        return 1;
    }
    unsigned char dtable[256];
    memset(dtable, 0x80, 256);
    for (int i = 0; i < 64; i++) dtable[(unsigned char) b64_table[i]] = (unsigned char) i;
    dtable['='] = 0;

    size_t out_cap = (len * 3) / 4 + 4;
    char *out = (char *) malloc(out_cap);
    if (!out) return luaL_error(L, "Out of memory in base64_decode");
    size_t j = 0;
    int pad = 0;
    for (size_t i = 0; i < len; i += 4) {
        unsigned int b = 0;
        for (int k = 0; k < 4; k++) {
            char c = (i + k < len) ? src[i + k] : '=';
            if (c == '=') pad++;
            unsigned char v = dtable[(unsigned char) c];
            b = (b << 6) | (v & 0x3F);
        }
        out[j++] = (char) ((b >> 16) & 0xFF);
        if (pad < 2) out[j++] = (char) ((b >> 8) & 0xFF);
        if (pad < 1) out[j++] = (char) (b & 0xFF);
    }
    lua_pushlstring(L, out, j);
    free(out);
    return 1;
}

static int lua_api_json_parse(lua_State *L) {
    const char *str = luaL_checkstring(L, 1);
    if (!json_to_lua(L, str)) {
        lua_pushnil(L);
    }
    return 1;
}

static int lua_api_json_stringify(lua_State *L) {
    json_buf_t buf;
    json_buf_init(&buf);
    lua_to_json(L, 1, &buf);
    if (buf.data) {
        lua_pushlstring(L, buf.data, buf.length);
        free(buf.data);
    } else {
        lua_pushliteral(L, "null");
    }
    return 1;
}

static int lua_api_string_trim(lua_State *L) {
    size_t len = 0;
    const char *str = luaL_checklstring(L, 1, &len);
    while (len > 0 && ((unsigned char) *str <= ' ')) {
        str++;
        len--;
    }
    while (len > 0 && ((unsigned char) str[len - 1] <= ' ')) {
        len--;
    }
    lua_pushlstring(L, str, len);
    return 1;
}

static int lua_api_string_starts_with(lua_State *L) {
    size_t slen = 0, plen = 0;
    const char *str = luaL_checklstring(L, 1, &slen);
    const char *prefix = luaL_checklstring(L, 2, &plen);
    if (plen > slen) {
        lua_pushboolean(L, 0);
    } else {
        lua_pushboolean(L, memcmp(str, prefix, plen) == 0);
    }
    return 1;
}

static int lua_api_string_ends_with(lua_State *L) {
    size_t slen = 0, sufflen = 0;
    const char *str = luaL_checklstring(L, 1, &slen);
    const char *suffix = luaL_checklstring(L, 2, &sufflen);
    if (sufflen > slen) {
        lua_pushboolean(L, 0);
    } else {
        lua_pushboolean(L, memcmp(str + slen - sufflen, suffix, sufflen) == 0);
    }
    return 1;
}

static int lua_api_string_split(lua_State *L) {
    size_t slen = 0, dlen = 0;
    const char *str = luaL_checklstring(L, 1, &slen);
    const char *delim = luaL_checklstring(L, 2, &dlen);
    lua_newtable(L);
    if (dlen == 0) {
        lua_pushvalue(L, 1);
        lua_rawseti(L, -2, 1);
        return 1;
    }
    lua_Integer idx = 1;
    const char *cur = str;
    const char *end = str + slen;
    while (cur < end) {
        const char *found = strstr(cur, delim);
        if (!found) {
            lua_pushlstring(L, cur, (size_t) (end - cur));
            lua_rawseti(L, -2, idx++);
            break;
        }
        lua_pushlstring(L, cur, (size_t) (found - cur));
        lua_rawseti(L, -2, idx++);
        cur = found + dlen;
    }
    return 1;
}

static int lua_api_string_clean(lua_State *L) {
    size_t len = 0;
    const char *str = luaL_checklstring(L, 1, &len);
    if (len == 0) {
        lua_pushliteral(L, "");
        return 1;
    }
    char *out = (char *) malloc(len + 1);
    if (!out) return luaL_error(L, "Out of memory in string_clean");
    size_t j = 0;
    int in_ws = 0;
    for (size_t i = 0; i < len; i++) {
        unsigned char c = (unsigned char) str[i];
        if (c <= ' ') {
            if (!in_ws && j > 0) {
                out[j++] = ' ';
                in_ws = 1;
            }
        } else {
            out[j++] = (char) c;
            in_ws = 0;
        }
    }
    if (j > 0 && out[j - 1] == ' ') j--;
    out[j] = '\0';
    lua_pushlstring(L, out, j);
    free(out);
    return 1;
}

static int lua_api_os_time(lua_State *L) {
    lua_pushinteger(L, (lua_Integer) time(NULL));
    return 1;
}

static int lua_api_sleep(lua_State *L) {
    lua_Integer ms = luaL_checkinteger(L, 1);
    if (ms > 0) {
        usleep((useconds_t) (ms * 1000));
    }
    return 0;
}

// -----------------------------------------------------------------------------
// JNI Bridge Callbacks (Network, Preferences, Cookies, Logging)
// -----------------------------------------------------------------------------

static int lua_api_http_get(lua_State *L) {
    lua_bridge_holder_t *holder = get_bridge_holder(L);
    if (!holder || !holder->bridge_ref || !holder->http_get_mid) {
        lua_newtable(L);
        lua_pushboolean(L, 0);
        lua_setfield(L, -2, "success");
        lua_pushliteral(L, "No network bridge initialized");
        lua_setfield(L, -2, "body");
        return 1;
    }
    const char *url = luaL_checkstring(L, 1);
    char *config_json = NULL;
    if (lua_istable(L, 2)) {
        json_buf_t buf;
        json_buf_init(&buf);
        lua_to_json(L, 2, &buf);
        config_json = buf.data;
    }

    int should_detach = 0;
    JNIEnv *env = get_jni_env(&should_detach);
    if (!env) {
        if (config_json) free(config_json);
        lua_newtable(L);
        lua_pushboolean(L, 0);
        lua_setfield(L, -2, "success");
        return 1;
    }

    jstring j_url = (*env)->NewStringUTF(env, url);
    jstring j_config = config_json ? (*env)->NewStringUTF(env, config_json) : NULL;
    if (config_json) free(config_json);

    jstring res = (jstring) (*env)->CallObjectMethod(env, holder->bridge_ref, holder->http_get_mid, j_url, j_config);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        res = NULL;
    }

    if (j_url) (*env)->DeleteLocalRef(env, j_url);
    if (j_config) (*env)->DeleteLocalRef(env, j_config);

    if (res) {
        const char *res_str = (*env)->GetStringUTFChars(env, res, NULL);
        if (res_str) {
            json_to_lua(L, res_str);
            (*env)->ReleaseStringUTFChars(env, res, res_str);
        } else {
            lua_newtable(L);
            lua_pushboolean(L, 0);
            lua_setfield(L, -2, "success");
        }
        (*env)->DeleteLocalRef(env, res);
    } else {
        lua_newtable(L);
        lua_pushboolean(L, 0);
        lua_setfield(L, -2, "success");
    }

    release_jni_env(should_detach);
    return 1;
}

static int lua_api_http_post(lua_State *L) {
    lua_bridge_holder_t *holder = get_bridge_holder(L);
    if (!holder || !holder->bridge_ref || !holder->http_post_mid) {
        lua_newtable(L);
        lua_pushboolean(L, 0);
        lua_setfield(L, -2, "success");
        return 1;
    }
    const char *url = luaL_checkstring(L, 1);
    char *body_str = NULL;
    if (lua_isstring(L, 2)) {
        size_t blen = 0;
        const char *b = lua_tolstring(L, 2, &blen);
        body_str = strdup(b);
    } else if (lua_istable(L, 2)) {
        json_buf_t buf;
        json_buf_init(&buf);
        lua_to_json(L, 2, &buf);
        body_str = buf.data;
    }
    char *config_json = NULL;
    if (lua_istable(L, 3)) {
        json_buf_t buf;
        json_buf_init(&buf);
        lua_to_json(L, 3, &buf);
        config_json = buf.data;
    }

    int should_detach = 0;
    JNIEnv *env = get_jni_env(&should_detach);
    if (!env) {
        if (body_str) free(body_str);
        if (config_json) free(config_json);
        lua_newtable(L);
        lua_pushboolean(L, 0);
        lua_setfield(L, -2, "success");
        return 1;
    }

    jstring j_url = (*env)->NewStringUTF(env, url);
    jstring j_body = body_str ? (*env)->NewStringUTF(env, body_str) : NULL;
    jstring j_config = config_json ? (*env)->NewStringUTF(env, config_json) : NULL;
    if (body_str) free(body_str);
    if (config_json) free(config_json);

    jstring res = (jstring) (*env)->CallObjectMethod(env, holder->bridge_ref, holder->http_post_mid, j_url, j_body, j_config);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        res = NULL;
    }

    if (j_url) (*env)->DeleteLocalRef(env, j_url);
    if (j_body) (*env)->DeleteLocalRef(env, j_body);
    if (j_config) (*env)->DeleteLocalRef(env, j_config);

    if (res) {
        const char *res_str = (*env)->GetStringUTFChars(env, res, NULL);
        if (res_str) {
            json_to_lua(L, res_str);
            (*env)->ReleaseStringUTFChars(env, res, res_str);
        } else {
            lua_newtable(L);
            lua_pushboolean(L, 0);
            lua_setfield(L, -2, "success");
        }
        (*env)->DeleteLocalRef(env, res);
    } else {
        lua_newtable(L);
        lua_pushboolean(L, 0);
        lua_setfield(L, -2, "success");
    }

    release_jni_env(should_detach);
    return 1;
}

static int lua_api_http_get_batch(lua_State *L) {
    lua_bridge_holder_t *holder = get_bridge_holder(L);
    if (!holder || !holder->bridge_ref || !holder->http_get_batch_mid) {
        lua_newtable(L);
        return 1;
    }
    json_buf_t items_buf;
    json_buf_init(&items_buf);
    lua_to_json(L, 1, &items_buf);

    char *config_json = NULL;
    if (lua_istable(L, 2)) {
        json_buf_t cbuf;
        json_buf_init(&cbuf);
        lua_to_json(L, 2, &cbuf);
        config_json = cbuf.data;
    }

    int should_detach = 0;
    JNIEnv *env = get_jni_env(&should_detach);
    if (!env) {
        free(items_buf.data);
        if (config_json) free(config_json);
        lua_newtable(L);
        return 1;
    }

    jstring j_items = (*env)->NewStringUTF(env, items_buf.data ? items_buf.data : "[]");
    jstring j_config = config_json ? (*env)->NewStringUTF(env, config_json) : NULL;
    free(items_buf.data);
    if (config_json) free(config_json);

    jstring res = (jstring) (*env)->CallObjectMethod(env, holder->bridge_ref, holder->http_get_batch_mid, j_items, j_config);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        res = NULL;
    }

    if (j_items) (*env)->DeleteLocalRef(env, j_items);
    if (j_config) (*env)->DeleteLocalRef(env, j_config);

    if (res) {
        const char *res_str = (*env)->GetStringUTFChars(env, res, NULL);
        if (res_str) {
            json_to_lua(L, res_str);
            (*env)->ReleaseStringUTFChars(env, res, res_str);
        } else {
            lua_newtable(L);
        }
        (*env)->DeleteLocalRef(env, res);
    } else {
        lua_newtable(L);
    }

    release_jni_env(should_detach);
    return 1;
}

static int lua_api_get_preference(lua_State *L) {
    lua_bridge_holder_t *holder = get_bridge_holder(L);
    if (!holder || !holder->bridge_ref || !holder->get_pref_mid) {
        lua_pushnil(L);
        return 1;
    }
    const char *key = luaL_checkstring(L, 1);
    int should_detach = 0;
    JNIEnv *env = get_jni_env(&should_detach);
    if (!env) { lua_pushnil(L); return 1; }

    jstring j_key = (*env)->NewStringUTF(env, key);
    jstring res = (jstring) (*env)->CallObjectMethod(env, holder->bridge_ref, holder->get_pref_mid, j_key);
    (*env)->DeleteLocalRef(env, j_key);

    if (res) {
        const char *str = (*env)->GetStringUTFChars(env, res, NULL);
        if (str) {
            lua_pushstring(L, str);
            (*env)->ReleaseStringUTFChars(env, res, str);
        } else {
            lua_pushnil(L);
        }
        (*env)->DeleteLocalRef(env, res);
    } else {
        lua_pushnil(L);
    }

    release_jni_env(should_detach);
    return 1;
}

static int lua_api_set_preference(lua_State *L) {
    lua_bridge_holder_t *holder = get_bridge_holder(L);
    if (!holder || !holder->bridge_ref || !holder->set_pref_mid) return 0;

    const char *key = luaL_checkstring(L, 1);
    const char *val = luaL_optstring(L, 2, NULL);

    int should_detach = 0;
    JNIEnv *env = get_jni_env(&should_detach);
    if (!env) return 0;

    jstring j_key = (*env)->NewStringUTF(env, key);
    jstring j_val = val ? (*env)->NewStringUTF(env, val) : NULL;

    (*env)->CallVoidMethod(env, holder->bridge_ref, holder->set_pref_mid, j_key, j_val);

    (*env)->DeleteLocalRef(env, j_key);
    if (j_val) (*env)->DeleteLocalRef(env, j_val);

    release_jni_env(should_detach);
    return 0;
}

static int lua_api_get_cookies(lua_State *L) {
    lua_bridge_holder_t *holder = get_bridge_holder(L);
    if (!holder || !holder->bridge_ref || !holder->get_cookies_mid) {
        lua_pushnil(L);
        return 1;
    }
    const char *url = luaL_checkstring(L, 1);
    int should_detach = 0;
    JNIEnv *env = get_jni_env(&should_detach);
    if (!env) { lua_pushnil(L); return 1; }

    jstring j_url = (*env)->NewStringUTF(env, url);
    jstring res = (jstring) (*env)->CallObjectMethod(env, holder->bridge_ref, holder->get_cookies_mid, j_url);
    (*env)->DeleteLocalRef(env, j_url);

    if (res) {
        const char *str = (*env)->GetStringUTFChars(env, res, NULL);
        if (str) {
            lua_pushstring(L, str);
            (*env)->ReleaseStringUTFChars(env, res, str);
        } else {
            lua_pushnil(L);
        }
        (*env)->DeleteLocalRef(env, res);
    } else {
        lua_pushnil(L);
    }

    release_jni_env(should_detach);
    return 1;
}

static int lua_api_set_cookies(lua_State *L) {
    lua_bridge_holder_t *holder = get_bridge_holder(L);
    if (!holder || !holder->bridge_ref || !holder->set_cookies_mid) return 0;

    const char *url = luaL_checkstring(L, 1);
    json_buf_t buf;
    json_buf_init(&buf);
    lua_to_json(L, 2, &buf);

    int should_detach = 0;
    JNIEnv *env = get_jni_env(&should_detach);
    if (!env) {
        free(buf.data);
        return 0;
    }

    jstring j_url = (*env)->NewStringUTF(env, url);
    jstring j_cookies = (*env)->NewStringUTF(env, buf.data ? buf.data : "{}");
    free(buf.data);

    (*env)->CallVoidMethod(env, holder->bridge_ref, holder->set_cookies_mid, j_url, j_cookies);

    (*env)->DeleteLocalRef(env, j_url);
    (*env)->DeleteLocalRef(env, j_cookies);

    release_jni_env(should_detach);
    return 0;
}

static int lua_api_get_local_storage(lua_State *L) {
    lua_bridge_holder_t *holder = get_bridge_holder(L);
    if (!holder || !holder->bridge_ref || !holder->get_storage_mid) {
        lua_pushnil(L);
        return 1;
    }
    const char *url = luaL_checkstring(L, 1);
    const char *key = luaL_checkstring(L, 2);

    int should_detach = 0;
    JNIEnv *env = get_jni_env(&should_detach);
    if (!env) { lua_pushnil(L); return 1; }

    jstring j_url = (*env)->NewStringUTF(env, url);
    jstring j_key = (*env)->NewStringUTF(env, key);

    jstring res = (jstring) (*env)->CallObjectMethod(env, holder->bridge_ref, holder->get_storage_mid, j_url, j_key);

    (*env)->DeleteLocalRef(env, j_url);
    (*env)->DeleteLocalRef(env, j_key);

    if (res) {
        const char *str = (*env)->GetStringUTFChars(env, res, NULL);
        if (str) {
            json_to_lua(L, str);
            (*env)->ReleaseStringUTFChars(env, res, str);
        } else {
            lua_pushnil(L);
        }
        (*env)->DeleteLocalRef(env, res);
    } else {
        lua_pushnil(L);
    }

    release_jni_env(should_detach);
    return 1;
}

static int lua_api_show_error(lua_State *L) {
    lua_bridge_holder_t *holder = get_bridge_holder(L);
    if (!holder || !holder->bridge_ref || !holder->show_error_mid) return 0;

    const char *title = luaL_checkstring(L, 1);
    const char *msg = luaL_checkstring(L, 2);
    const char *auth_url = luaL_optstring(L, 3, NULL);

    int should_detach = 0;
    JNIEnv *env = get_jni_env(&should_detach);
    if (!env) return 0;

    jstring j_title = (*env)->NewStringUTF(env, title);
    jstring j_msg = (*env)->NewStringUTF(env, msg);
    jstring j_auth = auth_url ? (*env)->NewStringUTF(env, auth_url) : NULL;

    (*env)->CallVoidMethod(env, holder->bridge_ref, holder->show_error_mid, j_title, j_msg, j_auth);

    (*env)->DeleteLocalRef(env, j_title);
    (*env)->DeleteLocalRef(env, j_msg);
    if (j_auth) (*env)->DeleteLocalRef(env, j_auth);

    release_jni_env(should_detach);
    return 0;
}

static int lua_api_log_info(lua_State *L) {
    const char *msg = luaL_optstring(L, 1, "");
    LOGI("%s", msg);
    lua_bridge_holder_t *holder = get_bridge_holder(L);
    if (holder && holder->bridge_ref && holder->log_mid) {
        int should_detach = 0;
        JNIEnv *env = get_jni_env(&should_detach);
        if (env) {
            jstring j_msg = (*env)->NewStringUTF(env, msg);
            (*env)->CallVoidMethod(env, holder->bridge_ref, holder->log_mid, (jint) 1, j_msg);
            (*env)->DeleteLocalRef(env, j_msg);
            release_jni_env(should_detach);
        }
    }
    return 0;
}

static int lua_api_log_error(lua_State *L) {
    const char *msg = luaL_optstring(L, 1, "");
    LOGE("%s", msg);
    lua_bridge_holder_t *holder = get_bridge_holder(L);
    if (holder && holder->bridge_ref && holder->log_mid) {
        int should_detach = 0;
        JNIEnv *env = get_jni_env(&should_detach);
        if (env) {
            jstring j_msg = (*env)->NewStringUTF(env, msg);
            (*env)->CallVoidMethod(env, holder->bridge_ref, holder->log_mid, (jint) 2, j_msg);
            (*env)->DeleteLocalRef(env, j_msg);
            release_jni_env(should_detach);
        }
    }
    return 0;
}

// -----------------------------------------------------------------------------
// Sandboxing & API Registration
// -----------------------------------------------------------------------------

static void sandbox_lua_state(lua_State *L) {
    lua_pushnil(L);
    lua_setglobal(L, "io");
    lua_pushnil(L);
    lua_setglobal(L, "package");
    lua_pushnil(L);
    lua_setglobal(L, "debug");
    lua_pushnil(L);
    lua_setglobal(L, "loadfile");
    lua_pushnil(L);
    lua_setglobal(L, "dofile");

    lua_getglobal(L, "os");
    if (lua_istable(L, -1)) {
        lua_pushnil(L);
        lua_setfield(L, -2, "execute");
        lua_pushnil(L);
        lua_setfield(L, -2, "exit");
        lua_pushnil(L);
        lua_setfield(L, -2, "getenv");
        lua_pushnil(L);
        lua_setfield(L, -2, "rename");
        lua_pushnil(L);
        lua_setfield(L, -2, "remove");
        lua_pushnil(L);
        lua_setfield(L, -2, "tmpname");
    }
    lua_pop(L, 1);
}

static const luaL_Reg bridge_api_funcs[] = {
    {"http_get",           lua_api_http_get},
    {"http_post",          lua_api_http_post},
    {"http_get_batch",     lua_api_http_get_batch},
    {"get_preference",     lua_api_get_preference},
    {"set_preference",     lua_api_set_preference},
    {"get_cookies",        lua_api_get_cookies},
    {"set_cookies",        lua_api_set_cookies},
    {"get_localStorage",   lua_api_get_local_storage},
    {"show_error",         lua_api_show_error},
    {"log_info",           lua_api_log_info},
    {"log_error",          lua_api_log_error},
    {"sleep",              lua_api_sleep},
    {"json_parse",         lua_api_json_parse},
    {"json_stringify",     lua_api_json_stringify},
    {"base64_encode",      lua_api_base64_encode},
    {"base64_decode",      lua_api_base64_decode},
    {"string_trim",        lua_api_string_trim},
    {"string_starts_with", lua_api_string_starts_with},
    {"string_ends_with",   lua_api_string_ends_with},
    {"string_split",       lua_api_string_split},
    {"string_clean",       lua_api_string_clean},
    {"os_time",            lua_api_os_time},
    {NULL, NULL}
};

static void register_bridge_apis(lua_State *L) {
    lua_pushglobaltable(L);
    luaL_setfuncs(L, bridge_api_funcs, 0);
    lua_pop(L, 1);
}

// -----------------------------------------------------------------------------
// JNI Exported Functions
// -----------------------------------------------------------------------------

JNIEXPORT jlong JNICALL
Java_my_noveldokusha_tooling_native_1scraper_LuaNative_nativeCreateState(
    JNIEnv *env, jclass clazz, jobject bridge)
{
    lua_State *L = luaL_newstate();
    if (!L) return 0;

    luaL_openlibs(L);
    sandbox_lua_state(L);
    luaopen_novela_html(L);

    lua_bridge_holder_t *holder = (lua_bridge_holder_t *) calloc(1, sizeof(lua_bridge_holder_t));
    if (holder && bridge) {
        holder->bridge_ref = (*env)->NewGlobalRef(env, bridge);
        jclass bclass = (*env)->GetObjectClass(env, bridge);
        if (bclass) {
            holder->http_get_mid       = (*env)->GetMethodID(env, bclass, "httpGet", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
            holder->http_post_mid      = (*env)->GetMethodID(env, bclass, "httpPost", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
            holder->http_get_batch_mid = (*env)->GetMethodID(env, bclass, "httpGetBatch", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
            holder->get_pref_mid       = (*env)->GetMethodID(env, bclass, "getPreference", "(Ljava/lang/String;)Ljava/lang/String;");
            holder->set_pref_mid       = (*env)->GetMethodID(env, bclass, "setPreference", "(Ljava/lang/String;Ljava/lang/String;)V");
            holder->get_cookies_mid    = (*env)->GetMethodID(env, bclass, "getCookies", "(Ljava/lang/String;)Ljava/lang/String;");
            holder->set_cookies_mid    = (*env)->GetMethodID(env, bclass, "setCookies", "(Ljava/lang/String;Ljava/lang/String;)V");
            holder->get_storage_mid    = (*env)->GetMethodID(env, bclass, "getLocalStorage", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
            holder->show_error_mid     = (*env)->GetMethodID(env, bclass, "showError", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
            holder->log_mid            = (*env)->GetMethodID(env, bclass, "log", "(ILjava/lang/String;)V");
            holder->sleep_mid          = (*env)->GetMethodID(env, bclass, "sleep", "(J)V");
        }
    }

    *(lua_bridge_holder_t **) lua_getextraspace(L) = holder;
    register_bridge_apis(L);

    return (jlong) (intptr_t) L;
}

JNIEXPORT void JNICALL
Java_my_noveldokusha_tooling_native_1scraper_LuaNative_nativeCloseState(
    JNIEnv *env, jclass clazz, jlong state_ptr)
{
    if (!state_ptr) return;
    lua_State *L = (lua_State *) (intptr_t) state_ptr;

    lua_bridge_holder_t *holder = get_bridge_holder(L);
    if (holder) {
        if (holder->bridge_ref) {
            (*env)->DeleteGlobalRef(env, holder->bridge_ref);
        }
        free(holder);
        *(lua_bridge_holder_t **) lua_getextraspace(L) = NULL;
    }

    lua_close(L);
}

JNIEXPORT jstring JNICALL
Java_my_noveldokusha_tooling_native_1scraper_LuaNative_nativeExecuteString(
    JNIEnv *env, jclass clazz, jlong state_ptr, jstring code_str)
{
    if (!state_ptr || !code_str) return (*env)->NewStringUTF(env, "Invalid arguments");
    lua_State *L = (lua_State *) (intptr_t) state_ptr;

    const char *code = (*env)->GetStringUTFChars(env, code_str, NULL);
    if (!code) return (*env)->NewStringUTF(env, "Failed to get code string");

    int status = luaL_dostring(L, code);
    (*env)->ReleaseStringUTFChars(env, code_str, code);

    if (status != LUA_OK) {
        const char *err = lua_tostring(L, -1);
        jstring res = (*env)->NewStringUTF(env, err ? err : "Unknown Lua error");
        lua_pop(L, 1);
        return res;
    }

    return NULL;
}

JNIEXPORT jboolean JNICALL
Java_my_noveldokusha_tooling_native_1scraper_LuaNative_nativeHasFunction(
    JNIEnv *env, jclass clazz, jlong state_ptr, jstring fn_name)
{
    if (!state_ptr || !fn_name) return JNI_FALSE;
    lua_State *L = (lua_State *) (intptr_t) state_ptr;
    const char *name = (*env)->GetStringUTFChars(env, fn_name, NULL);
    if (!name) return JNI_FALSE;
    lua_getglobal(L, name);
    int is_fn = lua_isfunction(L, -1);
    lua_pop(L, 1);
    (*env)->ReleaseStringUTFChars(env, fn_name, name);
    return is_fn ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_my_noveldokusha_tooling_native_1scraper_LuaNative_nativeCall(
    JNIEnv *env, jclass clazz, jlong state_ptr, jstring fn_name, jstring args_json)
{
    if (!state_ptr || !fn_name) return NULL;
    lua_State *L = (lua_State *) (intptr_t) state_ptr;

    const char *name = (*env)->GetStringUTFChars(env, fn_name, NULL);
    if (!name) return NULL;
    lua_getglobal(L, name);
    (*env)->ReleaseStringUTFChars(env, fn_name, name);

    if (!lua_isfunction(L, -1)) {
        lua_pop(L, 1);
        return NULL;
    }

    int nargs = 0;
    if (args_json) {
        const char *json_str = (*env)->GetStringUTFChars(env, args_json, NULL);
        if (json_str) {
            int top_before = lua_gettop(L);
            if (json_to_lua(L, json_str) && lua_istable(L, -1)) {
                int tbl_idx = lua_gettop(L);
                nargs = (int) lua_rawlen(L, tbl_idx);
                for (int i = 1; i <= nargs; i++) {
                    lua_rawgeti(L, tbl_idx, i);
                }
                lua_remove(L, tbl_idx);
            } else {
                while (lua_gettop(L) > top_before) {
                    lua_pop(L, 1);
                }
            }
            (*env)->ReleaseStringUTFChars(env, args_json, json_str);
        }
    }

    if (lua_pcall(L, nargs, 1, 0) != LUA_OK) {
        const char *err = lua_tostring(L, -1);
        LOGE("Lua call failed: %s", err ? err : "unknown");
        lua_pop(L, 1);
        return NULL;
    }

    int res_type = lua_type(L, -1);
    jstring res = NULL;

    if (res_type == LUA_TNIL) {
        lua_pop(L, 1);
        return NULL;
    } else if (res_type == LUA_TTABLE) {
        json_buf_t buf;
        json_buf_init(&buf);
        lua_to_json(L, -1, &buf);
        lua_pop(L, 1);
        res = (*env)->NewStringUTF(env, buf.data ? buf.data : "{}");
        if (buf.data) free(buf.data);
    } else if (res_type == LUA_TSTRING) {
        size_t len = 0;
        const char *s = lua_tolstring(L, -1, &len);
        res = s ? (*env)->NewStringUTF(env, s) : NULL;
        lua_pop(L, 1);
    } else if (res_type == LUA_TNUMBER) {
        const char *s = lua_tostring(L, -1);
        res = s ? (*env)->NewStringUTF(env, s) : NULL;
        lua_pop(L, 1);
    } else if (res_type == LUA_TBOOLEAN) {
        res = (*env)->NewStringUTF(env, lua_toboolean(L, -1) ? "true" : "false");
        lua_pop(L, 1);
    } else {
        lua_pop(L, 1);
    }

    return res;
}

JNIEXPORT jstring JNICALL
Java_my_noveldokusha_tooling_native_1scraper_LuaNative_nativeCallFunctionJson(
    JNIEnv *env, jclass clazz, jlong state_ptr, jstring fn_name, jobjectArray args)
{
    if (!state_ptr || !fn_name) return NULL;
    lua_State *L = (lua_State *) (intptr_t) state_ptr;

    const char *name = (*env)->GetStringUTFChars(env, fn_name, NULL);
    lua_getglobal(L, name);
    (*env)->ReleaseStringUTFChars(env, fn_name, name);

    if (!lua_isfunction(L, -1)) {
        lua_pop(L, 1);
        return NULL;
    }

    int nargs = 0;
    if (args) {
        nargs = (*env)->GetArrayLength(env, args);
        for (int i = 0; i < nargs; i++) {
            jstring arg = (jstring) (*env)->GetObjectArrayElement(env, args, i);
            if (arg) {
                const char *arg_str = (*env)->GetStringUTFChars(env, arg, NULL);
                lua_pushstring(L, arg_str);
                (*env)->ReleaseStringUTFChars(env, arg, arg_str);
                (*env)->DeleteLocalRef(env, arg);
            } else {
                lua_pushnil(L);
            }
        }
    }

    if (lua_pcall(L, nargs, 1, 0) != LUA_OK) {
        const char *err = lua_tostring(L, -1);
        LOGE("Lua function execution error: %s", err ? err : "Unknown");
        lua_pop(L, 1);
        return NULL;
    }

    json_buf_t buf;
    json_buf_init(&buf);
    lua_to_json(L, -1, &buf);
    lua_pop(L, 1);

    jstring res = (*env)->NewStringUTF(env, buf.data ? buf.data : "null");
    free(buf.data);
    return res;
}

JNIEXPORT jstring JNICALL
Java_my_noveldokusha_tooling_native_1scraper_LuaNative_nativeCallFunctionString(
    JNIEnv *env, jclass clazz, jlong state_ptr, jstring fn_name, jstring arg_str)
{
    if (!state_ptr || !fn_name) return NULL;
    lua_State *L = (lua_State *) (intptr_t) state_ptr;

    const char *name = (*env)->GetStringUTFChars(env, fn_name, NULL);
    lua_getglobal(L, name);
    (*env)->ReleaseStringUTFChars(env, fn_name, name);

    if (!lua_isfunction(L, -1)) {
        lua_pop(L, 1);
        return NULL;
    }

    int nargs = 0;
    if (arg_str) {
        const char *arg = (*env)->GetStringUTFChars(env, arg_str, NULL);
        lua_pushstring(L, arg);
        (*env)->ReleaseStringUTFChars(env, arg_str, arg);
        nargs = 1;
    }

    if (lua_pcall(L, nargs, 1, 0) != LUA_OK) {
        const char *err = lua_tostring(L, -1);
        LOGE("Lua function error: %s", err ? err : "Unknown");
        lua_pop(L, 1);
        return NULL;
    }

    if (lua_isnil(L, -1)) {
        lua_pop(L, 1);
        return NULL;
    }

    const char *result_str = lua_tostring(L, -1);
    jstring res = result_str ? (*env)->NewStringUTF(env, result_str) : NULL;
    lua_pop(L, 1);
    return res;
}
