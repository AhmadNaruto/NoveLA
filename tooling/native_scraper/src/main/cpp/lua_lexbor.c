#include "lua_lexbor.h"
#include <stdlib.h>
#include <string.h>

#include <lexbor/html/html.h>
#include <lexbor/css/css.h>
#include <lexbor/selectors/selectors.h>
#include <lexbor/dom/interfaces/element.h>
#include <lexbor/dom/interfaces/node.h>
#include <lexbor/html/interfaces/document.h>

#define LEXBOR_DOC_MT "LexborDocumentMT"
#define LEXBOR_NODE_MT "LexborNodeMT"

typedef struct {
    lxb_html_document_t *document;
} lexbor_doc_ud_t;

typedef struct {
    lxb_dom_node_t *node;
    int doc_ref; // Lua registry reference to document userdata
} lexbor_node_ud_t;

typedef struct {
    lxb_dom_node_t **nodes;
    size_t count;
    size_t capacity;
    bool find_first_only;
} lua_select_ctx_t;

static lxb_status_t lua_select_cb(lxb_dom_node_t *node, lxb_css_selector_specificity_t spec, void *ctx) {
    lua_select_ctx_t *sctx = (lua_select_ctx_t *) ctx;
    if (sctx->find_first_only && sctx->count > 0) {
        return LXB_STATUS_OK;
    }
    if (sctx->count >= sctx->capacity) {
        size_t new_cap = sctx->capacity == 0 ? 16 : sctx->capacity * 2;
        lxb_dom_node_t **new_nodes = realloc(sctx->nodes, new_cap * sizeof(lxb_dom_node_t *));
        if (!new_nodes) return LXB_STATUS_ERROR_MEMORY_ALLOCATION;
        sctx->nodes = new_nodes;
        sctx->capacity = new_cap;
    }
    sctx->nodes[sctx->count++] = node;
    return LXB_STATUS_OK;
}

static int lua_doc_gc(lua_State *L) {
    lexbor_doc_ud_t *ud = (lexbor_doc_ud_t *) luaL_checkudata(L, 1, LEXBOR_DOC_MT);
    if (ud && ud->document) {
        lxb_html_document_destroy(ud->document);
        ud->document = NULL;
    }
    return 0;
}

static int lua_node_gc(lua_State *L) {
    lexbor_node_ud_t *ud = (lexbor_node_ud_t *) luaL_checkudata(L, 1, LEXBOR_NODE_MT);
    if (ud && ud->doc_ref != LUA_NOREF) {
        luaL_unref(L, LUA_REGISTRYINDEX, ud->doc_ref);
        ud->doc_ref = LUA_NOREF;
    }
    return 0;
}

// Forward declaration
static void push_element_table(lua_State *L, lxb_dom_node_t *node, int doc_idx, int existing_doc_ref);
static int execute_select(lua_State *L, lxb_dom_node_t *root_node, const char *css_query, bool find_first, int doc_idx, int existing_doc_ref);

// Helper: Extract text from node
static void push_node_text(lua_State *L, lxb_dom_node_t *node) {
    if (!node) {
        lua_pushliteral(L, "");
        return;
    }
    size_t len = 0;
    lxb_char_t *text = lxb_dom_node_text_content(node, &len);
    if (text) {
        lua_pushlstring(L, (const char *) text, len);
        lxb_dom_document_destroy_text(node->owner_document, text);
    } else {
        lua_pushliteral(L, "");
    }
}

// Helper: Extract inner HTML from node
static void push_node_inner_html(lua_State *L, lxb_dom_node_t *node) {
    if (!node) {
        lua_pushliteral(L, "");
        return;
    }
    lexbor_str_t str = {0};
    lxb_status_t status = lxb_html_serialize_tree_str(node, &str);
    if (status == LXB_STATUS_OK && str.data) {
        lua_pushlstring(L, (const char *) str.data, str.length);
        lexbor_str_destroy(&str, node->owner_document->text, false);
    } else {
        lua_pushliteral(L, "");
    }
}

