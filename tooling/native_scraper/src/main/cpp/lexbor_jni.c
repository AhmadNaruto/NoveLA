#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

#include <lexbor/html/html.h>
#include <lexbor/css/css.h>
#include <lexbor/selectors/selectors.h>
#include <lexbor/dom/interfaces/element.h>
#include <lexbor/dom/interfaces/node.h>
#include <lexbor/dom/interfaces/text.h>
#include <lexbor/html/interfaces/document.h>
#include <lexbor/tag/const.h>

#define LOG_TAG "LexborJNI"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

typedef struct {
    lxb_css_parser_t *parser;
    lxb_selectors_t *selectors;
} tl_css_engine_t;

static _Thread_local tl_css_engine_t tl_engine = {NULL, NULL};

static tl_css_engine_t* get_tl_engine(void) {
    if (!tl_engine.parser) {
        tl_engine.parser = lxb_css_parser_create();
        if (tl_engine.parser) {
            if (lxb_css_parser_init(tl_engine.parser, NULL) != LXB_STATUS_OK) {
                lxb_css_parser_destroy(tl_engine.parser, true);
                tl_engine.parser = NULL;
                return NULL;
            }
        }
    }
    if (!tl_engine.selectors) {
        tl_engine.selectors = lxb_selectors_create();
        if (tl_engine.selectors) {
            if (lxb_selectors_init(tl_engine.selectors) != LXB_STATUS_OK) {
                lxb_selectors_destroy(tl_engine.selectors, true);
                tl_engine.selectors = NULL;
                return NULL;
            }
        }
    }
    return (tl_engine.parser && tl_engine.selectors) ? &tl_engine : NULL;
}

typedef struct {
    char *data;
    size_t length;
    size_t capacity;
} text_buf_t;

static void text_buf_init(text_buf_t *buf, size_t initial_cap) {
    buf->capacity = initial_cap > 0 ? initial_cap : 4096;
    buf->length = 0;
    buf->data = malloc(buf->capacity);
    if (buf->data) buf->data[0] = '\0';
}

