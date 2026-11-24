#define lhtmllib_c
#define LUA_LIB

#include "lprefix.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <ctype.h>
#include <stdbool.h>

#include "lua.h"
#include "lauxlib.h"
#include "lualib.h"

#include "lexbor.h"
#include "lhtmllib.h"

#define HTML_DOCUMENT_MT "html.document"
#define HTML_ELEMENT_MT  "html.element"
#define HTML_ELEMENTS_MT "html.elements"

typedef struct {
    lxb_html_document_t *doc;
    char *base_uri;
} LuaHtmlDocument;

typedef struct {
    lxb_dom_node_t *node;
} LuaHtmlElement;

/* Helper: extract LuaHtmlDocument or owner from stack */
static void push_owner_doc(lua_State *L, int idx) {
    if (luaL_testudata(L, idx, HTML_DOCUMENT_MT)) {
        lua_pushvalue(L, idx);
    } else if (luaL_testudata(L, idx, HTML_ELEMENT_MT)) {
        lua_getiuservalue(L, idx, 1);
    } else {
        lua_pushnil(L);
    }
}

/* Helper: resolve relative URL against base URI */
static void resolve_absolute_url(luaL_Buffer *b, const char *base, const char *rel, size_t rel_len) {
    if (!rel || rel_len == 0) return;
    if (strncmp(rel, "http://", 7) == 0 || strncmp(rel, "https://", 8) == 0 || strncmp(rel, "data:", 5) == 0) {
        luaL_addlstring(b, rel, rel_len);
        return;
    }
    if (strncmp(rel, "//", 2) == 0) {
        luaL_addstring(b, "https:");
        luaL_addlstring(b, rel, rel_len);
        return;
    }
    if (!base || base[0] == '\0') {
        luaL_addlstring(b, rel, rel_len);
        return;
    }

    if (rel[0] == '/') {
        const char *scheme_end = strstr(base, "://");
        if (scheme_end != NULL) {
            const char *slash = strchr(scheme_end + 3, '/');
            if (slash != NULL) {
                luaL_addlstring(b, base, slash - base);
            } else {
                luaL_addstring(b, base);
            }
        } else {
            luaL_addstring(b, base);
        }
        luaL_addlstring(b, rel, rel_len);
    } else {
        size_t blen = strlen(base);
        const char *last_slash = strrchr(base, '/');
        const char *scheme_end = strstr(base, "://");
        if (last_slash != NULL && (scheme_end == NULL || last_slash > scheme_end + 2)) {
            luaL_addlstring(b, base, last_slash - base + 1);
        } else {
            luaL_addstring(b, base);
            if (blen > 0 && base[blen - 1] != '/') {
                luaL_addchar(b, '/');
            }
        }
        luaL_addlstring(b, rel, rel_len);
    }
}

/* Helper: push LuaHtmlElement wrapping a DOM node */
static void push_html_element_with_owner(lua_State *L, lxb_dom_node_t *node, int owner_idx) {
    if (node == NULL) {
        lua_pushnil(L);
        return;
    }
    int abs_owner_idx = lua_absindex(L, owner_idx);
    LuaHtmlElement *el = (LuaHtmlElement *)lua_newuserdatauv(L, sizeof(LuaHtmlElement), 1);
    el->node = node;
    luaL_setmetatable(L, HTML_ELEMENT_MT);
    push_owner_doc(L, abs_owner_idx);
    lua_setiuservalue(L, -2, 1);
}

/* Helper: serialize callback into luaL_Buffer */
typedef struct {
    luaL_Buffer *b;
} SerializeCtx;

static lxb_status_t serialize_buffer_cb(const lxb_char_t *data, size_t len, void *ctx) {
    SerializeCtx *sctx = (SerializeCtx *)ctx;
    luaL_addlstring(sctx->b, (const char *)data, len);
    return LXB_STATUS_OK;
}

/* Helper: CSS Selectors matching context */
typedef struct {
    lua_State *L;
    int owner_idx;
    int table_idx;
    int count;
    bool first_only;
    lxb_dom_node_t *found_node;
} SelectCtx;

static lxb_status_t select_find_cb(lxb_dom_node_t *node, lxb_css_selector_specificity_t spec, void *ctx) {
    (void)spec;
    SelectCtx *sctx = (SelectCtx *)ctx;
    if (node->type != LXB_DOM_NODE_TYPE_ELEMENT) {
        return LXB_STATUS_OK;
    }
    if (sctx->first_only) {
        sctx->found_node = node;
        return LXB_STATUS_STOP;
    }
    sctx->count++;
    push_html_element_with_owner(sctx->L, node, sctx->owner_idx);
    lua_rawseti(sctx->L, sctx->table_idx, sctx->count);
    return LXB_STATUS_OK;
}