// Helper: Extract outer HTML from node
static void push_node_outer_html(lua_State *L, lxb_dom_node_t *node) {
    if (!node) {
        lua_pushliteral(L, "");
        return;
    }
    lexbor_str_t str = {0};
    lxb_status_t status = lxb_html_serialize_deep_str(node, &str);
    if (status == LXB_STATUS_OK && str.data) {
        lua_pushlstring(L, (const char *) str.data, str.length);
        lexbor_str_destroy(&str, node->owner_document->text, false);
    } else {
        lua_pushliteral(L, "");
    }
}

// Helper: Extract attribute
static void push_node_attr(lua_State *L, lxb_dom_node_t *node, const char *name) {
    if (!node || !name || node->type != LXB_DOM_NODE_TYPE_ELEMENT) {
        lua_pushliteral(L, "");
        return;
    }
    lxb_dom_element_t *el = lxb_dom_interface_element(node);
    size_t val_len = 0;
    const lxb_char_t *val = lxb_dom_element_get_attribute(el, (const lxb_char_t *) name, strlen(name), &val_len);
    if (val) {
        lua_pushlstring(L, (const char *) val, val_len);
    } else {
        lua_pushliteral(L, "");
    }
}

static int get_element_node(lua_State *L, lexbor_node_ud_t **out_nud) {
    int tbl_idx = lua_upvalueindex(1);
    if (!lua_istable(L, tbl_idx)) {
        if (lua_istable(L, 1)) {
            tbl_idx = 1;
        } else {
            return 0;
        }
    }
    lua_getfield(L, tbl_idx, "__node");
    *out_nud = (lexbor_node_ud_t *) luaL_testudata(L, -1, LEXBOR_NODE_MT);
    lua_pop(L, 1);
    return (*out_nud && (*out_nud)->node) ? 1 : 0;
}

// Method: element:attr(name) or element.attr(name)
static int lua_el_attr(lua_State *L) {
    lexbor_node_ud_t *nud = NULL;
    if (!get_element_node(L, &nud)) {
        lua_pushliteral(L, "");
        return 1;
    }
    int top = lua_gettop(L);
    const char *attr_name = NULL;
    if (top >= 2 && lua_istable(L, 1)) {
        attr_name = luaL_checkstring(L, 2);
    } else if (top >= 1) {
        attr_name = luaL_checkstring(L, 1);
    }
    if (attr_name) {
        push_node_attr(L, nud->node, attr_name);
    } else {
        lua_pushliteral(L, "");
    }
    return 1;
}

// Method: element:text() or element.text()
static int lua_el_text(lua_State *L) {
    lexbor_node_ud_t *nud = NULL;
    if (get_element_node(L, &nud)) {
        push_node_text(L, nud->node);
    } else {
        lua_pushliteral(L, "");
    }
    return 1;
}

// Method: element:html() or element.html()
static int lua_el_html(lua_State *L) {
    lexbor_node_ud_t *nud = NULL;
    if (get_element_node(L, &nud)) {
        push_node_inner_html(L, nud->node);
    } else {
        lua_pushliteral(L, "");
    }
    return 1;
}

// Method: element:remove()
static int lua_el_remove(lua_State *L) {
    lexbor_node_ud_t *nud = NULL;
    if (get_element_node(L, &nud)) {
        lxb_dom_node_remove(nud->node);
    }
    return 0;
}

// Method: element:select(query) or element.select(query)
static int lua_el_select(lua_State *L) {
    lexbor_node_ud_t *nud = NULL;
    if (!get_element_node(L, &nud)) {
        lua_newtable(L);
        return 1;
    }
    int top = lua_gettop(L);
    const char *query = NULL;
    if (top >= 2 && lua_istable(L, 1)) {
        query = luaL_checkstring(L, 2);
    } else if (top >= 1) {
        query = luaL_checkstring(L, 1);
    }
    if (query) {
        return execute_select(L, nud->node, query, false, 0, nud->doc_ref);
    }
    lua_newtable(L);
    return 1;
}

