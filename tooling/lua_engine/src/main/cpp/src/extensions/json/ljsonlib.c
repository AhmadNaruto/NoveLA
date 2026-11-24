#define ljsonlib_c
#define LUA_LIB

#include "lprefix.h"
#include <stdlib.h>
#include <string.h>
#include "lua.h"
#include "lauxlib.h"
#include "lualib.h"
#include "cJSON.h"

#define MAX_JSON_DEPTH 128

static void cjson_to_lua(lua_State *L, const cJSON *item) {
    if (item == NULL || cJSON_IsNull(item) || cJSON_IsInvalid(item)) {
        lua_pushnil(L);
    } else if (cJSON_IsBool(item)) {
        lua_pushboolean(L, cJSON_IsTrue(item));
    } else if (cJSON_IsNumber(item)) {
        double d = item->valuedouble;
        if ((double)(lua_Integer)d == d) {
            lua_pushinteger(L, (lua_Integer)d);
        } else {
            lua_pushnumber(L, (lua_Number)d);
        }
    } else if (cJSON_IsString(item)) {
        lua_pushstring(L, item->valuestring ? item->valuestring : "");
    } else if (cJSON_IsArray(item)) {
        int count = cJSON_GetArraySize((cJSON *)item);
        lua_createtable(L, count, 0);
        int idx = 1;
        const cJSON *child = NULL;
        cJSON_ArrayForEach(child, item) {
            cjson_to_lua(L, child);
            lua_rawseti(L, -2, idx++);
        }
    } else if (cJSON_IsObject(item)) {
        lua_newtable(L);
        const cJSON *child = NULL;
        cJSON_ArrayForEach(child, item) {
            if (child->string != NULL) {
                lua_pushstring(L, child->string);
                cjson_to_lua(L, child);
                lua_rawset(L, -3);
            }
        }
    } else {
        lua_pushnil(L);
    }
}

static int is_lua_array(lua_State *L, int index, lua_Integer *out_len) {
    if (index < 0) index = lua_gettop(L) + index + 1;
    lua_Integer len = luaL_len(L, index);
    if (len <= 0) {
        lua_pushnil(L);
        if (lua_next(L, index) != 0) {
            lua_pop(L, 2);
            return 0;
        }
        *out_len = 0;
        return 1;
    }

    lua_rawgeti(L, index, 1);
    int has_one = !lua_isnil(L, -1);
    lua_pop(L, 1);
    if (!has_one) return 0;

    *out_len = len;
    return 1;
}

static cJSON *lua_to_cjson(lua_State *L, int index, int depth) {
    if (depth > MAX_JSON_DEPTH) {
        return cJSON_CreateString("<max recursion depth>");
    }
    if (index < 0) index = lua_gettop(L) + index + 1;

    int type = lua_type(L, index);
    switch (type) {
        case LUA_TNIL:
            return cJSON_CreateNull();
        case LUA_TBOOLEAN:
            return cJSON_CreateBool(lua_toboolean(L, index));
        case LUA_TNUMBER:
            if (lua_isinteger(L, index)) {
                return cJSON_CreateNumber((double)lua_tointeger(L, index));
            } else {
                return cJSON_CreateNumber(lua_tonumber(L, index));
            }
        case LUA_TSTRING:
            return cJSON_CreateString(lua_tostring(L, index));
        case LUA_TTABLE: {
            lua_Integer len = 0;
            if (is_lua_array(L, index, &len)) {
                cJSON *arr = cJSON_CreateArray();
                for (lua_Integer i = 1; i <= len; i++) {
                    lua_rawgeti(L, index, i);
                    cJSON *child = lua_to_cjson(L, -1, depth + 1);
                    lua_pop(L, 1);
                    cJSON_AddItemToArray(arr, child ? child : cJSON_CreateNull());
                }
                return arr;
            } else {
                cJSON *obj = cJSON_CreateObject();
                lua_pushnil(L);
                while (lua_next(L, index) != 0) {
                    const char *key = NULL;
                    char keybuf[32];
                    if (lua_type(L, -2) == LUA_TSTRING) {
                        key = lua_tostring(L, -2);
                    } else {
                        snprintf(keybuf, sizeof(keybuf), "%lld", (long long)lua_tointeger(L, -2));
                        key = keybuf;
                    }
                    cJSON *child = lua_to_cjson(L, -1, depth + 1);
                    cJSON_AddItemToObject(obj, key, child ? child : cJSON_CreateNull());
                    lua_pop(L, 1);
                }
                return obj;
            }
        }
        default:
            return cJSON_CreateNull();
    }
}

static int json_decode(lua_State *L) {
    size_t len = 0;
    const char *str = luaL_checklstring(L, 1, &len);
    cJSON *json = cJSON_ParseWithLength(str, len);
    if (json == NULL) {
        const char *err = cJSON_GetErrorPtr();
        lua_pushnil(L);
        if (err != NULL) {
            lua_pushfstring(L, "JSON parse error near: %.30s", err);
        } else {
            lua_pushliteral(L, "JSON parse error: invalid syntax");
        }
        return 2;
    }
    cjson_to_lua(L, json);
    cJSON_Delete(json);
    return 1;
}

static int json_encode(lua_State *L) {
    luaL_checkany(L, 1);
    int pretty = lua_toboolean(L, 2);
    cJSON *json = lua_to_cjson(L, 1, 0);
    if (json == NULL) {
        lua_pushnil(L);
        lua_pushliteral(L, "Failed to encode value to JSON");
        return 2;
    }
    char *str = pretty ? cJSON_Print(json) : cJSON_PrintUnformatted(json);
    cJSON_Delete(json);
    if (str == NULL) {
        lua_pushnil(L);
        lua_pushliteral(L, "Failed to render JSON string");
        return 2;
    }
    lua_pushstring(L, str);
    free(str);
    return 1;
}

static const struct luaL_Reg jsonlib[] = {
    {"decode", json_decode},
    {"encode", json_encode},
    {NULL, NULL}
};

LUAMOD_API int luaopen_json(lua_State *L) {
    luaL_newlib(L, jsonlib);
    return 1;
}