/* Helper: execute CSS query against root node */
static int run_css_selector(lua_State *L, lxb_dom_node_t *root_node, int owner_idx,
                            const char *css, size_t css_len, bool first_only) {
    if (!root_node || !css) {
        if (first_only) {
            lua_pushnil(L);
        } else {
            lua_newtable(L);
            luaL_setmetatable(L, HTML_ELEMENTS_MT);
        }
        return 1;
    }

    lxb_css_parser_t *css_parser = lxb_css_parser_create();
    lxb_status_t status = lxb_css_parser_init(css_parser, NULL);
    if (status != LXB_STATUS_OK) {
        lxb_css_parser_destroy(css_parser, true);
        return luaL_error(L, "html: failed to initialize css parser");
    }

    lxb_css_selector_list_t *list = lxb_css_selectors_parse(css_parser, (const lxb_char_t *)css, css_len);
    if (list == NULL || css_parser->status != LXB_STATUS_OK) {
        lxb_css_parser_destroy(css_parser, true);
        if (first_only) {
            lua_pushnil(L);
        } else {
            lua_newtable(L);
            luaL_setmetatable(L, HTML_ELEMENTS_MT);
        }
        return 1;
    }

    lxb_selectors_t *selectors = lxb_selectors_create();
    status = lxb_selectors_init(selectors);
    if (status != LXB_STATUS_OK) {
        lxb_css_selector_list_destroy_memory(list);
        lxb_selectors_destroy(selectors, true);
        lxb_css_parser_destroy(css_parser, true);
        return luaL_error(L, "html: failed to initialize selectors engine");
    }

    SelectCtx sctx;
    sctx.L = L;
    sctx.owner_idx = lua_absindex(L, owner_idx);
    sctx.first_only = first_only;
    sctx.found_node = NULL;
    sctx.count = 0;

    if (!first_only) {
        lua_newtable(L);
        luaL_setmetatable(L, HTML_ELEMENTS_MT);
        sctx.table_idx = lua_gettop(L);
    } else {
        sctx.table_idx = 0;
    }

    (void)lxb_selectors_find(selectors, root_node, list, select_find_cb, &sctx);

    lxb_selectors_destroy(selectors, true);
    lxb_css_selector_list_destroy_memory(list);
    lxb_css_parser_destroy(css_parser, true);

    if (first_only) {
        if (sctx.found_node != NULL) {
            push_html_element_with_owner(L, sctx.found_node, sctx.owner_idx);
        } else {
            lua_pushnil(L);
        }
    }
    return 1;
}

/* ========================================================================= */
/*                          Document Methods                                 */
/* ========================================================================= */

static LuaHtmlDocument *check_document(lua_State *L, int idx) {
    return (LuaHtmlDocument *)luaL_checkudata(L, idx, HTML_DOCUMENT_MT);
}

static int doc_gc(lua_State *L) {
    LuaHtmlDocument *doc = (LuaHtmlDocument *)luaL_checkudata(L, 1, HTML_DOCUMENT_MT);
    if (doc->doc != NULL) {
        lxb_html_document_destroy(doc->doc);
        doc->doc = NULL;
    }
    if (doc->base_uri != NULL) {
        free(doc->base_uri);
        doc->base_uri = NULL;
    }
    return 0;
}

static int doc_select(lua_State *L) {
    LuaHtmlDocument *doc = check_document(L, 1);
    size_t len = 0;
    const char *css = luaL_checklstring(L, 2, &len);
    lxb_dom_node_t *root = lxb_dom_interface_node(doc->doc);
    return run_css_selector(L, root, 1, css, len, false);
}

static int doc_select_first(lua_State *L) {
    LuaHtmlDocument *doc = check_document(L, 1);
    size_t len = 0;
    const char *css = luaL_checklstring(L, 2, &len);
    lxb_dom_node_t *root = lxb_dom_interface_node(doc->doc);
    return run_css_selector(L, root, 1, css, len, true);
}

static int doc_title(lua_State *L) {
    LuaHtmlDocument *doc = check_document(L, 1);
    size_t len = 0;
    const lxb_char_t *title = lxb_html_document_title(doc->doc, &len);
    if (title && len > 0) {
        lua_pushlstring(L, (const char *)title, len);
    } else {
        lua_pushliteral(L, "");
    }
    return 1;
}

static int doc_body(lua_State *L) {
    LuaHtmlDocument *doc = check_document(L, 1);
    lxb_html_body_element_t *body = lxb_html_document_body_element(doc->doc);
    if (body) {
        push_html_element_with_owner(L, lxb_dom_interface_node(body), 1);
    } else {
        lua_pushnil(L);
    }
    return 1;
}

static int doc_head(lua_State *L) {
    LuaHtmlDocument *doc = check_document(L, 1);
    lxb_html_head_element_t *head = lxb_html_document_head_element(doc->doc);
    if (head) {
        push_html_element_with_owner(L, lxb_dom_interface_node(head), 1);
    } else {
        lua_pushnil(L);
    }
    return 1;
}

static int doc_root(lua_State *L) {
    LuaHtmlDocument *doc = check_document(L, 1);
    lxb_dom_node_t *root = lxb_dom_interface_node(doc->doc);
    push_html_element_with_owner(L, root, 1);
    return 1;
}

static int doc_html(lua_State *L) {
    LuaHtmlDocument *doc = check_document(L, 1);
    lxb_dom_node_t *root = lxb_dom_interface_node(doc->doc);
    luaL_Buffer b;
    luaL_buffinit(L, &b);
    SerializeCtx sctx = { &b };
    lxb_html_serialize_deep_cb(root, serialize_buffer_cb, &sctx);
    luaL_pushresult(&b);
    return 1;
}

static int doc_text(lua_State *L) {
    LuaHtmlDocument *doc = check_document(L, 1);
    lxb_dom_node_t *root = lxb_dom_interface_node(doc->doc);
    size_t len = 0;
    lxb_char_t *text = lxb_dom_node_text_content(root, &len);
    if (text) {
        lua_pushlstring(L, (const char *)text, len);
        lxb_dom_document_destroy_text(lxb_dom_interface_document(doc->doc), text);
    } else {
        lua_pushliteral(L, "");
    }
    return 1;
}