// Method: element:select_first(query) or element.select_first(query)
static int lua_el_select_first(lua_State *L) {
    lexbor_node_ud_t *nud = NULL;
    if (!get_element_node(L, &nud)) {
        lua_pushnil(L);
        return 1;
    }
    int top = lua_gettop(L);
    const char *query = NULL;
    if (top >= 2 && lua_istable(L, 1)) {
        query = luaL_checkstring(L, 2);
    } else if (top >= 1) {
        query = luaL_checkstring(L, 1);
    }
    if (query) {
        return execute_select(L, nud->node, query, true, 0, nud->doc_ref);
    }
    lua_pushnil(L);
    return 1;
}

// Element metatable __index for lazy evaluation & methods
static int lua_el_index(lua_State *L) {
    // 1 = table, 2 = key
    const char *key = luaL_checkstring(L, 2);

    lua_getfield(L, 1, "__node");
    lexbor_node_ud_t *nud = (lexbor_node_ud_t *) luaL_testudata(L, -1, LEXBOR_NODE_MT);
    lua_pop(L, 1); // pop __node userdata

    if (!nud || !nud->node) {
        lua_pushnil(L);
        return 1;
    }

    if (strcmp(key, "text") == 0) {
        push_node_text(L, nud->node);
        return 1;
    } else if (strcmp(key, "html") == 0) {
        push_node_inner_html(L, nud->node);
        return 1;
    } else if (strcmp(key, "href") == 0) {
        push_node_attr(L, nud->node, "href");
        return 1;
    } else if (strcmp(key, "src") == 0) {
        push_node_attr(L, nud->node, "src");
        return 1;
    } else if (strcmp(key, "title") == 0) {
        push_node_attr(L, nud->node, "title");
        return 1;
    } else if (strcmp(key, "class") == 0) {
        push_node_attr(L, nud->node, "class");
        return 1;
    } else if (strcmp(key, "id") == 0) {
        push_node_attr(L, nud->node, "id");
        return 1;
    } else if (strcmp(key, "attr") == 0) {
        lua_pushvalue(L, 1);
        lua_pushcclosure(L, lua_el_attr, 1);
        return 1;
    } else if (strcmp(key, "select") == 0) {
        lua_pushvalue(L, 1);
        lua_pushcclosure(L, lua_el_select, 1);
        return 1;
    } else if (strcmp(key, "select_first") == 0) {
        lua_pushvalue(L, 1);
        lua_pushcclosure(L, lua_el_select_first, 1);
        return 1;
    } else if (strcmp(key, "get_text") == 0) {
        lua_pushvalue(L, 1);
        lua_pushcclosure(L, lua_el_text, 1);
        return 1;
    } else if (strcmp(key, "get_html") == 0) {
        lua_pushvalue(L, 1);
        lua_pushcclosure(L, lua_el_html, 1);
        return 1;
    } else if (strcmp(key, "remove") == 0) {
        lua_pushvalue(L, 1);
        lua_pushcclosure(L, lua_el_remove, 1);
        return 1;
    }

    lua_pushnil(L);
    return 1;
}

static void push_element_table(lua_State *L, lxb_dom_node_t *node, int doc_idx, int existing_doc_ref) {
    lua_createtable(L, 0, 4);

    // Create node userdata
    lexbor_node_ud_t *nud = (lexbor_node_ud_t *) lua_newuserdatauv(L, sizeof(lexbor_node_ud_t), 0);
    nud->node = node;
    nud->doc_ref = LUA_NOREF;

    if (existing_doc_ref != LUA_NOREF) {
        lua_rawgeti(L, LUA_REGISTRYINDEX, existing_doc_ref);
        nud->doc_ref = luaL_ref(L, LUA_REGISTRYINDEX);
    } else if (doc_idx != 0) {
        lua_pushvalue(L, doc_idx);
        nud->doc_ref = luaL_ref(L, LUA_REGISTRYINDEX);
    }

    luaL_getmetatable(L, LEXBOR_NODE_MT);
    lua_setmetatable(L, -2);
    lua_setfield(L, -2, "__node");

    // Set metatable with lazy __index
    lua_createtable(L, 0, 1);
    lua_pushcfunction(L, lua_el_index);
    lua_setfield(L, -2, "__index");
    lua_setmetatable(L, -2);
}

