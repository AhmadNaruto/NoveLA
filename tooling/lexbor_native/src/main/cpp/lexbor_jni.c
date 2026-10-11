#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

#include <lexbor/html/html.h>
#include <lexbor/css/css.h>
#include <lexbor/selectors/selectors.h>
#include <lexbor/dom/interfaces/element.h>
#include <lexbor/dom/interfaces/node.h>
#include <lexbor/html/interfaces/document.h>

#define LOG_TAG "LexborJNI"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

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
Java_my_noveldokusha_tooling_lexbor_1native_LexborNative_nativeParse(
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
Java_my_noveldokusha_tooling_lexbor_1native_LexborNative_nativeGetRootNode(
    JNIEnv *env, jclass clazz, jlong doc_ptr)
{
    if (!doc_ptr) return 0;
    lxb_html_document_t *document = (lxb_html_document_t *) (intptr_t) doc_ptr;
    lxb_dom_node_t *root = lxb_dom_interface_node(document);
    return (jlong) (intptr_t) root;
}

JNIEXPORT jlong JNICALL
Java_my_noveldokusha_tooling_lexbor_1native_LexborNative_nativeGetBodyNode(
    JNIEnv *env, jclass clazz, jlong doc_ptr)
{
    if (!doc_ptr) return 0;
    lxb_html_document_t *document = (lxb_html_document_t *) (intptr_t) doc_ptr;
    lxb_html_body_element_t *body = lxb_html_document_body_element(document);
    if (!body) return 0;
    return (jlong) (intptr_t) lxb_dom_interface_node(body);
}

JNIEXPORT jlong JNICALL
Java_my_noveldokusha_tooling_lexbor_1native_LexborNative_nativeGetHeadNode(
    JNIEnv *env, jclass clazz, jlong doc_ptr)
{
    if (!doc_ptr) return 0;
    lxb_html_document_t *document = (lxb_html_document_t *) (intptr_t) doc_ptr;
    lxb_html_head_element_t *head = lxb_html_document_head_element(document);
    if (!head) return 0;
    return (jlong) (intptr_t) lxb_dom_interface_node(head);
}

JNIEXPORT jstring JNICALL
Java_my_noveldokusha_tooling_lexbor_1native_LexborNative_nativeGetTitle(
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
Java_my_noveldokusha_tooling_lexbor_1native_LexborNative_nativeFreeDocument(
    JNIEnv *env, jclass clazz, jlong doc_ptr)
{
    if (!doc_ptr) return;
    lxb_html_document_t *document = (lxb_html_document_t *) (intptr_t) doc_ptr;
    lxb_html_document_destroy(document);
}

JNIEXPORT jlongArray JNICALL
Java_my_noveldokusha_tooling_lexbor_1native_LexborNative_nativeSelect(
    JNIEnv *env, jclass clazz, jlong node_ptr, jstring css_query)
{
    if (!node_ptr || !css_query) return NULL;

    lxb_dom_node_t *root_node = (lxb_dom_node_t *) (intptr_t) node_ptr;
    const char *query_c = (*env)->GetStringUTFChars(env, css_query, NULL);
    if (!query_c) return NULL;
    size_t query_len = (*env)->GetStringUTFLength(env, css_query);

    lxb_css_parser_t *parser = lxb_css_parser_create();
    lxb_status_t status = lxb_css_parser_init(parser, NULL);
    if (status != LXB_STATUS_OK) {
        (*env)->ReleaseStringUTFChars(env, css_query, query_c);
        return NULL;
    }

    lxb_selectors_t *selectors = lxb_selectors_create();
    status = lxb_selectors_init(selectors);
    if (status != LXB_STATUS_OK) {
        lxb_css_parser_destroy(parser, true);
        (*env)->ReleaseStringUTFChars(env, css_query, query_c);
        return NULL;
    }

    lxb_css_selector_list_t *list = lxb_css_selectors_parse(parser, (const lxb_char_t *) query_c, query_len);
    (*env)->ReleaseStringUTFChars(env, css_query, query_c);

    if (!list || parser->status != LXB_STATUS_OK) {
        lxb_selectors_destroy(selectors, true);
        lxb_css_parser_destroy(parser, true);
        return NULL;
    }

    lexbor_select_ctx_t ctx = { .nodes = NULL, .count = 0, .capacity = 0, .find_first_only = false };
    status = lxb_selectors_find(selectors, root_node, list, select_callback, &ctx);

    lxb_selectors_destroy(selectors, true);
    lxb_css_parser_destroy(parser, true);
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
Java_my_noveldokusha_tooling_lexbor_1native_LexborNative_nativeSelectFirst(
    JNIEnv *env, jclass clazz, jlong node_ptr, jstring css_query)
{
    if (!node_ptr || !css_query) return 0;

    lxb_dom_node_t *root_node = (lxb_dom_node_t *) (intptr_t) node_ptr;
    const char *query_c = (*env)->GetStringUTFChars(env, css_query, NULL);
    if (!query_c) return 0;
    size_t query_len = (*env)->GetStringUTFLength(env, css_query);

    lxb_css_parser_t *parser = lxb_css_parser_create();
    lxb_status_t status = lxb_css_parser_init(parser, NULL);
    if (status != LXB_STATUS_OK) {
        (*env)->ReleaseStringUTFChars(env, css_query, query_c);
        return 0;
    }

    lxb_selectors_t *selectors = lxb_selectors_create();
    status = lxb_selectors_init(selectors);
    if (status != LXB_STATUS_OK) {
        lxb_css_parser_destroy(parser, true);
        (*env)->ReleaseStringUTFChars(env, css_query, query_c);
        return 0;
    }

    lxb_css_selector_list_t *list = lxb_css_selectors_parse(parser, (const lxb_char_t *) query_c, query_len);
    (*env)->ReleaseStringUTFChars(env, css_query, query_c);

    if (!list || parser->status != LXB_STATUS_OK) {
        lxb_selectors_destroy(selectors, true);
        lxb_css_parser_destroy(parser, true);
        return 0;
    }

    lexbor_select_ctx_t ctx = { .nodes = NULL, .count = 0, .capacity = 0, .find_first_only = true };
    status = lxb_selectors_find(selectors, root_node, list, select_callback, &ctx);

    lxb_selectors_destroy(selectors, true);
    lxb_css_parser_destroy(parser, true);
    lxb_css_selector_list_destroy_memory(list);

    jlong res = 0;
    if (status == LXB_STATUS_OK && ctx.count > 0) {
        res = (jlong) (intptr_t) ctx.nodes[0];
    }

    free(ctx.nodes);
    return res;
}

JNIEXPORT jstring JNICALL
Java_my_noveldokusha_tooling_lexbor_1native_LexborNative_nativeGetText(
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
Java_my_noveldokusha_tooling_lexbor_1native_LexborNative_nativeGetAttr(
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

    char *buf = malloc(value_len + 1);
    if (!buf) return (*env)->NewStringUTF(env, "");
    memcpy(buf, val, value_len);
    buf[value_len] = '\0';

    jstring res = (*env)->NewStringUTF(env, buf);
    free(buf);
    return res;
}

JNIEXPORT void JNICALL
Java_my_noveldokusha_tooling_lexbor_1native_LexborNative_nativeRemoveNode(
    JNIEnv *env, jclass clazz, jlong node_ptr)
{
    if (!node_ptr) return;
    lxb_dom_node_t *node = (lxb_dom_node_t *) (intptr_t) node_ptr;
    lxb_dom_node_remove(node);
}

JNIEXPORT jstring JNICALL
Java_my_noveldokusha_tooling_lexbor_1native_LexborNative_nativeGetOuterHtml(
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
Java_my_noveldokusha_tooling_lexbor_1native_LexborNative_nativeGetInnerHtml(
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
Java_my_noveldokusha_tooling_lexbor_1native_LexborNative_nativeGetTagName(
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
Java_my_noveldokusha_tooling_lexbor_1native_LexborNative_nativeGetNodeType(
    JNIEnv *env, jclass clazz, jlong node_ptr)
{
    if (!node_ptr) return 0;
    lxb_dom_node_t *node = (lxb_dom_node_t *) (intptr_t) node_ptr;
    return (jint) node->type;
}

JNIEXPORT jstring JNICALL
Java_my_noveldokusha_tooling_lexbor_1native_LexborNative_nativeGetNodeName(
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
Java_my_noveldokusha_tooling_lexbor_1native_LexborNative_nativeGetChildNodes(
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