static int doc_set_base_uri(lua_State *L) {
    LuaHtmlDocument *doc = check_document(L, 1);
    const char *uri = luaL_checkstring(L, 2);
    if (doc->base_uri != NULL) {
        free(doc->base_uri);
    }
    doc->base_uri = strdup(uri);
    lua_pushvalue(L, 1);
    return 1;
}

static int doc_base_uri(lua_State *L) {
    LuaHtmlDocument *doc = check_document(L, 1);
    if (doc->base_uri != NULL) {
        lua_pushstring(L, doc->base_uri);
    } else {
        lua_pushliteral(L, "");
    }
    return 1;
}

static const luaL_Reg doc_methods[] = {
    {"select", doc_select},
    {"selectFirst", doc_select_first},
    {"select_first", doc_select_first},
    {"setBaseUri", doc_set_base_uri},
    {"set_base_uri", doc_set_base_uri},
    {"baseUri", doc_base_uri},
    {"base_uri", doc_base_uri},
    {"root", doc_root},
    {NULL, NULL}
};

static int doc_index(lua_State *L) {
    const char *key = luaL_checkstring(L, 2);
    lua_getmetatable(L, 1);
    lua_getfield(L, -1, key);
    if (!lua_isnil(L, -1)) {
        return 1;
    }
    lua_pop(L, 2);

    if (strcmp(key, "title") == 0) {
        return doc_title(L);
    } else if (strcmp(key, "body") == 0) {
        return doc_body(L);
    } else if (strcmp(key, "head") == 0) {
        return doc_head(L);
    } else if (strcmp(key, "html") == 0 || strcmp(key, "outerHtml") == 0 || strcmp(key, "outer_html") == 0) {
        return doc_html(L);
    } else if (strcmp(key, "text") == 0) {
        return doc_text(L);
    } else if (strcmp(key, "baseUri") == 0 || strcmp(key, "base_uri") == 0) {
        return doc_base_uri(L);
    }
    lua_pushnil(L);
    return 1;
}

/* ========================================================================= */
/*                           Element Methods                                 */
/* ========================================================================= */

static LuaHtmlElement *check_element(lua_State *L, int idx) {
    return (LuaHtmlElement *)luaL_checkudata(L, idx, HTML_ELEMENT_MT);
}

static int el_select(lua_State *L) {
    LuaHtmlElement *el = check_element(L, 1);
    size_t len = 0;
    const char *css = luaL_checklstring(L, 2, &len);
    return run_css_selector(L, el->node, 1, css, len, false);
}

static int el_select_first(lua_State *L) {
    LuaHtmlElement *el = check_element(L, 1);
    size_t len = 0;
    const char *css = luaL_checklstring(L, 2, &len);
    return run_css_selector(L, el->node, 1, css, len, true);
}

static int el_attr(lua_State *L) {
    LuaHtmlElement *el = check_element(L, 1);
    size_t name_len = 0;
    const char *name = luaL_checklstring(L, 2, &name_len);
    if (el->node->type != LXB_DOM_NODE_TYPE_ELEMENT) {
        lua_pushliteral(L, "");
        return 1;
    }
    lxb_dom_element_t *element = lxb_dom_interface_element(el->node);

    bool resolve_abs = false;
    const char *actual_name = name;
    size_t actual_name_len = name_len;

    if (strncmp(name, "abs:", 4) == 0) {
        resolve_abs = true;
        actual_name = name + 4;
        actual_name_len = name_len - 4;
    }

    size_t val_len = 0;
    const lxb_char_t *val = lxb_dom_element_get_attribute(element, (const lxb_char_t *)actual_name, actual_name_len, &val_len);
    if (val == NULL) {
        lua_pushliteral(L, "");
        return 1;
    }

    if (resolve_abs) {
        const char *base = NULL;
        push_owner_doc(L, 1);
        if (luaL_testudata(L, -1, HTML_DOCUMENT_MT)) {
            LuaHtmlDocument *doc = (LuaHtmlDocument *)lua_touserdata(L, -1);
            base = doc->base_uri;
        }
        lua_pop(L, 1);

        luaL_Buffer b;
        luaL_buffinit(L, &b);
        resolve_absolute_url(&b, base, (const char *)val, val_len);
        luaL_pushresult(&b);
        return 1;
    }

    lua_pushlstring(L, (const char *)val, val_len);
    return 1;
}

static int el_has_attr(lua_State *L) {
    LuaHtmlElement *el = check_element(L, 1);
    size_t name_len = 0;
    const char *name = luaL_checklstring(L, 2, &name_len);
    if (el->node->type != LXB_DOM_NODE_TYPE_ELEMENT) {
        lua_pushboolean(L, false);
        return 1;
    }
    lxb_dom_element_t *element = lxb_dom_interface_element(el->node);
    if (strncmp(name, "abs:", 4) == 0) {
        name += 4;
        name_len -= 4;
    }
    bool has = lxb_dom_element_has_attribute(element, (const lxb_char_t *)name, name_len);
    lua_pushboolean(L, has);
    return 1;
}

static int el_text(lua_State *L) {
    LuaHtmlElement *el = check_element(L, 1);
    size_t len = 0;
    lxb_char_t *text = lxb_dom_node_text_content(el->node, &len);
    if (text != NULL) {
        lua_pushlstring(L, (const char *)text, len);
        lxb_dom_document_destroy_text(el->node->owner_document, text);
    } else {
        lua_pushliteral(L, "");
    }
    return 1;
}