// Core selector query execution
static int execute_select(lua_State *L, lxb_dom_node_t *root_node, const char *css_query, bool find_first, int doc_idx, int existing_doc_ref) {
    if (!root_node || !css_query) {
        if (find_first) lua_pushnil(L);
        else lua_createtable(L, 0, 0);
        return 1;
    }

    size_t qlen = strlen(css_query);
    lxb_css_parser_t *parser = lxb_css_parser_create();
    lxb_css_parser_init(parser, NULL);

    lxb_selectors_t *selectors = lxb_selectors_create();
    lxb_selectors_init(selectors);

    lxb_css_selector_list_t *list = lxb_css_selectors_parse(parser, (const lxb_char_t *) css_query, qlen);
    if (!list || parser->status != LXB_STATUS_OK) {
        lxb_selectors_destroy(selectors, true);
        lxb_css_parser_destroy(parser, true);
        if (find_first) lua_pushnil(L);
        else lua_createtable(L, 0, 0);
        return 1;
    }

    lua_select_ctx_t ctx = { .nodes = NULL, .count = 0, .capacity = 0, .find_first_only = find_first };
    lxb_status_t status = lxb_selectors_find(selectors, root_node, list, lua_select_cb, &ctx);

    lxb_selectors_destroy(selectors, true);
    lxb_css_parser_destroy(parser, true);
    lxb_css_selector_list_destroy_memory(list);

    if (status != LXB_STATUS_OK || ctx.count == 0) {
        free(ctx.nodes);
        if (find_first) lua_pushnil(L);
        else lua_createtable(L, 0, 0);
        return 1;
    }

    if (find_first) {
        push_element_table(L, ctx.nodes[0], doc_idx, existing_doc_ref);
        free(ctx.nodes);
        return 1;
    }

    lua_createtable(L, (int) ctx.count, 0);
    for (size_t i = 0; i < ctx.count; i++) {
        push_element_table(L, ctx.nodes[i], doc_idx, existing_doc_ref);
        lua_rawseti(L, -2, (lua_Integer) (i + 1));
    }
    free(ctx.nodes);
    return 1;
}

// Global: html_parse(html) -> { text, html, title, body }
static int lua_html_parse(lua_State *L) {
    size_t html_len = 0;
    const char *html = luaL_checklstring(L, 1, &html_len);

    lxb_html_document_t *document = lxb_html_document_create();
    if (!document) {
        lua_pushnil(L);
        return 1;
    }

    lxb_status_t status = lxb_html_document_parse(document, (const lxb_char_t *) html, html_len);
    if (status != LXB_STATUS_OK) {
        lxb_html_document_destroy(document);
        lua_pushnil(L);
        return 1;
    }

    // Document userdata
    lexbor_doc_ud_t *dud = (lexbor_doc_ud_t *) lua_newuserdatauv(L, sizeof(lexbor_doc_ud_t), 0);
    dud->document = document;
    luaL_getmetatable(L, LEXBOR_DOC_MT);
    lua_setmetatable(L, -2);
    int doc_idx = lua_gettop(L);

    lxb_dom_node_t *root = lxb_dom_interface_node(document);
    lxb_html_body_element_t *body_el = lxb_html_document_body_element(document);
    lxb_dom_node_t *body_node = body_el ? lxb_dom_interface_node(body_el) : root;

    lua_createtable(L, 0, 4);

    push_node_text(L, body_node);
    lua_setfield(L, -2, "text");

    push_node_inner_html(L, body_node);
    lua_setfield(L, -2, "html");

    size_t tlen = 0;
    const lxb_char_t *title = lxb_html_document_title(document, &tlen);
    if (title) {
        lua_pushlstring(L, (const char *) title, tlen);
    } else {
        lua_pushliteral(L, "");
    }
    lua_setfield(L, -2, "title");

    push_element_table(L, body_node, doc_idx, LUA_NOREF);
    lua_setfield(L, -2, "body");

    return 1;
}

