#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <android/log.h>

#include "lua/lua.h"
#include "lua/lauxlib.h"
#include "lua/lualib.h"
#include "lua_lexbor.h"

#define LOG_TAG "NativeScraperLua"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

typedef struct {
    char *data;
    size_t length;
    size_t capacity;
} json_buf_t;

static void json_buf_init(json_buf_t *buf) {
    buf->capacity = 128;
    buf->length = 0;
    buf->data = malloc(buf->capacity);
    if (buf->data) buf->data[0] = '\0';
}

static void json_buf_append(json_buf_t *buf, const char *str) {
    if (!str) return;
    size_t slen = strlen(str);
    if (buf->length + slen + 1 >= buf->capacity) {
        size_t new_cap = (buf->capacity + slen) * 2;
        char *new_data = realloc(buf->data, new_cap);
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

JNIEXPORT jlong JNICALL
Java_my_noveldokusha_tooling_native_1scraper_LuaNative_nativeCreateState(
    JNIEnv *env, jclass clazz)
{
    lua_State *L = luaL_newstate();
    if (!L) return 0;

    luaL_openlibs(L);
    luaopen_novela_html(L);

    return (jlong) (intptr_t) L;
}

JNIEXPORT void JNICALL
Java_my_noveldokusha_tooling_native_1scraper_LuaNative_nativeCloseState(
    JNIEnv *env, jclass clazz, jlong state_ptr)
{
    if (!state_ptr) return;
    lua_State *L = (lua_State *) (intptr_t) state_ptr;
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

    return NULL; // Null indicates success
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