static int el_own_text(lua_State *L) {
    LuaHtmlElement *el = check_element(L, 1);
    luaL_Buffer b;
    luaL_buffinit(L, &b);
    bool has_content = false;

    for (lxb_dom_node_t *ch = el->node->first_child; ch != NULL; ch = ch->next) {
        if (ch->type == LXB_DOM_NODE_TYPE_TEXT) {
            size_t tlen = 0;
            lxb_char_t *t = lxb_dom_node_text_content(ch, &tlen);
            if (t && tlen > 0) {
                const char *s = (const char *)t;
                size_t start = 0;
                while (start < tlen && isspace((unsigned char)s[start])) start++;
                size_t end = tlen;
                while (end > start && isspace((unsigned char)s[end - 1])) end--;
                if (end > start) {
                    if (has_content) {
                        luaL_addchar(&b, ' ');
                    }
                    luaL_addlstring(&b, s + start, end - start);
                    has_content = true;
                }
                lxb_dom_document_destroy_text(el->node->owner_document, t);
            }
        }
    }
    luaL_pushresult(&b);
    return 1;
}

static int el_html(lua_State *L) {
    LuaHtmlElement *el = check_element(L, 1);
    luaL_Buffer b;
    luaL_buffinit(L, &b);
    SerializeCtx sctx = { &b };
    lxb_html_serialize_deep_cb(el->node, serialize_buffer_cb, &sctx);
    luaL_pushresult(&b);
    return 1;
}

static int el_outer_html(lua_State *L) {
    LuaHtmlElement *el = check_element(L, 1);
    luaL_Buffer b;
    luaL_buffinit(L, &b);
    SerializeCtx sctx = { &b };
    lxb_html_serialize_tree_cb(el->node, serialize_buffer_cb, &sctx);
    luaL_pushresult(&b);
    return 1;
}

static int el_tag_name(lua_State *L) {
    LuaHtmlElement *el = check_element(L, 1);
    if (el->node->type != LXB_DOM_NODE_TYPE_ELEMENT) {
        lua_pushliteral(L, "");
        return 1;
    }
    lxb_dom_element_t *element = lxb_dom_interface_element(el->node);
    size_t len = 0;
    const lxb_char_t *name = lxb_dom_element_qualified_name(element, &len);
    if (name && len > 0) {
        lua_pushlstring(L, (const char *)name, len);
    } else {
        lua_pushliteral(L, "");
    }
    return 1;
}

static int el_id(lua_State *L) {
    lua_pushliteral(L, "id");
    lua_replace(L, 2);
    return el_attr(L);
}

static int el_class_name(lua_State *L) {
    lua_pushliteral(L, "class");
    lua_replace(L, 2);
    return el_attr(L);
}

static int el_has_class(lua_State *L) {
    LuaHtmlElement *el = check_element(L, 1);
    size_t target_len = 0;
    const char *target = luaL_checklstring(L, 2, &target_len);
    if (el->node->type != LXB_DOM_NODE_TYPE_ELEMENT || target_len == 0) {
        lua_pushboolean(L, false);
        return 1;
    }
    lxb_dom_element_t *element = lxb_dom_interface_element(el->node);
    size_t class_len = 0;
    const lxb_char_t *class_val = lxb_dom_element_get_attribute(element, (const lxb_char_t *)"class", 5, &class_len);
    if (!class_val || class_len == 0) {
        lua_pushboolean(L, false);
        return 1;
    }

    const char *p = (const char *)class_val;
    const char *end = p + class_len;
    bool found = false;

    while (p < end) {
        while (p < end && isspace((unsigned char)*p)) p++;
        const char *word_start = p;
        while (p < end && !isspace((unsigned char)*p)) p++;
        size_t word_len = p - word_start;
        if (word_len == target_len && strncmp(word_start, target, target_len) == 0) {
            found = true;
            break;
        }
    }
    lua_pushboolean(L, found);
    return 1;
}

static int el_parent(lua_State *L) {
    LuaHtmlElement *el = check_element(L, 1);
    lxb_dom_node_t *p = el->node->parent;
    while (p && p->type != LXB_DOM_NODE_TYPE_ELEMENT && p->type != LXB_DOM_NODE_TYPE_DOCUMENT) {
        p = p->parent;
    }
    if (p && p->type == LXB_DOM_NODE_TYPE_ELEMENT) {
        push_html_element_with_owner(L, p, 1);
    } else {
        lua_pushnil(L);
    }
    return 1;
}

static int el_children(lua_State *L) {
    LuaHtmlElement *el = check_element(L, 1);
    lua_newtable(L);
    luaL_setmetatable(L, HTML_ELEMENTS_MT);
    int count = 0;
    int table_idx = lua_gettop(L);

    for (lxb_dom_node_t *ch = el->node->first_child; ch != NULL; ch = ch->next) {
        if (ch->type == LXB_DOM_NODE_TYPE_ELEMENT) {
            count++;
            push_html_element_with_owner(L, ch, 1);
            lua_rawseti(L, table_idx, count);
        }
    }
    return 1;
}

static int el_child(lua_State *L) {
    LuaHtmlElement *el = check_element(L, 1);
    lua_Integer target_idx = luaL_checkinteger(L, 2);
    if (target_idx < 1) {
        lua_pushnil(L);
        return 1;
    }
    lua_Integer current = 0;
    for (lxb_dom_node_t *ch = el->node->first_child; ch != NULL; ch = ch->next) {
        if (ch->type == LXB_DOM_NODE_TYPE_ELEMENT) {
            current++;
            if (current == target_idx) {
                push_html_element_with_owner(L, ch, 1);
                return 1;
            }
        }
    }
    lua_pushnil(L);
    return 1;
}