// Global: html_select(html_or_el, css_query)
static int lua_html_select(lua_State *L) {
    const char *query = luaL_checkstring(L, 2);

    if (lua_istable(L, 1)) {
        lua_getfield(L, 1, "__node");
        lexbor_node_ud_t *nud = (lexbor_node_ud_t *) luaL_testudata(L, -1, LEXBOR_NODE_MT);
        lua_pop(L, 1);
        if (nud && nud->node) {
            return execute_select(L, nud->node, query, false, 0, nud->doc_ref);
        }
    }

    // String argument: parse and select
    size_t html_len = 0;
    const char *html = luaL_checklstring(L, 1, &html_len);
    lxb_html_document_t *document = lxb_html_document_create();
    if (!document) {
        lua_createtable(L, 0, 0);
        return 1;
    }
    lxb_html_document_parse(document, (const lxb_char_t *) html, html_len);

    lexbor_doc_ud_t *dud = (lexbor_doc_ud_t *) lua_newuserdatauv(L, sizeof(lexbor_doc_ud_t), 0);
    dud->document = document;
    luaL_getmetatable(L, LEXBOR_DOC_MT);
    lua_setmetatable(L, -2);
    int doc_idx = lua_gettop(L);

    return execute_select(L, lxb_dom_interface_node(document), query, false, doc_idx, LUA_NOREF);
}

// Global: html_select_first(html_or_el, css_query)
static int lua_html_select_first(lua_State *L) {
    const char *query = luaL_checkstring(L, 2);

    if (lua_istable(L, 1)) {
        lua_getfield(L, 1, "__node");
        lexbor_node_ud_t *nud = (lexbor_node_ud_t *) luaL_testudata(L, -1, LEXBOR_NODE_MT);
        lua_pop(L, 1);
        if (nud && nud->node) {
            return execute_select(L, nud->node, query, true, 0, nud->doc_ref);
        }
    }

    size_t html_len = 0;
    const char *html = luaL_checklstring(L, 1, &html_len);
    lxb_html_document_t *document = lxb_html_document_create();
    if (!document) {
        lua_pushnil(L);
        return 1;
    }
    lxb_html_document_parse(document, (const lxb_char_t *) html, html_len);

    lexbor_doc_ud_t *dud = (lexbor_doc_ud_t *) lua_newuserdatauv(L, sizeof(lexbor_doc_ud_t), 0);
    dud->document = document;
    luaL_getmetatable(L, LEXBOR_DOC_MT);
    lua_setmetatable(L, -2);
    int doc_idx = lua_gettop(L);

    return execute_select(L, lxb_dom_interface_node(document), query, true, doc_idx, LUA_NOREF);
}

// Global: html_attr(html_or_el, css_query, attr_name)
static int lua_html_attr(lua_State *L) {
    const char *query = luaL_checkstring(L, 2);
    const char *attr_name = luaL_checkstring(L, 3);

    // Run select first
    lua_html_select_first(L);
    if (lua_istable(L, -1)) {
        lua_getfield(L, -1, "attr");
        lua_pushvalue(L, -2); // self
        lua_pushstring(L, attr_name);
        lua_call(L, 2, 1);
        return 1;
    }
    lua_pushliteral(L, "");
    return 1;
}

