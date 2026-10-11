#ifndef NOVELA_LUA_LEXBOR_H
#define NOVELA_LUA_LEXBOR_H

#include "lua/lua.h"
#include "lua/lauxlib.h"
#include "lua/lualib.h"

#ifdef __cplusplus
extern "C" {
#endif

int luaopen_novela_html(lua_State *L);

#ifdef __cplusplus
}
#endif

#endif // NOVELA_LUA_LEXBOR_H