static int el_children_size(lua_State *L) {
    LuaHtmlElement *el = check_element(L, 1);
    lua_Integer count = 0;
    for (lxb_dom_node_t *ch = el->node->first_child; ch != NULL; ch = ch->next) {
        if (ch->type == LXB_DOM_NODE_TYPE_ELEMENT) {
            count++;
        }
    }
    lua_pushinteger(L, count);
    return 1;
}

static int el_next_sibling(lua_State *L) {
    LuaHtmlElement *el = check_element(L, 1);
    lxb_dom_node_t *curr = el->node->next;
    while (curr && curr->type != LXB_DOM_NODE_TYPE_ELEMENT) {
        curr = curr->next;
    }
    if (curr) {
        push_html_element_with_owner(L, curr, 1);
    } else {
        lua_pushnil(L);
    }
    return 1;
}

static int el_prev_sibling(lua_State *L) {
    LuaHtmlElement *el = check_element(L, 1);
    lxb_dom_node_t *curr = el->node->prev;
    while (curr && curr->type != LXB_DOM_NODE_TYPE_ELEMENT) {
        curr = curr->prev;
    }
    if (curr) {
        push_html_element_with_owner(L, curr, 1);
    } else {
        lua_pushnil(L);
    }
    return 1;
}

static int el_remove(lua_State *L) {
    LuaHtmlElement *el = check_element(L, 1);
    lxb_dom_node_remove(el->node);
    lua_pushvalue(L, 1);
    return 1;
}

static const luaL_Reg el_methods[] = {
    {"select", el_select},
    {"selectFirst", el_select_first},
    {"select_first", el_select_first},
    {"attr", el_attr},
    {"hasAttr", el_has_attr},
    {"has_attr", el_has_attr},
    {"hasClass", el_has_class},
    {"has_class", el_has_class},
    {"parent", el_parent},
    {"children", el_children},
    {"child", el_child},
    {"childrenSize", el_children_size},
    {"children_size", el_children_size},
    {"nextElementSibling", el_next_sibling},
    {"next_sibling", el_next_sibling},
    {"previousElementSibling", el_prev_sibling},
    {"prev_sibling", el_prev_sibling},
    {"remove", el_remove},
    {NULL, NULL}
};

static int el_index(lua_State *L) {
    const char *key = luaL_checkstring(L, 2);

    lua_getmetatable(L, 1);
    lua_getfield(L, -1, key);
    if (!lua_isnil(L, -1)) {
        return 1;
    }
    lua_pop(L, 2);

    if (strcmp(key, "text") == 0) {
        return el_text(L);
    } else if (strcmp(key, "ownText") == 0 || strcmp(key, "own_text") == 0) {
        return el_own_text(L);
    } else if (strcmp(key, "html") == 0) {
        return el_html(L);
    } else if (strcmp(key, "outerHtml") == 0 || strcmp(key, "outer_html") == 0) {
        return el_outer_html(L);
    } else if (strcmp(key, "tag") == 0 || strcmp(key, "tagName") == 0 || strcmp(key, "tag_name") == 0) {
        return el_tag_name(L);
    } else if (strcmp(key, "id") == 0) {
        return el_id(L);
    } else if (strcmp(key, "className") == 0 || strcmp(key, "class_name") == 0) {
        return el_class_name(L);
    } else if (strcmp(key, "href") == 0 || strcmp(key, "src") == 0) {
        /* Auto-resolve absolute URL for .href and .src */
        lua_pushfstring(L, "abs:%s", key);
        lua_replace(L, 2);
        return el_attr(L);
    }

    lua_pushvalue(L, 2);
    return el_attr(L);
}

/* ========================================================================= */
/*                         Elements List Methods                             */
/* ========================================================================= */

static int elements_size(lua_State *L) {
    luaL_checktype(L, 1, LUA_TTABLE);
    lua_pushinteger(L, (lua_Integer)lua_rawlen(L, 1));
    return 1;
}

static int elements_is_empty(lua_State *L) {
    luaL_checktype(L, 1, LUA_TTABLE);
    lua_pushboolean(L, lua_rawlen(L, 1) == 0);
    return 1;
}

static int elements_get(lua_State *L) {
    luaL_checktype(L, 1, LUA_TTABLE);
    lua_Integer idx = luaL_checkinteger(L, 2);
    lua_rawgeti(L, 1, idx);
    return 1;
}

static int elements_first(lua_State *L) {
    luaL_checktype(L, 1, LUA_TTABLE);
    lua_rawgeti(L, 1, 1);
    return 1;
}

static int elements_last(lua_State *L) {
    luaL_checktype(L, 1, LUA_TTABLE);
    lua_Integer len = (lua_Integer)lua_rawlen(L, 1);
    if (len > 0) {
        lua_rawgeti(L, 1, len);
    } else {
        lua_pushnil(L);
    }
    return 1;
}

static int elements_attr(lua_State *L) {
    luaL_checktype(L, 1, LUA_TTABLE);
    const char *attr_name = luaL_checkstring(L, 2);
    lua_rawgeti(L, 1, 1);
    if (!lua_isnil(L, -1)) {
        lua_getfield(L, -1, "attr");
        lua_pushvalue(L, -2);
        lua_pushstring(L, attr_name);
        lua_call(L, 2, 1);
        return 1;
    }
    lua_pushliteral(L, "");
    return 1;
}