// Global: html_text(html_or_el)
static int lua_html_text(lua_State *L) {
    if (lua_istable(L, 1)) {
        lua_getfield(L, 1, "__node");
        lexbor_node_ud_t *nud = (lexbor_node_ud_t *) luaL_testudata(L, -1, LEXBOR_NODE_MT);
        lua_pop(L, 1);
        if (nud && nud->node) {
            push_node_text(L, nud->node);
            return 1;
        }
    }

    size_t html_len = 0;
    const char *html = luaL_checklstring(L, 1, &html_len);
    lxb_html_document_t *document = lxb_html_document_create();
    if (!document) {
        lua_pushliteral(L, "");
        return 1;
    }
    lxb_html_document_parse(document, (const lxb_char_t *) html, html_len);
    push_node_text(L, lxb_dom_interface_node(document));
    lxb_html_document_destroy(document);
    return 1;
}

// Global: html_remove(html_or_el, sel1, sel2, ...)
static int lua_html_remove(lua_State *L) {
    int top = lua_gettop(L);
    if (top < 2) return 1;

    lxb_html_document_t *owned_doc = NULL;
    lxb_dom_node_t *root_node = NULL;

    if (lua_istable(L, 1)) {
        lua_getfield(L, 1, "__node");
        lexbor_node_ud_t *nud = (lexbor_node_ud_t *) luaL_testudata(L, -1, LEXBOR_NODE_MT);
        lua_pop(L, 1);
        if (nud && nud->node) {
            root_node = nud->node;
        }
    }

    if (!root_node) {
        size_t html_len = 0;
        const char *html = luaL_checklstring(L, 1, &html_len);
        owned_doc = lxb_html_document_create();
        if (!owned_doc) return 1;
        lxb_html_document_parse(owned_doc, (const lxb_char_t *) html, html_len);
        lxb_html_body_element_t *body = lxb_html_document_body_element(owned_doc);
        root_node = body ? lxb_dom_interface_node(body) : lxb_dom_interface_node(owned_doc);
    }

    lxb_css_parser_t *parser = lxb_css_parser_create();
    lxb_css_parser_init(parser, NULL);
    lxb_selectors_t *selectors = lxb_selectors_create();
    lxb_selectors_init(selectors);

    for (int i = 2; i <= top; i++) {
        const char *sel = luaL_optstring(L, i, NULL);
        if (!sel || strlen(sel) == 0) continue;

        lxb_css_selector_list_t *list = lxb_css_selectors_parse(parser, (const lxb_char_t *) sel, strlen(sel));
        if (list && parser->status == LXB_STATUS_OK) {
            lua_select_ctx_t ctx = { .nodes = NULL, .count = 0, .capacity = 0, .find_first_only = false };
            if (lxb_selectors_find(selectors, root_node, list, lua_select_cb, &ctx) == LXB_STATUS_OK) {
                for (size_t j = 0; j < ctx.count; j++) {
                    lxb_dom_node_remove(ctx.nodes[j]);
                }
            }
            free(ctx.nodes);
            lxb_css_selector_list_destroy_memory(list);
        }
    }

    lxb_selectors_destroy(selectors, true);
    lxb_css_parser_destroy(parser, true);

    // Return inner HTML of the modified tree
    push_node_inner_html(L, root_node);

    if (owned_doc) {
        lxb_html_document_destroy(owned_doc);
    }
    return 1;
}

static const luaL_Reg novela_html_funcs[] = {
    {"html_parse", lua_html_parse},
    {"html_select", lua_html_select},
    {"html_select_first", lua_html_select_first},
    {"html_attr", lua_html_attr},
    {"html_text", lua_html_text},
    {"html_remove", lua_html_remove},
    {NULL, NULL}
};

int luaopen_novela_html(lua_State *L) {
    // Document metatable
    luaL_newmetatable(L, LEXBOR_DOC_MT);
    lua_pushcfunction(L, lua_doc_gc);
    lua_setfield(L, -2, "__gc");
    lua_pop(L, 1);

    // Node metatable
    luaL_newmetatable(L, LEXBOR_NODE_MT);
    lua_pushcfunction(L, lua_node_gc);
    lua_setfield(L, -2, "__gc");
    lua_pop(L, 1);

    // Register global functions
    lua_pushglobaltable(L);
    luaL_setfuncs(L, novela_html_funcs, 0);
    lua_pop(L, 1);

    return 1;
}