static void text_buf_append_len(text_buf_t *buf, const char *str, size_t slen) {
    if (!str || slen == 0 || !buf->data) return;
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

static void text_buf_append(text_buf_t *buf, const char *str) {
    if (str) text_buf_append_len(buf, str, strlen(str));
}

static void text_buf_free(text_buf_t *buf) {
    free(buf->data);
    buf->data = NULL;
    buf->length = 0;
    buf->capacity = 0;
}

typedef struct {
    lxb_dom_node_t **nodes;
    size_t count;
    size_t capacity;
    bool find_first_only;
} lexbor_select_ctx_t;

static lxb_status_t select_callback(lxb_dom_node_t *node, lxb_css_selector_specificity_t spec, void *ctx) {
    lexbor_select_ctx_t *select_ctx = (lexbor_select_ctx_t *) ctx;
    if (select_ctx->find_first_only && select_ctx->count > 0) {
        return LXB_STATUS_OK;
    }
    if (select_ctx->count >= select_ctx->capacity) {
        size_t new_cap = select_ctx->capacity == 0 ? 16 : select_ctx->capacity * 2;
        lxb_dom_node_t **new_nodes = realloc(select_ctx->nodes, new_cap * sizeof(lxb_dom_node_t *));
        if (!new_nodes) return LXB_STATUS_ERROR_MEMORY_ALLOCATION;
        select_ctx->nodes = new_nodes;
        select_ctx->capacity = new_cap;
    }
    select_ctx->nodes[select_ctx->count++] = node;
    return LXB_STATUS_OK;
}

JNIEXPORT jlong JNICALL
Java_my_noveldokusha_tooling_native_1scraper_LexborNative_nativeParse(
    JNIEnv *env, jclass clazz, jstring html_str)
{
    if (!html_str) return 0;
    const char *html = (*env)->GetStringUTFChars(env, html_str, NULL);
    if (!html) return 0;
    size_t html_len = (*env)->GetStringUTFLength(env, html_str);

    lxb_html_document_t *document = lxb_html_document_create();
    if (!document) {
        (*env)->ReleaseStringUTFChars(env, html_str, html);
        return 0;
    }

    lxb_status_t status = lxb_html_document_parse(document, (const lxb_char_t *) html, html_len);
    (*env)->ReleaseStringUTFChars(env, html_str, html);

    if (status != LXB_STATUS_OK) {
        lxb_html_document_destroy(document);
        return 0;
    }

    return (jlong) (intptr_t) document;
}

JNIEXPORT jlong JNICALL
Java_my_noveldokusha_tooling_native_1scraper_LexborNative_nativeGetRootNode(
    JNIEnv *env, jclass clazz, jlong doc_ptr)
{
    if (!doc_ptr) return 0;
    lxb_html_document_t *document = (lxb_html_document_t *) (intptr_t) doc_ptr;
    lxb_dom_node_t *root = lxb_dom_interface_node(document);
    return (jlong) (intptr_t) root;
}

JNIEXPORT jlong JNICALL
Java_my_noveldokusha_tooling_native_1scraper_LexborNative_nativeGetBodyNode(
    JNIEnv *env, jclass clazz, jlong doc_ptr)
{
    if (!doc_ptr) return 0;
    lxb_html_document_t *document = (lxb_html_document_t *) (intptr_t) doc_ptr;
    lxb_html_body_element_t *body = lxb_html_document_body_element(document);
    if (!body) return 0;
    return (jlong) (intptr_t) lxb_dom_interface_node(body);
}

JNIEXPORT jlong JNICALL
Java_my_noveldokusha_tooling_native_1scraper_LexborNative_nativeGetHeadNode(
    JNIEnv *env, jclass clazz, jlong doc_ptr)
{
    if (!doc_ptr) return 0;
    lxb_html_document_t *document = (lxb_html_document_t *) (intptr_t) doc_ptr;
    lxb_html_head_element_t *head = lxb_html_document_head_element(document);
    if (!head) return 0;
    return (jlong) (intptr_t) lxb_dom_interface_node(head);
}

JNIEXPORT jstring JNICALL
Java_my_noveldokusha_tooling_native_1scraper_LexborNative_nativeGetTitle(
    JNIEnv *env, jclass clazz, jlong doc_ptr)
{
    if (!doc_ptr) return (*env)->NewStringUTF(env, "");
    lxb_html_document_t *document = (lxb_html_document_t *) (intptr_t) doc_ptr;
    size_t len = 0;
    const lxb_char_t *title = lxb_html_document_title(document, &len);
    if (!title) return (*env)->NewStringUTF(env, "");

    char *buf = malloc(len + 1);
    if (!buf) return (*env)->NewStringUTF(env, "");
    memcpy(buf, title, len);
    buf[len] = '\0';

    jstring res = (*env)->NewStringUTF(env, buf);
    free(buf);
    return res;
}

JNIEXPORT void JNICALL
Java_my_noveldokusha_tooling_native_1scraper_LexborNative_nativeFreeDocument(
    JNIEnv *env, jclass clazz, jlong doc_ptr)
{
    if (!doc_ptr) return;
    lxb_html_document_t *document = (lxb_html_document_t *) (intptr_t) doc_ptr;
    lxb_html_document_destroy(document);
}

static void append_img_entry(text_buf_t *buf, lxb_dom_node_t *node) {
    if (node->type != LXB_DOM_NODE_TYPE_ELEMENT) return;
    lxb_dom_element_t *el = lxb_dom_interface_element(node);
    size_t val_len = 0;
    const lxb_char_t *src = lxb_dom_element_get_attribute(el, (const lxb_char_t *) "src", 3, &val_len);
    if (!src || val_len == 0) return;
    text_buf_append(buf, "\n\n<img src=\"");
    text_buf_append_len(buf, (const char *) src, val_len);
    text_buf_append(buf, "\" yrel=\"1.45\">\n\n");
}

static void inner_p_traverse(text_buf_t *buf, lxb_dom_node_t *node) {
    for (lxb_dom_node_t *child = node->first_child; child != NULL; child = child->next) {
        if (child->type == LXB_DOM_NODE_TYPE_ELEMENT) {
            if (child->local_name == LXB_TAG_BR) {
                text_buf_append(buf, "\n");
            } else if (child->local_name == LXB_TAG_IMG) {
                append_img_entry(buf, child);
            } else {
                inner_p_traverse(buf, child);
            }
        } else if (child->type == LXB_DOM_NODE_TYPE_TEXT) {
            lxb_dom_character_data_t *ch_data = &lxb_dom_interface_text(child)->char_data;
            text_buf_append_len(buf, (const char *) ch_data->data.data, ch_data->data.length);
        }
    }
}

static void get_p_traverse(text_buf_t *buf, lxb_dom_node_t *p_node) {
    text_buf_t p_buf;
    text_buf_init(&p_buf, 512);
    inner_p_traverse(&p_buf, p_node);
    char *start = p_buf.data;
    size_t len = p_buf.length;
    while (len > 0 && (unsigned char)*start <= ' ') {
        start++;
        len--;
    }
    while (len > 0 && (unsigned char)start[len - 1] <= ' ') {
        len--;
    }
    if (len > 0) {
        text_buf_append_len(buf, start, len);
        text_buf_append(buf, "\n\n");
    }
    text_buf_free(&p_buf);
}

static void get_node_text_traverse(text_buf_t *buf, lxb_dom_node_t *node) {
    for (lxb_dom_node_t *child = node->first_child; child != NULL; child = child->next) {
        if (child->type == LXB_DOM_NODE_TYPE_ELEMENT) {
            if (child->local_name == LXB_TAG_P) {
                get_p_traverse(buf, child);
            } else if (child->local_name == LXB_TAG_BR) {
                text_buf_append(buf, "\n");
            } else if (child->local_name == LXB_TAG_HR) {
                text_buf_append(buf, "\n\n");
            } else if (child->local_name == LXB_TAG_IMG) {
                append_img_entry(buf, child);
            } else {
                get_node_text_traverse(buf, child);
            }
        } else if (child->type == LXB_DOM_NODE_TYPE_TEXT) {
            lxb_dom_character_data_t *ch_data = &lxb_dom_interface_text(child)->char_data;
            const char *start = (const char *) ch_data->data.data;
            size_t len = ch_data->data.length;
            while (len > 0 && (unsigned char)*start <= ' ') {
                start++;
                len--;
            }
            while (len > 0 && (unsigned char)start[len - 1] <= ' ') {
                len--;
            }
            if (len > 0) {
                text_buf_append_len(buf, start, len);
                text_buf_append(buf, "\n\n");
            }
        }
    }
}

JNIEXPORT jstring JNICALL
Java_my_noveldokusha_tooling_native_1scraper_LexborNative_nativeExtractContentText(
    JNIEnv *env, jclass clazz, jlong node_ptr)
{
    if (!node_ptr) return (*env)->NewStringUTF(env, "");
    lxb_dom_node_t *root = (lxb_dom_node_t *) (intptr_t) node_ptr;

    text_buf_t buf;
    text_buf_init(&buf, 16384);

    for (lxb_dom_node_t *child = root->first_child; child != NULL; child = child->next) {
        if (child->type == LXB_DOM_NODE_TYPE_ELEMENT) {
            if (child->local_name == LXB_TAG_P) {
                get_p_traverse(&buf, child);
            } else if (child->local_name == LXB_TAG_BR) {
                text_buf_append(&buf, "\n");
            } else if (child->local_name == LXB_TAG_HR) {
                text_buf_append(&buf, "\n\n");
            } else if (child->local_name == LXB_TAG_IMG) {
                append_img_entry(&buf, child);
            } else {
                get_node_text_traverse(&buf, child);
            }
        } else if (child->type == LXB_DOM_NODE_TYPE_TEXT) {
            lxb_dom_character_data_t *ch_data = &lxb_dom_interface_text(child)->char_data;
            const char *start = (const char *) ch_data->data.data;
            size_t len = ch_data->data.length;
            while (len > 0 && (unsigned char)*start <= ' ') {
                start++;
                len--;
            }
            while (len > 0 && (unsigned char)start[len - 1] <= ' ') {
                len--;
            }
            if (len > 0) {
                text_buf_append_len(&buf, start, len);
            }
        }
    }

    jstring result = (*env)->NewStringUTF(env, buf.data ? buf.data : "");
    text_buf_free(&buf);
    return result;
}

JNIEXPORT jlongArray JNICALL
Java_my_noveldokusha_tooling_native_1scraper_LexborNative_nativeSelect(
    JNIEnv *env, jclass clazz, jlong node_ptr, jstring css_query)
{
    if (!node_ptr || !css_query) return NULL;

    lxb_dom_node_t *root_node = (lxb_dom_node_t *) (intptr_t) node_ptr;
    const char *query_c = (*env)->GetStringUTFChars(env, css_query, NULL);
    if (!query_c) return NULL;
    size_t query_len = (*env)->GetStringUTFLength(env, css_query);

    tl_css_engine_t *engine = get_tl_engine();
    if (!engine) {
        (*env)->ReleaseStringUTFChars(env, css_query, query_c);
        return NULL;
    }

    lxb_css_selector_list_t *list = lxb_css_selectors_parse(engine->parser, (const lxb_char_t *) query_c, query_len);
    (*env)->ReleaseStringUTFChars(env, css_query, query_c);

    if (!list || engine->parser->status != LXB_STATUS_OK) {
        lxb_css_parser_clean(engine->parser);
        return NULL;
    }

    lexbor_select_ctx_t ctx = { .nodes = NULL, .count = 0, .capacity = 0, .find_first_only = false };
    lxb_status_t status = lxb_selectors_find(engine->selectors, root_node, list, select_callback, &ctx);

    lxb_selectors_clean(engine->selectors);
    lxb_css_parser_clean(engine->parser);
    lxb_css_selector_list_destroy_memory(list);

    if (status != LXB_STATUS_OK || ctx.count == 0) {
        free(ctx.nodes);
        return (*env)->NewLongArray(env, 0);
    }

    jlongArray result = (*env)->NewLongArray(env, ctx.count);
    if (result) {
        jlong *elems = malloc(ctx.count * sizeof(jlong));
        for (size_t i = 0; i < ctx.count; i++) {
            elems[i] = (jlong) (intptr_t) ctx.nodes[i];
        }
        (*env)->SetLongArrayRegion(env, result, 0, ctx.count, elems);
        free(elems);
    }

    free(ctx.nodes);
    return result;
}

JNIEXPORT jlong JNICALL
Java_my_noveldokusha_tooling_native_1scraper_LexborNative_nativeSelectFirst(
    JNIEnv *env, jclass clazz, jlong node_ptr, jstring css_query)
{
    if (!node_ptr || !css_query) return 0;

    lxb_dom_node_t *root_node = (lxb_dom_node_t *) (intptr_t) node_ptr;
    const char *query_c = (*env)->GetStringUTFChars(env, css_query, NULL);
    if (!query_c) return 0;
    size_t query_len = (*env)->GetStringUTFLength(env, css_query);

    tl_css_engine_t *engine = get_tl_engine();
    if (!engine) {
        (*env)->ReleaseStringUTFChars(env, css_query, query_c);
        return 0;
    }

    lxb_css_selector_list_t *list = lxb_css_selectors_parse(engine->parser, (const lxb_char_t *) query_c, query_len);
    (*env)->ReleaseStringUTFChars(env, css_query, query_c);

    if (!list || engine->parser->status != LXB_STATUS_OK) {
        lxb_css_parser_clean(engine->parser);
        return 0;
    }

    lexbor_select_ctx_t ctx = { .nodes = NULL, .count = 0, .capacity = 0, .find_first_only = true };
    lxb_status_t status = lxb_selectors_find(engine->selectors, root_node, list, select_callback, &ctx);

    lxb_selectors_clean(engine->selectors);
    lxb_css_parser_clean(engine->parser);
    lxb_css_selector_list_destroy_memory(list);

    jlong res = 0;
    if (status == LXB_STATUS_OK && ctx.count > 0) {
        res = (jlong) (intptr_t) ctx.nodes[0];
    }

    free(ctx.nodes);
    return res;
}

JNIEXPORT jstring JNICALL
Java_my_noveldokusha_tooling_native_1scraper_LexborNative_nativeGetText(
    JNIEnv *env, jclass clazz, jlong node_ptr)
{
    if (!node_ptr) return (*env)->NewStringUTF(env, "");
    lxb_dom_node_t *node = (lxb_dom_node_t *) (intptr_t) node_ptr;
    size_t len = 0;
    lxb_char_t *text = lxb_dom_node_text_content(node, &len);
    if (!text) return (*env)->NewStringUTF(env, "");

    jstring res = (*env)->NewStringUTF(env, (const char *) text);
    lxb_dom_document_destroy_text(node->owner_document, text);
    return res;
}

JNIEXPORT jstring JNICALL
Java_my_noveldokusha_tooling_native_1scraper_LexborNative_nativeGetAttr(
    JNIEnv *env, jclass clazz, jlong node_ptr, jstring attr_name)
{
    if (!node_ptr || !attr_name) return (*env)->NewStringUTF(env, "");
    lxb_dom_node_t *node = (lxb_dom_node_t *) (intptr_t) node_ptr;
    if (node->type != LXB_DOM_NODE_TYPE_ELEMENT) return (*env)->NewStringUTF(env, "");

    lxb_dom_element_t *element = lxb_dom_interface_element(node);
    const char *attr_c = (*env)->GetStringUTFChars(env, attr_name, NULL);
    if (!attr_c) return (*env)->NewStringUTF(env, "");
    size_t attr_len = (*env)->GetStringUTFLength(env, attr_name);

    size_t value_len = 0;
    const lxb_char_t *val = lxb_dom_element_get_attribute(element, (const lxb_char_t *) attr_c, attr_len, &value_len);
    (*env)->ReleaseStringUTFChars(env, attr_name, attr_c);

    if (!val) return (*env)->NewStringUTF(env, "");

    if (value_len < 256) {
        char stack_buf[256];
        memcpy(stack_buf, val, value_len);
        stack_buf[value_len] = '\0';
        return (*env)->NewStringUTF(env, stack_buf);
    } else {
        char *buf = malloc(value_len + 1);
        if (!buf) return (*env)->NewStringUTF(env, "");
        memcpy(buf, val, value_len);
        buf[value_len] = '\0';
        jstring res = (*env)->NewStringUTF(env, buf);
        free(buf);
        return res;
    }
}

JNIEXPORT void JNICALL
Java_my_noveldokusha_tooling_native_1scraper_LexborNative_nativeRemoveNode(
    JNIEnv *env, jclass clazz, jlong node_ptr)
{
    if (!node_ptr) return;
    lxb_dom_node_t *node = (lxb_dom_node_t *) (intptr_t) node_ptr;
    lxb_dom_node_remove(node);
}

JNIEXPORT jstring JNICALL
Java_my_noveldokusha_tooling_native_1scraper_LexborNative_nativeGetOuterHtml(
    JNIEnv *env, jclass clazz, jlong node_ptr)
{
    if (!node_ptr) return (*env)->NewStringUTF(env, "");
    lxb_dom_node_t *node = (lxb_dom_node_t *) (intptr_t) node_ptr;

    lexbor_str_t str = {0};
    lxb_status_t status = lxb_html_serialize_deep_str(node, &str);
    if (status != LXB_STATUS_OK || !str.data) return (*env)->NewStringUTF(env, "");

    jstring res = (*env)->NewStringUTF(env, (const char *) str.data);
    lexbor_str_destroy(&str, node->owner_document->text, false);
    return res;
}

JNIEXPORT jstring JNICALL
Java_my_noveldokusha_tooling_native_1scraper_LexborNative_nativeGetInnerHtml(
    JNIEnv *env, jclass clazz, jlong node_ptr)
{
    if (!node_ptr) return (*env)->NewStringUTF(env, "");
    lxb_dom_node_t *node = (lxb_dom_node_t *) (intptr_t) node_ptr;

    lexbor_str_t str = {0};
    lxb_status_t status = lxb_html_serialize_tree_str(node, &str);
    if (status != LXB_STATUS_OK || !str.data) return (*env)->NewStringUTF(env, "");

    jstring res = (*env)->NewStringUTF(env, (const char *) str.data);
    lexbor_str_destroy(&str, node->owner_document->text, false);
    return res;
}

JNIEXPORT jstring JNICALL
Java_my_noveldokusha_tooling_native_1scraper_LexborNative_nativeGetTagName(
    JNIEnv *env, jclass clazz, jlong node_ptr)
{
    if (!node_ptr) return (*env)->NewStringUTF(env, "");
    lxb_dom_node_t *node = (lxb_dom_node_t *) (intptr_t) node_ptr;
    if (node->type != LXB_DOM_NODE_TYPE_ELEMENT) return (*env)->NewStringUTF(env, "");

    lxb_dom_element_t *element = lxb_dom_interface_element(node);
    size_t len = 0;
    const lxb_char_t *name = lxb_dom_element_qualified_name(element, &len);
    if (!name) return (*env)->NewStringUTF(env, "");

    char *buf = malloc(len + 1);
    if (!buf) return (*env)->NewStringUTF(env, "");
    memcpy(buf, name, len);
    buf[len] = '\0';

    jstring res = (*env)->NewStringUTF(env, buf);
    free(buf);
    return res;
}

JNIEXPORT jint JNICALL
Java_my_noveldokusha_tooling_native_1scraper_LexborNative_nativeGetNodeType(
    JNIEnv *env, jclass clazz, jlong node_ptr)
{
    if (!node_ptr) return 0;
    lxb_dom_node_t *node = (lxb_dom_node_t *) (intptr_t) node_ptr;
    return (jint) node->type;
}

JNIEXPORT jstring JNICALL
Java_my_noveldokusha_tooling_native_1scraper_LexborNative_nativeGetNodeName(
    JNIEnv *env, jclass clazz, jlong node_ptr)
{
    if (!node_ptr) return (*env)->NewStringUTF(env, "");
    lxb_dom_node_t *node = (lxb_dom_node_t *) (intptr_t) node_ptr;
    size_t len = 0;
    const lxb_char_t *name = lxb_dom_node_name(node, &len);
    if (!name) return (*env)->NewStringUTF(env, "");

    char *buf = malloc(len + 1);
    if (!buf) return (*env)->NewStringUTF(env, "");
    memcpy(buf, name, len);
    buf[len] = '\0';

    jstring res = (*env)->NewStringUTF(env, buf);
    free(buf);
    return res;
}

JNIEXPORT jlongArray JNICALL
Java_my_noveldokusha_tooling_native_1scraper_LexborNative_nativeGetChildNodes(
    JNIEnv *env, jclass clazz, jlong node_ptr)
{
    if (!node_ptr) return (*env)->NewLongArray(env, 0);
    lxb_dom_node_t *node = (lxb_dom_node_t *) (intptr_t) node_ptr;

    size_t count = 0;
    for (lxb_dom_node_t *curr = node->first_child; curr != NULL; curr = curr->next) {
        count++;
    }

    jlongArray result = (*env)->NewLongArray(env, count);
    if (!result || count == 0) return result;

    jlong *elems = malloc(count * sizeof(jlong));
    if (!elems) return result;

    size_t i = 0;
    for (lxb_dom_node_t *curr = node->first_child; curr != NULL; curr = curr->next) {
        elems[i++] = (jlong) (intptr_t) curr;
    }

    (*env)->SetLongArrayRegion(env, result, 0, count, elems);
    free(elems);
    return result;
}

JNIEXPORT jlong JNICALL
Java_my_noveldokusha_tooling_native_1scraper_LexborNative_nativeGetParentNode(
    JNIEnv *env, jclass clazz, jlong node_ptr)
{
    if (!node_ptr) return 0;
    lxb_dom_node_t *node = (lxb_dom_node_t *) (intptr_t) node_ptr;
    return (jlong) (intptr_t) node->parent;
}

JNIEXPORT jlong JNICALL
Java_my_noveldokusha_tooling_native_1scraper_LexborNative_nativeGetNextSibling(
    JNIEnv *env, jclass clazz, jlong node_ptr)
{
    if (!node_ptr) return 0;
    lxb_dom_node_t *node = (lxb_dom_node_t *) (intptr_t) node_ptr;
    return (jlong) (intptr_t) node->next;
}

JNIEXPORT jlong JNICALL
Java_my_noveldokusha_tooling_native_1scraper_LexborNative_nativeGetPrevSibling(
    JNIEnv *env, jclass clazz, jlong node_ptr)
{
    if (!node_ptr) return 0;
    lxb_dom_node_t *node = (lxb_dom_node_t *) (intptr_t) node_ptr;
    return (jlong) (intptr_t) node->prev;
}

JNIEXPORT jlong JNICALL
Java_my_noveldokusha_tooling_native_1scraper_LexborNative_nativeGetNextElementSibling(
    JNIEnv *env, jclass clazz, jlong node_ptr)
{
    if (!node_ptr) return 0;
    lxb_dom_node_t *node = (lxb_dom_node_t *) (intptr_t) node_ptr;
    for (lxb_dom_node_t *curr = node->next; curr != NULL; curr = curr->next) {
        if (curr->type == LXB_DOM_NODE_TYPE_ELEMENT) {
            return (jlong) (intptr_t) curr;
        }
    }
    return 0;
}

JNIEXPORT jlong JNICALL
Java_my_noveldokusha_tooling_native_1scraper_LexborNative_nativeGetPrevElementSibling(
    JNIEnv *env, jclass clazz, jlong node_ptr)
{
    if (!node_ptr) return 0;
    lxb_dom_node_t *node = (lxb_dom_node_t *) (intptr_t) node_ptr;
    for (lxb_dom_node_t *curr = node->prev; curr != NULL; curr = curr->prev) {
        if (curr->type == LXB_DOM_NODE_TYPE_ELEMENT) {
            return (jlong) (intptr_t) curr;
        }
    }
    return 0;
}