static int elements_text(lua_State *L) {
    luaL_checktype(L, 1, LUA_TTABLE);
    lua_Integer len = (lua_Integer)lua_rawlen(L, 1);
    luaL_Buffer b;
    luaL_buffinit(L, &b);

    for (lua_Integer i = 1; i <= len; i++) {
        lua_rawgeti(L, 1, i);
        if (luaL_testudata(L, -1, HTML_ELEMENT_MT)) {
            LuaHtmlElement *el = (LuaHtmlElement *)lua_touserdata(L, -1);
            size_t tlen = 0;
            lxb_char_t *text = lxb_dom_node_text_content(el->node, &tlen);
            if (text) {
                if (i > 1 && luaL_bufflen(&b) > 0) {
                    luaL_addchar(&b, ' ');
                }
                luaL_addlstring(&b, (const char *)text, tlen);
                lxb_dom_document_destroy_text(el->node->owner_document, text);
            }
        }
        lua_pop(L, 1);
    }
    luaL_pushresult(&b);
    return 1;
}

static int elements_html(lua_State *L) {
    luaL_checktype(L, 1, LUA_TTABLE);
    lua_Integer len = (lua_Integer)lua_rawlen(L, 1);
    luaL_Buffer b;
    luaL_buffinit(L, &b);
    SerializeCtx sctx = { &b };

    for (lua_Integer i = 1; i <= len; i++) {
        lua_rawgeti(L, 1, i);
        if (luaL_testudata(L, -1, HTML_ELEMENT_MT)) {
            LuaHtmlElement *el = (LuaHtmlElement *)lua_touserdata(L, -1);
            lxb_html_serialize_deep_cb(el->node, serialize_buffer_cb, &sctx);
        }
        lua_pop(L, 1);
    }
    luaL_pushresult(&b);
    return 1;
}

static int elements_outer_html(lua_State *L) {
    luaL_checktype(L, 1, LUA_TTABLE);
    lua_Integer len = (lua_Integer)lua_rawlen(L, 1);
    luaL_Buffer b;
    luaL_buffinit(L, &b);
    SerializeCtx sctx = { &b };

    for (lua_Integer i = 1; i <= len; i++) {
        lua_rawgeti(L, 1, i);
        if (luaL_testudata(L, -1, HTML_ELEMENT_MT)) {
            LuaHtmlElement *el = (LuaHtmlElement *)lua_touserdata(L, -1);
            lxb_html_serialize_tree_cb(el->node, serialize_buffer_cb, &sctx);
        }
        lua_pop(L, 1);
    }
    luaL_pushresult(&b);
    return 1;
}

static int elements_select(lua_State *L) {
    luaL_checktype(L, 1, LUA_TTABLE);
    size_t css_len = 0;
    const char *css = luaL_checklstring(L, 2, &css_len);
    lua_Integer len = (lua_Integer)lua_rawlen(L, 1);

    lua_newtable(L);
    luaL_setmetatable(L, HTML_ELEMENTS_MT);
    int res_table_idx = lua_gettop(L);
    int total_collected = 0;

    for (lua_Integer i = 1; i <= len; i++) {
        lua_rawgeti(L, 1, i);
        if (luaL_testudata(L, -1, HTML_ELEMENT_MT)) {
            LuaHtmlElement *el = (LuaHtmlElement *)lua_touserdata(L, -1);
            run_css_selector(L, el->node, -1, css, css_len, false);
            int sub_len = (int)lua_rawlen(L, -1);
            for (int j = 1; j <= sub_len; j++) {
                lua_rawgeti(L, -1, j);
                total_collected++;
                lua_rawseti(L, res_table_idx, total_collected);
            }
            lua_pop(L, 1);
        }
        lua_pop(L, 1);
    }
    return 1;
}

static int elements_for_each(lua_State *L) {
    luaL_checktype(L, 1, LUA_TTABLE);
    luaL_checktype(L, 2, LUA_TFUNCTION);
    lua_Integer len = (lua_Integer)lua_rawlen(L, 1);

    for (lua_Integer i = 1; i <= len; i++) {
        lua_pushvalue(L, 2);
        lua_rawgeti(L, 1, i);
        lua_pushinteger(L, i);
        lua_call(L, 2, 0);
    }
    lua_pushvalue(L, 1);
    return 1;
}

static const luaL_Reg elements_methods[] = {
    {"size", elements_size},
    {"length", elements_size},
    {"isEmpty", elements_is_empty},
    {"is_empty", elements_is_empty},
    {"get", elements_get},
    {"first", elements_first},
    {"last", elements_last},
    {"attr", elements_attr},
    {"text", elements_text},
    {"html", elements_html},
    {"outerHtml", elements_outer_html},
    {"outer_html", elements_outer_html},
    {"select", elements_select},
    {"forEach", elements_for_each},
    {"each", elements_for_each},
    {NULL, NULL}
};

/* ========================================================================= */
/*                          Global Library Functions                         */
/* ========================================================================= */

static int html_parse(lua_State *L) {
    size_t len = 0;
    const char *html = luaL_checklstring(L, 1, &len);
    const char *base = luaL_optstring(L, 2, NULL);

    lxb_html_document_t *doc = lxb_html_document_create();
    if (!doc) {
        return luaL_error(L, "html: failed to allocate html document");
    }

    lxb_status_t status = lxb_html_document_parse(doc, (const lxb_char_t *)html, len);
    if (status != LXB_STATUS_OK) {
        lxb_html_document_destroy(doc);
        return luaL_error(L, "html: failed to parse html string");
    }

    LuaHtmlDocument *ud = (LuaHtmlDocument *)lua_newuserdatauv(L, sizeof(LuaHtmlDocument), 0);
    ud->doc = doc;
    ud->base_uri = (base != NULL) ? strdup(base) : NULL;

    luaL_setmetatable(L, HTML_DOCUMENT_MT);
    return 1;
}

/* Legacy NoveLA helpers: html_select(html, css) & html_select_first(html, css) */
static int html_select_global(lua_State *L) {
    lua_pushcfunction(L, html_parse);
    lua_pushvalue(L, 1);
    lua_call(L, 1, 1);

    lua_getfield(L, -1, "select");
    lua_pushvalue(L, -2);
    lua_pushvalue(L, 2);
    lua_call(L, 2, 1);
    return 1;
}

static int html_select_first_global(lua_State *L) {
    lua_pushcfunction(L, html_parse);
    lua_pushvalue(L, 1);
    lua_call(L, 1, 1);

    lua_getfield(L, -1, "selectFirst");
    lua_pushvalue(L, -2);
    lua_pushvalue(L, 2);
    lua_call(L, 2, 1);
    return 1;
}

static int html_attr_global(lua_State *L) {
    lua_pushcfunction(L, html_select_first_global);
    lua_pushvalue(L, 1);
    lua_pushvalue(L, 2);
    lua_call(L, 2, 1);
    if (lua_isnil(L, -1)) {
        lua_pushliteral(L, "");
        return 1;
    }
    lua_getfield(L, -1, "attr");
    lua_pushvalue(L, -2);
    lua_pushvalue(L, 3);
    lua_call(L, 2, 1);
    return 1;
}

typedef struct {
    luaL_Buffer *b;
    int last_char;
} HtmlTextCtx;

static void html_text_traverse(lxb_dom_node_t *node, HtmlTextCtx *ctx) {
    if (node == NULL) return;

    if (node->type == LXB_DOM_NODE_TYPE_ELEMENT) {
        lxb_tag_id_t tag = node->local_name;
        if (tag == LXB_TAG_SCRIPT || tag == LXB_TAG_STYLE || 
            tag == LXB_TAG_IFRAME || tag == LXB_TAG_NOSCRIPT) {
            return;
        }

        if (tag == LXB_TAG_BR) {
            luaL_addchar(ctx->b, '\n');
            ctx->last_char = '\n';
            return;
        }

        int is_block = (tag == LXB_TAG_P || tag == LXB_TAG_DIV || 
                        tag == LXB_TAG_H1 || tag == LXB_TAG_H2 || tag == LXB_TAG_H3 ||
                        tag == LXB_TAG_H4 || tag == LXB_TAG_H5 || tag == LXB_TAG_H6 ||
                        tag == LXB_TAG_LI || tag == LXB_TAG_TR || tag == LXB_TAG_BLOCKQUOTE);

        if (is_block && ctx->last_char != '\n' && ctx->last_char != 0) {
            luaL_addchar(ctx->b, '\n');
            ctx->last_char = '\n';
        }

        for (lxb_dom_node_t *ch = node->first_child; ch != NULL; ch = ch->next) {
            html_text_traverse(ch, ctx);
        }

        if (is_block && ctx->last_char != '\n' && ctx->last_char != 0) {
            luaL_addchar(ctx->b, '\n');
            ctx->last_char = '\n';
        }
    } else if (node->type == LXB_DOM_NODE_TYPE_TEXT) {
        size_t len = 0;
        lxb_char_t *text = lxb_dom_node_text_content(node, &len);
        if (text && len > 0) {
            const char *s = (const char *)text;
            for (size_t i = 0; i < len; i++) {
                char c = s[i];
                if (c == '\r') continue;
                if (isspace((unsigned char)c)) {
                    if (ctx->last_char != ' ' && ctx->last_char != '\n' && ctx->last_char != 0) {
                        luaL_addchar(ctx->b, ' ');
                        ctx->last_char = ' ';
                    }
                } else {
                    luaL_addchar(ctx->b, c);
                    ctx->last_char = c;
                }
            }
            lxb_dom_document_destroy_text(node->owner_document, text);
        }
    }
}

static int html_text_global(lua_State *L) {
    size_t len = 0;
    const char *html = luaL_checklstring(L, 1, &len);
    lxb_html_document_t *doc = lxb_html_document_create();
    if (!doc) {
        lua_pushliteral(L, "");
        return 1;
    }
    if (lxb_html_document_parse(doc, (const lxb_char_t *)html, len) != LXB_STATUS_OK) {
        lxb_html_document_destroy(doc);
        lua_pushliteral(L, "");
        return 1;
    }

    luaL_Buffer b;
    luaL_buffinit(L, &b);
    HtmlTextCtx ctx = { &b, 0 };

    lxb_dom_node_t *root = doc->body ? lxb_dom_interface_node(doc->body) : lxb_dom_interface_node(doc);
    html_text_traverse(root, &ctx);

    lxb_html_document_destroy(doc);
    luaL_pushresult(&b);

    size_t res_len = 0;
    const char *res = luaL_checklstring(L, -1, &res_len);
    size_t start = 0;
    while (start < res_len && (res[start] == ' ' || res[start] == '\n' || res[start] == '\r' || res[start] == '\t')) start++;
    size_t end = res_len;
    while (end > start && (res[end - 1] == ' ' || res[end - 1] == '\n' || res[end - 1] == '\r' || res[end - 1] == '\t')) end--;

    if (start > 0 || end < res_len) {
        lua_pushlstring(L, res + start, end - start);
        lua_replace(L, -2);
    }
    return 1;
}

typedef struct {
    lxb_dom_node_t **nodes;
    size_t count;
    size_t cap;
} RemoveCollector;

static lxb_status_t remove_node_cb(lxb_dom_node_t *node, lxb_css_selector_specificity_t spec, void *ctx) {
    (void)spec;
    RemoveCollector *col = (RemoveCollector *)ctx;
    if (node->type != LXB_DOM_NODE_TYPE_ELEMENT) return LXB_STATUS_OK;
    if (col->count >= col->cap) {
        col->cap *= 2;
        col->nodes = (lxb_dom_node_t **)realloc(col->nodes, col->cap * sizeof(lxb_dom_node_t *));
    }
    col->nodes[col->count++] = node;
    return LXB_STATUS_OK;
}

static int html_remove_global(lua_State *L) {
    size_t hlen = 0, clen = 0;
    const char *html = luaL_checklstring(L, 1, &hlen);
    const char *css = luaL_checklstring(L, 2, &clen);

    lxb_html_document_t *doc = lxb_html_document_create();
    if (!doc) {
        lua_pushvalue(L, 1);
        return 1;
    }
    if (lxb_html_document_parse(doc, (const lxb_char_t *)html, hlen) != LXB_STATUS_OK) {
        lxb_html_document_destroy(doc);
        lua_pushvalue(L, 1);
        return 1;
    }

    lxb_css_parser_t *parser = lxb_css_parser_create();
    lxb_selectors_t *selectors = lxb_selectors_create();
    lxb_css_selector_list_t *list = NULL;
    if (lxb_css_parser_init(parser, NULL) == LXB_STATUS_OK && lxb_selectors_init(selectors) == LXB_STATUS_OK) {
        list = lxb_css_selectors_parse(parser, (const lxb_char_t *)css, clen);
    }

    if (list != NULL) {
        RemoveCollector col;
        col.cap = 32;
        col.count = 0;
        col.nodes = (lxb_dom_node_t **)malloc(col.cap * sizeof(lxb_dom_node_t *));

        lxb_dom_node_t *root = doc->body ? lxb_dom_interface_node(doc->body) : lxb_dom_interface_node(doc);
        lxb_selectors_find(selectors, root, list, remove_node_cb, &col);

        for (size_t i = 0; i < col.count; i++) {
            lxb_dom_node_remove(col.nodes[i]);
        }
        free(col.nodes);
        lxb_css_selector_list_destroy_memory(list);
    }

    if (selectors) lxb_selectors_destroy(selectors, true);
    if (parser) lxb_css_parser_destroy(parser, true);

    luaL_Buffer b;
    luaL_buffinit(L, &b);
    SerializeCtx sctx = { &b };
    lxb_dom_node_t *target = doc->body ? lxb_dom_interface_node(doc->body) : lxb_dom_interface_node(doc);
    lxb_html_serialize_deep_cb(target, serialize_buffer_cb, &sctx);

    lxb_html_document_destroy(doc);
    luaL_pushresult(&b);
    return 1;
}

static int url_resolve_global(lua_State *L) {
    const char *base = luaL_checkstring(L, 1);
    size_t rel_len = 0;
    const char *rel = luaL_checklstring(L, 2, &rel_len);
    luaL_Buffer b;
    luaL_buffinit(L, &b);
    resolve_absolute_url(&b, base, rel, rel_len);
    luaL_pushresult(&b);
    return 1;
}

static int string_call_self(lua_State *L) {
    lua_pushvalue(L, 1);
    return 1;
}

static const luaL_Reg htmllib_funcs[] = {
    {"html_parse", html_parse},
    {"parse", html_parse},
    {"html_select", html_select_global},
    {"select", html_select_global},
    {"html_select_first", html_select_first_global},
    {"selectFirst", html_select_first_global},
    {"select_first", html_select_first_global},
    {"html_attr", html_attr_global},
    {"html_text", html_text_global},
    {"html_remove", html_remove_global},
    {"url_resolve", url_resolve_global},
    {NULL, NULL}
};

/* Registration */
LUAMOD_API int luaopen_html(lua_State *L) {
    if (luaL_newmetatable(L, HTML_DOCUMENT_MT)) {
        lua_pushcfunction(L, doc_gc);
        lua_setfield(L, -2, "__gc");
        lua_pushcfunction(L, doc_html);
        lua_setfield(L, -2, "__tostring");
        lua_pushcfunction(L, doc_index);
        lua_setfield(L, -2, "__index");
        luaL_setfuncs(L, doc_methods, 0);
    }
    lua_pop(L, 1);

    if (luaL_newmetatable(L, HTML_ELEMENT_MT)) {
        lua_pushcfunction(L, el_outer_html);
        lua_setfield(L, -2, "__tostring");
        lua_pushcfunction(L, el_index);
        lua_setfield(L, -2, "__index");
        luaL_setfuncs(L, el_methods, 0);
    }
    lua_pop(L, 1);

    if (luaL_newmetatable(L, HTML_ELEMENTS_MT)) {
        lua_pushcfunction(L, elements_outer_html);
        lua_setfield(L, -2, "__tostring");
        lua_pushcfunction(L, elements_size);
        lua_setfield(L, -2, "__len");
        lua_pushvalue(L, -1);
        lua_setfield(L, -2, "__index");
        luaL_setfuncs(L, elements_methods, 0);
    }
    lua_pop(L, 1);

    lua_pushliteral(L, "");
    if (lua_getmetatable(L, -1)) {
        lua_pushcfunction(L, string_call_self);
        lua_setfield(L, -2, "__call");
        lua_pop(L, 1);
    }
    lua_pop(L, 1);

    luaL_newlib(L, htmllib_funcs);
    return 1;
}
