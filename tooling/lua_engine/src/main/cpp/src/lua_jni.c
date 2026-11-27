#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <signal.h>
#include <unistd.h>
#include <fcntl.h>
#include <stdatomic.h>

#ifdef __ANDROID__
#include <android/log.h>
#define LOG_TAG "Lua"
#endif

#include "lua.h"
#include "lauxlib.h"
#include "lualib.h"

// Cached global references for JNI performance
static JavaVM *g_vm = NULL;

static jclass g_class_LuaValue = NULL;
static jclass g_class_LuaNil = NULL;
static jclass g_class_LuaBoolean = NULL;
static jclass g_class_LuaInteger = NULL;
static jclass g_class_LuaDouble = NULL;
static jclass g_class_LuaString = NULL;
static jclass g_class_LuaTable = NULL;
static jclass g_class_NativeLuaTable = NULL;
static jclass g_class_LuaFunction = NULL;
static jclass g_class_NativeLuaFunction = NULL;
static jclass g_class_LuaUserdata = NULL;
static jclass g_class_Varargs = NULL;
static jclass g_class_LuaError = NULL;
static jclass g_class_String = NULL;
static jmethodID g_mid_String_init_bytes = NULL;
static jstring g_str_utf8 = NULL;

static jmethodID g_mid_LuaTable_keysArray = NULL;
static jmethodID g_mid_LuaTable_get = NULL;

static jmethodID g_mid_LuaUserdata_init = NULL;
static jfieldID g_fid_LuaUserdata_m_instance = NULL;

static jfieldID g_fid_NIL = NULL;
static jfieldID g_fid_TRUE = NULL;
static jfieldID g_fid_FALSE = NULL;

static jmethodID g_mid_valueOf_long = NULL;
static jmethodID g_mid_valueOf_double = NULL;
static jmethodID g_mid_valueOf_string = NULL;

static jmethodID g_mid_LuaBoolean_booleanValue = NULL;
static jmethodID g_mid_LuaInteger_longValue = NULL;
static jmethodID g_mid_LuaDouble_doubleValue = NULL;
static jmethodID g_mid_LuaValue_tojstring = NULL;

static jmethodID g_mid_NativeLuaTable_init = NULL;
static jmethodID g_mid_NativeLuaFunction_init = NULL;

static jfieldID g_fid_NativeLuaTable_ref = NULL;
static jfieldID g_fid_NativeLuaFunction_ref = NULL;

static jmethodID g_mid_LuaFunction_invoke = NULL;
static jmethodID g_mid_Varargs_of = NULL;
static jmethodID g_mid_Varargs_toArray = NULL;

#define CALLBACK_SENTINEL_MT "JNICallbackSentinelMT"

typedef struct {
    jobject globalRef;
} CallbackSentinel;

#include <pthread.h>

static lua_State **g_active_states = NULL;
static size_t g_active_states_count = 0;
static size_t g_active_states_cap = 0;
static pthread_mutex_t g_states_mutex = PTHREAD_MUTEX_INITIALIZER;
static _Atomic uint64_t g_states_epoch = 1;
static __thread lua_State *s_tls_cached_state = NULL;
static __thread uint64_t s_tls_cached_epoch = 0;

static int register_active_state(lua_State *L) {
    if (L == NULL) return 0;
    pthread_mutex_lock(&g_states_mutex);
    for (size_t i = 0; i < g_active_states_count; i++) {
        if (g_active_states[i] == L) {
            pthread_mutex_unlock(&g_states_mutex);
            return 1;
        }
    }
    for (size_t i = 0; i < g_active_states_count; i++) {
        if (g_active_states[i] == NULL) {
            g_active_states[i] = L;
            atomic_fetch_add_explicit(&g_states_epoch, 1, memory_order_release);
            pthread_mutex_unlock(&g_states_mutex);
            return 1;
        }
    }
    if (g_active_states_count < g_active_states_cap) {
        g_active_states[g_active_states_count++] = L;
        atomic_fetch_add_explicit(&g_states_epoch, 1, memory_order_release);
        pthread_mutex_unlock(&g_states_mutex);
        return 1;
    }
    size_t new_cap = (g_active_states_cap == 0) ? 16 : (g_active_states_cap * 2);
    lua_State **new_arr = (lua_State **)realloc(g_active_states, new_cap * sizeof(lua_State *));
    if (new_arr == NULL) {
        pthread_mutex_unlock(&g_states_mutex);
        return 0;
    }
    for (size_t i = g_active_states_cap; i < new_cap; i++) {
        new_arr[i] = NULL;
    }
    g_active_states = new_arr;
    g_active_states_cap = new_cap;
    g_active_states[g_active_states_count++] = L;
    atomic_fetch_add_explicit(&g_states_epoch, 1, memory_order_release);
    pthread_mutex_unlock(&g_states_mutex);
    return 1;
}

static void unregister_active_state(lua_State *L) {
    if (L == NULL) return;
    pthread_mutex_lock(&g_states_mutex);
    for (size_t i = 0; i < g_active_states_count; i++) {
        if (g_active_states[i] == L) {
            g_active_states[i] = NULL;
            break;
        }
    }
    while (g_active_states_count > 0 && g_active_states[g_active_states_count - 1] == NULL) {
        g_active_states_count--;
    }
    atomic_fetch_add_explicit(&g_states_epoch, 1, memory_order_release);
    pthread_mutex_unlock(&g_states_mutex);
}

static int is_active_state(lua_State *L) {
    if (L == NULL) return 0;
    int found = 0;
    pthread_mutex_lock(&g_states_mutex);
    for (size_t i = 0; i < g_active_states_count; i++) {
        if (g_active_states[i] == L) {
            found = 1;
            break;
        }
    }
    pthread_mutex_unlock(&g_states_mutex);
    return found;
}

static lua_State *to_lua_state(jlong ptr) {
    if (ptr == 0) return NULL;
    lua_State *L = (lua_State *)(uintptr_t)ptr;
    uint64_t current_epoch = atomic_load_explicit(&g_states_epoch, memory_order_acquire);
    if (L == s_tls_cached_state && current_epoch == s_tls_cached_epoch) {
        return L;
    }
    if (!is_active_state(L)) {
        if (L == s_tls_cached_state) {
            s_tls_cached_state = NULL;
        }
        return NULL;
    }
    s_tls_cached_state = L;
    s_tls_cached_epoch = current_epoch;
    return L;
}

static JNIEnv *get_jni_env(int *should_detach) {
    JNIEnv *env = NULL;
    *should_detach = 0;
    if (g_vm == NULL) return NULL;
    jint res = (*g_vm)->GetEnv(g_vm, (void **)&env, JNI_VERSION_1_6);
    if (res == JNI_OK) {
        return env;
    }
    if (res == JNI_EDETACHED) {
        if ((*g_vm)->AttachCurrentThread(g_vm, &env, NULL) == JNI_OK) {
            *should_detach = 1;
            return env;
        }
    }
    return NULL;
}

static void throw_lua_error(JNIEnv *env, const char *msg) {
    if (g_class_LuaError != NULL) {
        (*env)->ThrowNew(env, g_class_LuaError, msg ? msg : "Lua error");
    }
}

// Redirect Lua print() output to Android Logcat
static int android_lua_print(lua_State *L) {
    int n = lua_gettop(L);
    char buf[2048];
    size_t offset = 0;
    for (int i = 1; i <= n; i++) {
        if (i > 1 && offset + 1 < sizeof(buf)) {
            buf[offset++] = '\t';
        }
        size_t len = 0;
        const char *s = luaL_tolstring(L, i, &len);
        if (s != NULL && offset + len < sizeof(buf)) {
            memcpy(buf + offset, s, len);
            offset += len;
        }
        lua_pop(L, 1);
    }
    buf[offset] = '\0';
#ifdef __ANDROID__
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "%s", buf);
#else
    printf("%s\n", buf);
    fflush(stdout);
#endif
    return 0;
}

// Garbage-collector metamethod for host function callback userdata
static int callback_sentinel_gc(lua_State *L) {
    CallbackSentinel *s = (CallbackSentinel *)luaL_checkudata(L, 1, CALLBACK_SENTINEL_MT);
    if (s != NULL && s->globalRef != NULL) {
        int should_detach = 0;
        JNIEnv *env = get_jni_env(&should_detach);
        if (env != NULL) {
            (*env)->DeleteGlobalRef(env, s->globalRef);
        }
        s->globalRef = NULL;
        if (should_detach && g_vm != NULL) {
            (*g_vm)->DetachCurrentThread(g_vm);
        }
    }
    return 0;
}

static void register_sentinel_mt(lua_State *L) {
    if (luaL_newmetatable(L, CALLBACK_SENTINEL_MT)) {
        lua_pushcfunction(L, callback_sentinel_gc);
        lua_setfield(L, -2, "__gc");
    }
    lua_pop(L, 1);
}

// Forward declarations
static jobject lua_value_from_stack(JNIEnv *env, lua_State *L, int index, jlong statePtr);
static void push_lua_value(JNIEnv *env, lua_State *L, jobject jval);

// C Dispatcher for Kotlin/Java host callbacks invoked by Lua
static int jni_callback_dispatcher(lua_State *L) {
    int should_detach = 0;
    JNIEnv *env = get_jni_env(&should_detach);
    if (env == NULL) {
        return luaL_error(L, "Failed to obtain JNIEnv for callback execution");
    }

    CallbackSentinel *s = (CallbackSentinel *)luaL_checkudata(L, lua_upvalueindex(1), CALLBACK_SENTINEL_MT);
    if (s == NULL || s->globalRef == NULL) {
        if (should_detach) (*g_vm)->DetachCurrentThread(g_vm);
        return luaL_error(L, "Callback reference has already been released");
    }
    jobject callback = s->globalRef;

    int nargs = lua_gettop(L);
    jlong statePtr = (jlong)(uintptr_t)L;
    jobjectArray argsArray = (*env)->NewObjectArray(env, nargs, g_class_LuaValue, NULL);
    for (int i = 1; i <= nargs; i++) {
        jobject val = lua_value_from_stack(env, L, i, statePtr);
        (*env)->SetObjectArrayElement(env, argsArray, i - 1, val);
        (*env)->DeleteLocalRef(env, val);
    }

    jobject varargsIn = (*env)->CallStaticObjectMethod(env, g_class_Varargs, g_mid_Varargs_of, argsArray);
    (*env)->DeleteLocalRef(env, argsArray);

    jobject varargsOut = (*env)->CallObjectMethod(env, callback, g_mid_LuaFunction_invoke, varargsIn);
    (*env)->DeleteLocalRef(env, varargsIn);

    jthrowable exc = (*env)->ExceptionOccurred(env);
    if (exc != NULL) {
        (*env)->ExceptionClear(env);
        jclass excClass = (*env)->GetObjectClass(env, exc);
        jmethodID midGetMessage = (*env)->GetMethodID(env, excClass, "getMessage", "()Ljava/lang/String;");
        jstring jmsg = (jstring)(*env)->CallObjectMethod(env, exc, midGetMessage);
        const char *msg = jmsg ? (*env)->GetStringUTFChars(env, jmsg, NULL) : "Kotlin callback exception";
        char errBuf[512];
        snprintf(errBuf, sizeof(errBuf), "%s", msg ? msg : "Unknown exception");
        if (jmsg) {
            (*env)->ReleaseStringUTFChars(env, jmsg, msg);
            (*env)->DeleteLocalRef(env, jmsg);
        }
        (*env)->DeleteLocalRef(env, excClass);
        (*env)->DeleteLocalRef(env, exc);
        if (should_detach) (*g_vm)->DetachCurrentThread(g_vm);
        return luaL_error(L, "%s", errBuf);
    }

    int resultCount = 0;
    if (varargsOut != NULL) {
        jobjectArray resArray = (jobjectArray)(*env)->CallObjectMethod(env, varargsOut, g_mid_Varargs_toArray);
        if (resArray != NULL) {
            jsize len = (*env)->GetArrayLength(env, resArray);
            for (jsize i = 0; i < len; i++) {
                jobject v = (*env)->GetObjectArrayElement(env, resArray, i);
                push_lua_value(env, L, v);
                (*env)->DeleteLocalRef(env, v);
            }
            resultCount = (int)len;
            (*env)->DeleteLocalRef(env, resArray);
        }
        (*env)->DeleteLocalRef(env, varargsOut);
    }

    if (should_detach) (*g_vm)->DetachCurrentThread(g_vm);
    return resultCount;
}

static void log_jni(const char *msg) {
    __android_log_print(ANDROID_LOG_INFO, "NOVELA_LUA_JNI", "%s", msg);
    FILE *f = fopen("/sdcard/Download/novela_startup_log.txt", "a");
    if (f) {
        fprintf(f, "JNI: %s\n", msg);
        fclose(f);
    }
}

static void native_crash_handler(int sig, siginfo_t *info, void *ucontext) {
    (void)ucontext;
    int fd = open("/sdcard/Download/novela_crash.txt", O_WRONLY | O_CREAT | O_APPEND, 0666);
    if (fd >= 0) {
        char msg[256];
        int n = snprintf(msg, sizeof(msg),
            "=== NATIVE SIGNAL CRASH ===\nSignal: %d (%s), Code: %d, Fault Address: %p\n",
            sig,
            sig == SIGSEGV ? "SIGSEGV" :
            sig == SIGABRT ? "SIGABRT" :
            sig == SIGBUS  ? "SIGBUS"  :
            sig == SIGFPE  ? "SIGFPE"  :
            sig == SIGILL  ? "SIGILL"  : "UNKNOWN",
            info ? info->si_code : -1,
            info ? info->si_addr : NULL);
        write(fd, msg, (size_t)n);
        close(fd);
    }
    int fd2 = open("/sdcard/Download/novela_startup_log.txt", O_WRONLY | O_CREAT | O_APPEND, 0666);
    if (fd2 >= 0) {
        char msg2[256];
        int n2 = snprintf(msg2, sizeof(msg2),
            "JNI CRASH: Signal %d received at %p\n", sig, info ? info->si_addr : NULL);
        write(fd2, msg2, (size_t)n2);
        close(fd2);
    }
    signal(sig, SIG_DFL);
    raise(sig);
}

static void install_crash_handlers(void) {
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_flags = SA_SIGINFO;
    sa.sa_sigaction = native_crash_handler;
    sigemptyset(&sa.sa_mask);
    sigaction(SIGSEGV, &sa, NULL);
    sigaction(SIGABRT, &sa, NULL);
    sigaction(SIGBUS, &sa, NULL);
    sigaction(SIGFPE, &sa, NULL);
    sigaction(SIGILL, &sa, NULL);
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    install_crash_handlers();
    log_jni("JNI_OnLoad started");
    g_vm = vm;
    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        log_jni("JNI_OnLoad: GetEnv failed");
        return JNI_ERR;
    }

    jclass local_LuaValue = (*env)->FindClass(env, "com/github/anaruto/lua/LuaValue");
    if (local_LuaValue == NULL) {
        log_jni("JNI_OnLoad: FindClass com/github/anaruto/lua/LuaValue failed!");
        return JNI_ERR;
    }
    g_class_LuaValue = (jclass)(*env)->NewGlobalRef(env, local_LuaValue);
    (*env)->DeleteLocalRef(env, local_LuaValue);

    jclass local_LuaNil = (*env)->FindClass(env, "com/github/anaruto/lua/LuaNil");
    if (local_LuaNil != NULL) {
        g_class_LuaNil = (jclass)(*env)->NewGlobalRef(env, local_LuaNil);
        (*env)->DeleteLocalRef(env, local_LuaNil);
    }

    jclass local_LuaBoolean = (*env)->FindClass(env, "com/github/anaruto/lua/LuaBoolean");
    if (local_LuaBoolean != NULL) {
        g_class_LuaBoolean = (jclass)(*env)->NewGlobalRef(env, local_LuaBoolean);
        (*env)->DeleteLocalRef(env, local_LuaBoolean);
    }

    jclass local_LuaInteger = (*env)->FindClass(env, "com/github/anaruto/lua/LuaInteger");
    if (local_LuaInteger != NULL) {
        g_class_LuaInteger = (jclass)(*env)->NewGlobalRef(env, local_LuaInteger);
        (*env)->DeleteLocalRef(env, local_LuaInteger);
    }

    jclass local_LuaDouble = (*env)->FindClass(env, "com/github/anaruto/lua/LuaDouble");
    if (local_LuaDouble != NULL) {
        g_class_LuaDouble = (jclass)(*env)->NewGlobalRef(env, local_LuaDouble);
        (*env)->DeleteLocalRef(env, local_LuaDouble);
    }

    jclass local_LuaString = (*env)->FindClass(env, "com/github/anaruto/lua/LuaString");
    if (local_LuaString != NULL) {
        g_class_LuaString = (jclass)(*env)->NewGlobalRef(env, local_LuaString);
        (*env)->DeleteLocalRef(env, local_LuaString);
    }

    jclass local_LuaTable = (*env)->FindClass(env, "com/github/anaruto/lua/LuaTable");
    if (local_LuaTable != NULL) {
        g_class_LuaTable = (jclass)(*env)->NewGlobalRef(env, local_LuaTable);
        (*env)->DeleteLocalRef(env, local_LuaTable);
    }

    jclass local_NativeLuaTable = (*env)->FindClass(env, "com/github/anaruto/lua/NativeLuaTable");
    if (local_NativeLuaTable != NULL) {
        g_class_NativeLuaTable = (jclass)(*env)->NewGlobalRef(env, local_NativeLuaTable);
        (*env)->DeleteLocalRef(env, local_NativeLuaTable);
    }

    jclass local_LuaFunction = (*env)->FindClass(env, "com/github/anaruto/lua/LuaFunction");
    if (local_LuaFunction != NULL) {
        g_class_LuaFunction = (jclass)(*env)->NewGlobalRef(env, local_LuaFunction);
        (*env)->DeleteLocalRef(env, local_LuaFunction);
    }

    jclass local_LuaUserdata = (*env)->FindClass(env, "com/github/anaruto/lua/LuaUserdata");
    if (local_LuaUserdata != NULL) {
        g_class_LuaUserdata = (jclass)(*env)->NewGlobalRef(env, local_LuaUserdata);
        (*env)->DeleteLocalRef(env, local_LuaUserdata);
    }

    jclass local_NativeLuaFunction = (*env)->FindClass(env, "com/github/anaruto/lua/NativeLuaFunction");
    if (local_NativeLuaFunction != NULL) {
        g_class_NativeLuaFunction = (jclass)(*env)->NewGlobalRef(env, local_NativeLuaFunction);
        (*env)->DeleteLocalRef(env, local_NativeLuaFunction);
    }

    jclass local_Varargs = (*env)->FindClass(env, "com/github/anaruto/lua/Varargs");
    if (local_Varargs != NULL) {
        g_class_Varargs = (jclass)(*env)->NewGlobalRef(env, local_Varargs);
        (*env)->DeleteLocalRef(env, local_Varargs);
    }

    jclass local_LuaError = (*env)->FindClass(env, "com/github/anaruto/lua/LuaError");
    if (local_LuaError != NULL) {
        g_class_LuaError = (jclass)(*env)->NewGlobalRef(env, local_LuaError);
        (*env)->DeleteLocalRef(env, local_LuaError);
    }

    jclass local_String = (*env)->FindClass(env, "java/lang/String");
    if (local_String != NULL) {
        g_class_String = (jclass)(*env)->NewGlobalRef(env, local_String);
        g_mid_String_init_bytes = (*env)->GetMethodID(env, local_String, "<init>", "([BLjava/lang/String;)V");
        (*env)->DeleteLocalRef(env, local_String);
    }

    jstring local_utf8 = (*env)->NewStringUTF(env, "UTF-8");
    if (local_utf8 != NULL) {
        g_str_utf8 = (jstring)(*env)->NewGlobalRef(env, local_utf8);
        (*env)->DeleteLocalRef(env, local_utf8);
    }

    // Cache field IDs
    g_fid_NIL = (*env)->GetStaticFieldID(env, g_class_LuaValue, "NIL", "Lcom/github/anaruto/lua/LuaNil;");
    g_fid_TRUE = (*env)->GetStaticFieldID(env, g_class_LuaValue, "TRUE", "Lcom/github/anaruto/lua/LuaBoolean;");
    g_fid_FALSE = (*env)->GetStaticFieldID(env, g_class_LuaValue, "FALSE", "Lcom/github/anaruto/lua/LuaBoolean;");

    // Cache method IDs
    if (g_class_LuaInteger != NULL) {
        g_mid_valueOf_long = (*env)->GetStaticMethodID(env, g_class_LuaInteger, "valueOf", "(J)Lcom/github/anaruto/lua/LuaInteger;");
        g_mid_LuaInteger_longValue = (*env)->GetMethodID(env, g_class_LuaInteger, "longValue", "()J");
    }
    if (g_class_LuaDouble != NULL) {
        g_mid_valueOf_double = (*env)->GetStaticMethodID(env, g_class_LuaDouble, "valueOf", "(D)Lcom/github/anaruto/lua/LuaDouble;");
        g_mid_LuaDouble_doubleValue = (*env)->GetMethodID(env, g_class_LuaDouble, "doubleValue", "()D");
    }
    if (g_class_LuaString != NULL) {
        g_mid_valueOf_string = (*env)->GetStaticMethodID(env, g_class_LuaString, "valueOf", "(Ljava/lang/String;)Lcom/github/anaruto/lua/LuaString;");
    }
    if (g_class_LuaBoolean != NULL) {
        g_mid_LuaBoolean_booleanValue = (*env)->GetMethodID(env, g_class_LuaBoolean, "booleanValue", "()Z");
    }
    if (g_class_LuaValue != NULL) {
        g_mid_LuaValue_tojstring = (*env)->GetMethodID(env, g_class_LuaValue, "tojstring", "()Ljava/lang/String;");
    }

    if (g_class_LuaTable != NULL) {
        g_mid_LuaTable_keysArray = (*env)->GetMethodID(env, g_class_LuaTable, "keysArray", "()[Lcom/github/anaruto/lua/LuaValue;");
        g_mid_LuaTable_get = (*env)->GetMethodID(env, g_class_LuaTable, "get", "(Lcom/github/anaruto/lua/LuaValue;)Lcom/github/anaruto/lua/LuaValue;");
    }

    if (g_class_NativeLuaTable != NULL) {
        g_mid_NativeLuaTable_init = (*env)->GetMethodID(env, g_class_NativeLuaTable, "<init>", "(JI)V");
        g_fid_NativeLuaTable_ref = (*env)->GetFieldID(env, g_class_NativeLuaTable, "ref", "I");
    }

    if (g_class_LuaUserdata != NULL) {
        g_mid_LuaUserdata_init = (*env)->GetMethodID(env, g_class_LuaUserdata, "<init>", "(Ljava/lang/Object;)V");
        g_fid_LuaUserdata_m_instance = (*env)->GetFieldID(env, g_class_LuaUserdata, "m_instance", "Ljava/lang/Object;");
    }

    if (g_class_NativeLuaFunction != NULL) {
        g_mid_NativeLuaFunction_init = (*env)->GetMethodID(env, g_class_NativeLuaFunction, "<init>", "(JI)V");
        g_fid_NativeLuaFunction_ref = (*env)->GetFieldID(env, g_class_NativeLuaFunction, "ref", "I");
    }

    if (g_class_LuaFunction != NULL) {
        g_mid_LuaFunction_invoke = (*env)->GetMethodID(env, g_class_LuaFunction, "invoke", "(Lcom/github/anaruto/lua/Varargs;)Lcom/github/anaruto/lua/Varargs;");
    }

    if (g_class_Varargs != NULL) {
        g_mid_Varargs_of = (*env)->GetStaticMethodID(env, g_class_Varargs, "of", "([Lcom/github/anaruto/lua/LuaValue;)Lcom/github/anaruto/lua/Varargs;");
        g_mid_Varargs_toArray = (*env)->GetMethodID(env, g_class_Varargs, "toArray", "()[Lcom/github/anaruto/lua/LuaValue;");
    }

    if ((*env)->ExceptionCheck(env)) {
        log_jni("WARNING: Exception occurred during JNI_OnLoad, clearing...");
        (*env)->ExceptionClear(env);
    }

    log_jni("JNI_OnLoad completed successfully");
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM *vm, void *reserved) {
    (void)reserved;
    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        return;
    }

    if (g_class_LuaValue) (*env)->DeleteGlobalRef(env, g_class_LuaValue);
    if (g_class_LuaNil) (*env)->DeleteGlobalRef(env, g_class_LuaNil);
    if (g_class_LuaBoolean) (*env)->DeleteGlobalRef(env, g_class_LuaBoolean);
    if (g_class_LuaInteger) (*env)->DeleteGlobalRef(env, g_class_LuaInteger);
    if (g_class_LuaDouble) (*env)->DeleteGlobalRef(env, g_class_LuaDouble);
    if (g_class_LuaString) (*env)->DeleteGlobalRef(env, g_class_LuaString);
    if (g_class_LuaTable) (*env)->DeleteGlobalRef(env, g_class_LuaTable);
    if (g_class_NativeLuaTable) (*env)->DeleteGlobalRef(env, g_class_NativeLuaTable);
    if (g_class_LuaFunction) (*env)->DeleteGlobalRef(env, g_class_LuaFunction);
    if (g_class_NativeLuaFunction) (*env)->DeleteGlobalRef(env, g_class_NativeLuaFunction);
    if (g_class_LuaUserdata) (*env)->DeleteGlobalRef(env, g_class_LuaUserdata);
    if (g_class_Varargs) (*env)->DeleteGlobalRef(env, g_class_Varargs);
    if (g_class_LuaError) (*env)->DeleteGlobalRef(env, g_class_LuaError);
    if (g_class_String) (*env)->DeleteGlobalRef(env, g_class_String);
    if (g_str_utf8) (*env)->DeleteGlobalRef(env, g_str_utf8);
}

// Convert a Lua stack value to a polymorphic jobject (LuaValue)
static jobject lua_value_from_stack(JNIEnv *env, lua_State *L, int index, jlong statePtr) {
    int type = lua_type(L, index);
    switch (type) {
        case LUA_TNIL:
        case LUA_TNONE:
            return (*env)->GetStaticObjectField(env, g_class_LuaValue, g_fid_NIL);

        case LUA_TBOOLEAN:
            return lua_toboolean(L, index)
                ? (*env)->GetStaticObjectField(env, g_class_LuaValue, g_fid_TRUE)
                : (*env)->GetStaticObjectField(env, g_class_LuaValue, g_fid_FALSE);

        case LUA_TNUMBER:
            if (lua_isinteger(L, index)) {
                jlong val = (jlong)lua_tointeger(L, index);
                return (*env)->CallStaticObjectMethod(env, g_class_LuaInteger, g_mid_valueOf_long, val);
            } else {
                jdouble val = (jdouble)lua_tonumber(L, index);
                return (*env)->CallStaticObjectMethod(env, g_class_LuaDouble, g_mid_valueOf_double, val);
            }

        case LUA_TSTRING: {
            size_t len = 0;
            const char *str = lua_tolstring(L, index, &len);
            jstring js = NULL;
            if (str != NULL && g_class_String != NULL && g_mid_String_init_bytes != NULL && g_str_utf8 != NULL) {
                jbyteArray bytes = (*env)->NewByteArray(env, (jsize)len);
                if (bytes != NULL) {
                    (*env)->SetByteArrayRegion(env, bytes, 0, (jsize)len, (const jbyte *)str);
                    js = (jstring)(*env)->NewObject(env, g_class_String, g_mid_String_init_bytes, bytes, g_str_utf8);
                    (*env)->DeleteLocalRef(env, bytes);
                }
            }
            if (js == NULL) {
                js = (*env)->NewStringUTF(env, str ? str : "");
            }
            jobject obj = (*env)->CallStaticObjectMethod(env, g_class_LuaString, g_mid_valueOf_string, js);
            (*env)->DeleteLocalRef(env, js);
            return obj;
        }

        case LUA_TTABLE: {
            lua_pushvalue(L, index);
            int ref = luaL_ref(L, LUA_REGISTRYINDEX);
            if (g_class_NativeLuaTable != NULL && g_mid_NativeLuaTable_init != NULL) {
                jobject obj = (*env)->NewObject(env, g_class_NativeLuaTable, g_mid_NativeLuaTable_init, statePtr, (jint)ref);
                if ((*env)->ExceptionCheck(env)) {
                    (*env)->ExceptionClear(env);
                    return (*env)->GetStaticObjectField(env, g_class_LuaValue, g_fid_NIL);
                }
                return obj;
            }
            return (*env)->GetStaticObjectField(env, g_class_LuaValue, g_fid_NIL);
        }

        case LUA_TFUNCTION: {
            lua_pushvalue(L, index);
            int ref = luaL_ref(L, LUA_REGISTRYINDEX);
            if (g_class_NativeLuaFunction != NULL && g_mid_NativeLuaFunction_init != NULL) {
                jobject obj = (*env)->NewObject(env, g_class_NativeLuaFunction, g_mid_NativeLuaFunction_init, statePtr, (jint)ref);
                if ((*env)->ExceptionCheck(env)) {
                    (*env)->ExceptionClear(env);
                    return (*env)->GetStaticObjectField(env, g_class_LuaValue, g_fid_NIL);
                }
                return obj;
            }
            return (*env)->GetStaticObjectField(env, g_class_LuaValue, g_fid_NIL);
        }

        case LUA_TUSERDATA: {
            CallbackSentinel *s = (CallbackSentinel *)luaL_testudata(L, index, CALLBACK_SENTINEL_MT);
            if (s != NULL && s->globalRef != NULL && g_class_LuaUserdata != NULL) {
                return (*env)->NewObject(env, g_class_LuaUserdata, g_mid_LuaUserdata_init, s->globalRef);
            }
            // Fall through to default
            const char *s_str = luaL_tolstring(L, index, NULL);
            jstring js = (*env)->NewStringUTF(env, s_str ? s_str : "");
            lua_pop(L, 1);
            jobject obj = (*env)->CallStaticObjectMethod(env, g_class_LuaString, g_mid_valueOf_string, js);
            (*env)->DeleteLocalRef(env, js);
            return obj;
        }

        default: {
            // Userdata, Thread, etc. - fall back to string representation
            const char *s = luaL_tolstring(L, index, NULL);
            jstring js = (*env)->NewStringUTF(env, s ? s : "");
            lua_pop(L, 1);
            jobject obj = (*env)->CallStaticObjectMethod(env, g_class_LuaString, g_mid_valueOf_string, js);
            (*env)->DeleteLocalRef(env, js);
            return obj;
        }
    }
}

// Push a polymorphic Kotlin LuaValue onto the Lua stack
static void push_lua_value(JNIEnv *env, lua_State *L, jobject jval) {
    if (jval == NULL) {
        lua_pushnil(L);
        return;
    }

    if ((*env)->IsInstanceOf(env, jval, g_class_LuaNil)) {
        lua_pushnil(L);
    } else if ((*env)->IsInstanceOf(env, jval, g_class_LuaBoolean)) {
        jboolean b = (*env)->CallBooleanMethod(env, jval, g_mid_LuaBoolean_booleanValue);
        lua_pushboolean(L, b ? 1 : 0);
    } else if ((*env)->IsInstanceOf(env, jval, g_class_LuaInteger)) {
        jlong l = (*env)->CallLongMethod(env, jval, g_mid_LuaInteger_longValue);
        lua_pushinteger(L, (lua_Integer)l);
    } else if ((*env)->IsInstanceOf(env, jval, g_class_LuaDouble)) {
        jdouble d = (*env)->CallDoubleMethod(env, jval, g_mid_LuaDouble_doubleValue);
        lua_pushnumber(L, (lua_Number)d);
    } else if ((*env)->IsInstanceOf(env, jval, g_class_LuaString)) {
        jstring js = (jstring)(*env)->CallObjectMethod(env, jval, g_mid_LuaValue_tojstring);
        if (js != NULL) {
            const char *s = (*env)->GetStringUTFChars(env, js, NULL);
            lua_pushstring(L, s ? s : "");
            (*env)->ReleaseStringUTFChars(env, js, s);
            (*env)->DeleteLocalRef(env, js);
        } else {
            lua_pushstring(L, "");
        }
    } else if (g_class_NativeLuaTable != NULL && (*env)->IsInstanceOf(env, jval, g_class_NativeLuaTable)) {
        jint ref = (g_fid_NativeLuaTable_ref != NULL) ? (*env)->GetIntField(env, jval, g_fid_NativeLuaTable_ref) : -1;
        if (ref != -1) {
            lua_rawgeti(L, LUA_REGISTRYINDEX, ref);
        } else {
            lua_pushnil(L);
        }
    } else if (g_class_LuaTable != NULL && (*env)->IsInstanceOf(env, jval, g_class_LuaTable)) {
        // Kotlin in-memory LuaTable: create new table in Lua and copy entries
        lua_newtable(L);
        if (g_mid_LuaTable_keysArray != NULL && g_mid_LuaTable_get != NULL) {
            jobjectArray keys = (jobjectArray)(*env)->CallObjectMethod(env, jval, g_mid_LuaTable_keysArray);
            if (keys != NULL) {
                jsize len = (*env)->GetArrayLength(env, keys);
                for (jsize i = 0; i < len; i++) {
                    jobject k = (*env)->GetObjectArrayElement(env, keys, i);
                    jobject v = (*env)->CallObjectMethod(env, jval, g_mid_LuaTable_get, k);
                    push_lua_value(env, L, k);
                    push_lua_value(env, L, v);
                    lua_settable(L, -3);
                    if (k) (*env)->DeleteLocalRef(env, k);
                    if (v) (*env)->DeleteLocalRef(env, v);
                }
                (*env)->DeleteLocalRef(env, keys);
            }
        }
    } else if (g_class_NativeLuaFunction != NULL && (*env)->IsInstanceOf(env, jval, g_class_NativeLuaFunction)) {
        jint ref = (g_fid_NativeLuaFunction_ref != NULL) ? (*env)->GetIntField(env, jval, g_fid_NativeLuaFunction_ref) : -1;
        if (ref != -1) {
            lua_rawgeti(L, LUA_REGISTRYINDEX, ref);
        } else {
            lua_pushnil(L);
        }
    } else if ((*env)->IsInstanceOf(env, jval, g_class_LuaFunction)) {
        // Kotlin/Java host function callback registered into Lua
        register_sentinel_mt(L);
        CallbackSentinel *s = (CallbackSentinel *)lua_newuserdata(L, sizeof(CallbackSentinel));
        s->globalRef = (*env)->NewGlobalRef(env, jval);
        luaL_setmetatable(L, CALLBACK_SENTINEL_MT);
        lua_pushcclosure(L, jni_callback_dispatcher, 1);
    } else if (g_class_LuaUserdata != NULL && (*env)->IsInstanceOf(env, jval, g_class_LuaUserdata)) {
        // Kotlin/Java host userdata
        register_sentinel_mt(L);
        CallbackSentinel *s = (CallbackSentinel *)lua_newuserdata(L, sizeof(CallbackSentinel));
        jobject inst = (*env)->GetObjectField(env, jval, g_fid_LuaUserdata_m_instance);
        s->globalRef = inst ? (*env)->NewGlobalRef(env, inst) : NULL;
        luaL_setmetatable(L, CALLBACK_SENTINEL_MT);
    } else {
        // Fallback: convert to string via tojstring()
        jstring js = (jstring)(*env)->CallObjectMethod(env, jval, g_mid_LuaValue_tojstring);
        if (js != NULL) {
            const char *s = (*env)->GetStringUTFChars(env, js, NULL);
            lua_pushstring(L, s ? s : "");
            (*env)->ReleaseStringUTFChars(env, js, s);
            (*env)->DeleteLocalRef(env, js);
        } else {
            lua_pushnil(L);
        }
    }
}

// =========================================================================
// Globals native bindings (LuaJ architecture)
// =========================================================================

static int string_call_self(lua_State *L) {
    lua_pushvalue(L, 1);
    return 1;
}

JNIEXPORT jlong JNICALL
Java_com_github_anaruto_lua_Globals_nativeNewState(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    lua_State *L = luaL_newstate();
    if (L != NULL) {
        register_active_state(L);
        luaL_openlibs(L);
        // Hook print to Android Logcat
        lua_register(L, "print", android_lua_print);

        // Support Shosetsu/NoveLA OOP string getter idiom: ("text"):text() or el:text()
        lua_pushliteral(L, "");
        if (lua_getmetatable(L, -1)) {
            lua_pushcfunction(L, string_call_self);
            lua_setfield(L, -2, "__call");
            lua_pop(L, 1);
        }
        lua_pop(L, 1);
    }
    return (jlong)(uintptr_t)L;
}

JNIEXPORT void JNICALL
Java_com_github_anaruto_lua_Globals_nativeClose(JNIEnv *env, jclass clazz, jlong ptr) {
    (void)env;
    (void)clazz;
    if (ptr == 0) return;
    lua_State *L = (lua_State *)(uintptr_t)ptr;
    if (is_active_state(L)) {
        unregister_active_state(L);
        lua_close(L);
    }
}

JNIEXPORT jint JNICALL
Java_com_github_anaruto_lua_Globals_nativeLoad(JNIEnv *env, jclass clazz, jlong ptr, jstring jscript, jstring jname) {
    (void)clazz;
    lua_State *L = to_lua_state(ptr);
    if (L == NULL || jscript == NULL) {
        throw_lua_error(env, "Invalid Lua state or null script");
        return -1;
    }

    const char *script = (*env)->GetStringUTFChars(env, jscript, NULL);
    const char *name = jname ? (*env)->GetStringUTFChars(env, jname, NULL) : "chunk";
    int status = luaL_loadbuffer(L, script, strlen(script), name);
    (*env)->ReleaseStringUTFChars(env, jscript, script);
    if (jname) (*env)->ReleaseStringUTFChars(env, jname, name);

    if (status != LUA_OK) {
        const char *err = lua_tostring(L, -1);
        throw_lua_error(env, err ? err : "Compilation error");
        lua_pop(L, 1);
        return -1;
    }

    int ref = luaL_ref(L, LUA_REGISTRYINDEX);
    return (jint)ref;
}

JNIEXPORT jint JNICALL
Java_com_github_anaruto_lua_Globals_nativeLoadBytes(JNIEnv *env, jclass clazz, jlong ptr, jbyteArray jbytes, jstring jname) {
    (void)clazz;
    lua_State *L = to_lua_state(ptr);
    if (L == NULL || jbytes == NULL) {
        throw_lua_error(env, "Invalid Lua state or null byte array");
        return -1;
    }

    jsize len = (*env)->GetArrayLength(env, jbytes);
    jbyte *bytes = (*env)->GetByteArrayElements(env, jbytes, NULL);
    const char *name = jname ? (*env)->GetStringUTFChars(env, jname, NULL) : "chunk";

    int status = luaL_loadbuffer(L, (const char *)bytes, (size_t)len, name);

    (*env)->ReleaseByteArrayElements(env, jbytes, bytes, JNI_ABORT);
    if (jname) (*env)->ReleaseStringUTFChars(env, jname, name);

    if (status != LUA_OK) {
        const char *err = lua_tostring(L, -1);
        throw_lua_error(env, err ? err : "Compilation error");
        lua_pop(L, 1);
        return -1;
    }

    int ref = luaL_ref(L, LUA_REGISTRYINDEX);
    return (jint)ref;
}

typedef struct {
    char *buf;
    size_t size;
    size_t cap;
} DumpBuffer;

static int dump_buffer_writer(lua_State *L, const void *p, size_t sz, void *ud) {
    (void)L;
    DumpBuffer *db = (DumpBuffer *)ud;
    if (db->size + sz > db->cap) {
        size_t new_cap = (db->cap == 0) ? 4096 : (db->cap * 2);
        while (new_cap < db->size + sz) new_cap *= 2;
        char *new_buf = (char *)realloc(db->buf, new_cap);
        if (!new_buf) return 1;
        db->buf = new_buf;
        db->cap = new_cap;
    }
    memcpy(db->buf + db->size, p, sz);
    db->size += sz;
    return 0;
}

JNIEXPORT jbyteArray JNICALL
Java_com_github_anaruto_lua_Globals_nativeDump(JNIEnv *env, jclass clazz, jlong ptr, jint funcRef) {
    (void)clazz;
    lua_State *L = to_lua_state(ptr);
    if (L == NULL || funcRef == -1) return NULL;

    lua_rawgeti(L, LUA_REGISTRYINDEX, funcRef);
    if (!lua_isfunction(L, -1)) {
        lua_pop(L, 1);
        return NULL;
    }

    DumpBuffer db = {0};
    int status = lua_dump(L, dump_buffer_writer, &db, 0);
    lua_pop(L, 1); // pop function

    if (status != 0 || db.buf == NULL) {
        if (db.buf) free(db.buf);
        return NULL;
    }

    jbyteArray res = (*env)->NewByteArray(env, (jsize)db.size);
    if (res != NULL) {
        (*env)->SetByteArrayRegion(env, res, 0, (jsize)db.size, (const jbyte *)db.buf);
    }
    free(db.buf);
    return res;
}

JNIEXPORT void JNICALL
Java_com_github_anaruto_lua_Globals_nativeSanitizeSandbox(JNIEnv *env, jclass clazz, jlong ptr) {
    (void)env;
    (void)clazz;
    lua_State *L = to_lua_state(ptr);
    if (L == NULL) return;

    lua_pushnil(L); lua_setglobal(L, "luajava");
    lua_pushnil(L); lua_setglobal(L, "io");
    lua_pushnil(L); lua_setglobal(L, "load");
    lua_pushnil(L); lua_setglobal(L, "loadfile");
    lua_pushnil(L); lua_setglobal(L, "loadstring");
    lua_pushnil(L); lua_setglobal(L, "dofile");
    lua_pushnil(L); lua_setglobal(L, "require");
    lua_pushnil(L); lua_setglobal(L, "package");
    lua_pushnil(L); lua_setglobal(L, "debug");

    lua_getglobal(L, "os");
    if (lua_istable(L, -1)) {
        lua_pushnil(L); lua_setfield(L, -2, "execute");
        lua_pushnil(L); lua_setfield(L, -2, "exit");
        lua_pushnil(L); lua_setfield(L, -2, "getenv");
        lua_pushnil(L); lua_setfield(L, -2, "rename");
        lua_pushnil(L); lua_setfield(L, -2, "remove");
        lua_pushnil(L); lua_setfield(L, -2, "tmpname");
    }
    lua_pop(L, 1);
}

JNIEXPORT jint JNICALL
Java_com_github_anaruto_lua_Globals_nativeLoadFile(JNIEnv *env, jclass clazz, jlong ptr, jstring jpath) {
    (void)clazz;
    lua_State *L = to_lua_state(ptr);
    if (L == NULL || jpath == NULL) {
        throw_lua_error(env, "Invalid Lua state or null file path");
        return -1;
    }

    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    int status = luaL_loadfile(L, path);
    (*env)->ReleaseStringUTFChars(env, jpath, path);

    if (status != LUA_OK) {
        const char *err = lua_tostring(L, -1);
        throw_lua_error(env, err ? err : "Failed to load script file");
        lua_pop(L, 1);
        return -1;
    }

    int ref = luaL_ref(L, LUA_REGISTRYINDEX);
    return (jint)ref;
}

static int lua_error_traceback_handler(lua_State *L) {
    const char *msg = lua_tostring(L, 1);
    if (msg != NULL) {
        luaL_traceback(L, L, msg, 1);
    } else {
        luaL_traceback(L, L, "(unknown error)", 1);
    }
    return 1;
}

JNIEXPORT jobjectArray JNICALL
Java_com_github_anaruto_lua_Globals_nativeInvoke(JNIEnv *env, jclass clazz, jlong ptr, jint funcRef, jobjectArray jargs) {
    (void)clazz;
    lua_State *L = to_lua_state(ptr);
    if (L == NULL || funcRef == -1) {
        throw_lua_error(env, "Invalid Lua state or invalid function reference");
        return NULL;
    }

    int base = lua_gettop(L);
    lua_pushcfunction(L, lua_error_traceback_handler);
    int err_handler = base + 1;

    lua_rawgeti(L, LUA_REGISTRYINDEX, funcRef);
    if (!lua_isfunction(L, -1)) {
        lua_pop(L, 2);
        throw_lua_error(env, "Referenced object is not a function");
        return NULL;
    }

    jsize nargs = jargs ? (*env)->GetArrayLength(env, jargs) : 0;
    for (jsize i = 0; i < nargs; i++) {
        jobject arg = (*env)->GetObjectArrayElement(env, jargs, i);
        push_lua_value(env, L, arg);
        (*env)->DeleteLocalRef(env, arg);
    }

    int status = lua_pcall(L, (int)nargs, LUA_MULTRET, err_handler);
    if (status != LUA_OK) {
        const char *err = lua_tostring(L, -1);
        throw_lua_error(env, err ? err : "Lua runtime execution error");
        lua_pop(L, 2);
        return NULL;
    }

    int nresults = lua_gettop(L) - err_handler;
    jobjectArray resArray = (*env)->NewObjectArray(env, nresults, g_class_LuaValue, NULL);
    for (int i = 0; i < nresults; i++) {
        jobject val = lua_value_from_stack(env, L, err_handler + 1 + i, ptr);
        (*env)->SetObjectArrayElement(env, resArray, i, val);
        (*env)->DeleteLocalRef(env, val);
    }
    lua_pop(L, nresults + 1);
    return resArray;
}

JNIEXPORT void JNICALL
Java_com_github_anaruto_lua_Globals_nativeUnref(JNIEnv *env, jclass clazz, jlong ptr, jint ref) {
    (void)env;
    (void)clazz;
    lua_State *L = to_lua_state(ptr);
    if (L != NULL && ref != -1) {
        luaL_unref(L, LUA_REGISTRYINDEX, ref);
    }
}

JNIEXPORT jobject JNICALL
Java_com_github_anaruto_lua_Globals_nativeGetGlobal(JNIEnv *env, jclass clazz, jlong ptr, jstring jname) {
    (void)clazz;
    lua_State *L = to_lua_state(ptr);
    if (L == NULL || jname == NULL) {
        return (*env)->GetStaticObjectField(env, g_class_LuaValue, g_fid_NIL);
    }

    const char *name = (*env)->GetStringUTFChars(env, jname, NULL);
    lua_getglobal(L, name);
    (*env)->ReleaseStringUTFChars(env, jname, name);

    jobject val = lua_value_from_stack(env, L, -1, ptr);
    lua_pop(L, 1);
    return val;
}

JNIEXPORT jboolean JNICALL
Java_com_github_anaruto_lua_Globals_nativeHasGlobal(JNIEnv *env, jclass clazz, jlong ptr, jstring jname) {
    (void)clazz;
    lua_State *L = to_lua_state(ptr);
    if (L == NULL || jname == NULL) return JNI_FALSE;

    const char *name = (*env)->GetStringUTFChars(env, jname, NULL);
    if (!name) return JNI_FALSE;
    int type = lua_getglobal(L, name);
    lua_pop(L, 1);
    (*env)->ReleaseStringUTFChars(env, jname, name);
    return (type != LUA_TNIL && type != LUA_TNONE) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_github_anaruto_lua_Globals_nativeSetGlobal(JNIEnv *env, jclass clazz, jlong ptr, jstring jname, jobject jval) {
    (void)clazz;
    lua_State *L = to_lua_state(ptr);
    if (L == NULL || jname == NULL) return;

    const char *name = (*env)->GetStringUTFChars(env, jname, NULL);
    push_lua_value(env, L, jval);
    lua_setglobal(L, name);
    (*env)->ReleaseStringUTFChars(env, jname, name);
}

JNIEXPORT jobject JNICALL
Java_com_github_anaruto_lua_Globals_nativeTableGet(JNIEnv *env, jclass clazz, jlong ptr, jint tableRef, jobject jkey) {
    (void)clazz;
    lua_State *L = to_lua_state(ptr);
    if (L == NULL || tableRef == -1) {
        return (*env)->GetStaticObjectField(env, g_class_LuaValue, g_fid_NIL);
    }

    lua_rawgeti(L, LUA_REGISTRYINDEX, tableRef);
    if (!lua_istable(L, -1)) {
        lua_pop(L, 1);
        return (*env)->GetStaticObjectField(env, g_class_LuaValue, g_fid_NIL);
    }

    push_lua_value(env, L, jkey);
    lua_gettable(L, -2);
    jobject val = lua_value_from_stack(env, L, -1, ptr);
    lua_pop(L, 2); // pop result and table
    return val;
}

JNIEXPORT void JNICALL
Java_com_github_anaruto_lua_Globals_nativeTableSet(JNIEnv *env, jclass clazz, jlong ptr, jint tableRef, jobject jkey, jobject jval) {
    (void)clazz;
    lua_State *L = to_lua_state(ptr);
    if (L == NULL || tableRef == -1) return;

    lua_rawgeti(L, LUA_REGISTRYINDEX, tableRef);
    if (!lua_istable(L, -1)) {
        lua_pop(L, 1);
        return;
    }

    push_lua_value(env, L, jkey);
    push_lua_value(env, L, jval);
    lua_settable(L, -3);
    lua_pop(L, 1); // pop table
}

JNIEXPORT jint JNICALL
Java_com_github_anaruto_lua_Globals_nativeTableLength(JNIEnv *env, jclass clazz, jlong ptr, jint tableRef) {
    (void)env;
    (void)clazz;
    lua_State *L = to_lua_state(ptr);
    if (L == NULL || tableRef == -1) return 0;

    lua_rawgeti(L, LUA_REGISTRYINDEX, tableRef);
    if (!lua_istable(L, -1)) {
        lua_pop(L, 1);
        return 0;
    }

    lua_len(L, -1);
    lua_Integer len = lua_tointeger(L, -1);
    lua_pop(L, 2);
    return (jint)len;
}

JNIEXPORT jobjectArray JNICALL
Java_com_github_anaruto_lua_Globals_nativeTableKeys(JNIEnv *env, jclass clazz, jlong ptr, jint tableRef) {
    (void)clazz;
    lua_State *L = to_lua_state(ptr);
    if (L == NULL || tableRef == -1) {
        return (*env)->NewObjectArray(env, 0, g_class_LuaValue, NULL);
    }

    lua_rawgeti(L, LUA_REGISTRYINDEX, tableRef);
    if (!lua_istable(L, -1)) {
        lua_pop(L, 1);
        return (*env)->NewObjectArray(env, 0, g_class_LuaValue, NULL);
    }

    // Count keys first
    int count = 0;
    lua_pushnil(L);
    while (lua_next(L, -2) != 0) {
        count++;
        lua_pop(L, 1); // pop value, keep key
    }

    jobjectArray keys = (*env)->NewObjectArray(env, count, g_class_LuaValue, NULL);
    int idx = 0;
    lua_pushnil(L);
    while (lua_next(L, -2) != 0) {
        jobject k = lua_value_from_stack(env, L, -2, ptr);
        (*env)->SetObjectArrayElement(env, keys, idx++, k);
        (*env)->DeleteLocalRef(env, k);
        lua_pop(L, 1); // pop value, keep key
    }
    lua_pop(L, 1); // pop table
    return keys;
}

JNIEXPORT jint JNICALL
Java_com_github_anaruto_lua_Globals_nativeNewTable(JNIEnv *env, jclass clazz, jlong ptr) {
    (void)env;
    (void)clazz;
    lua_State *L = to_lua_state(ptr);
    if (L == NULL) return -1;
    lua_newtable(L);
    int ref = luaL_ref(L, LUA_REGISTRYINDEX);
    return (jint)ref;
}

JNIEXPORT jobjectArray JNICALL
Java_com_github_anaruto_lua_Globals_nativeDoString(JNIEnv *env, jclass clazz, jlong ptr, jstring jscript) {
    (void)clazz;
    lua_State *L = to_lua_state(ptr);
    if (L == NULL || jscript == NULL) {
        throw_lua_error(env, "Invalid Lua state or null script");
        return NULL;
    }

    const char *script = (*env)->GetStringUTFChars(env, jscript, NULL);
    int base = lua_gettop(L);
    lua_pushcfunction(L, lua_error_traceback_handler);
    int err_handler = base + 1;

    int status = luaL_loadbuffer(L, script, strlen(script), "script");
    (*env)->ReleaseStringUTFChars(env, jscript, script);

    if (status != LUA_OK) {
        const char *err = lua_tostring(L, -1);
        throw_lua_error(env, err ? err : "Compilation error");
        lua_pop(L, 2);
        return NULL;
    }

    status = lua_pcall(L, 0, LUA_MULTRET, err_handler);
    if (status != LUA_OK) {
        const char *err = lua_tostring(L, -1);
        throw_lua_error(env, err ? err : "Execution error");
        lua_pop(L, 2);
        return NULL;
    }

    int nresults = lua_gettop(L) - err_handler;
    jobjectArray res = (*env)->NewObjectArray(env, nresults, g_class_LuaValue, NULL);
    for (int i = 0; i < nresults; i++) {
        jobject v = lua_value_from_stack(env, L, err_handler + 1 + i, ptr);
        (*env)->SetObjectArrayElement(env, res, i, v);
        (*env)->DeleteLocalRef(env, v);
    }
    lua_pop(L, nresults + 1);
    return res;
}

JNIEXPORT jobjectArray JNICALL
Java_com_github_anaruto_lua_Globals_nativeDoFile(JNIEnv *env, jclass clazz, jlong ptr, jstring jpath) {
    (void)clazz;
    lua_State *L = to_lua_state(ptr);
    if (L == NULL || jpath == NULL) {
        throw_lua_error(env, "Invalid Lua state or null file path");
        return NULL;
    }

    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    int base = lua_gettop(L);
    lua_pushcfunction(L, lua_error_traceback_handler);
    int err_handler = base + 1;

    int status = luaL_loadfile(L, path);
    (*env)->ReleaseStringUTFChars(env, jpath, path);

    if (status != LUA_OK) {
        const char *err = lua_tostring(L, -1);
        throw_lua_error(env, err ? err : "Failed to load script file");
        lua_pop(L, 2);
        return NULL;
    }

    status = lua_pcall(L, 0, LUA_MULTRET, err_handler);
    if (status != LUA_OK) {
        const char *err = lua_tostring(L, -1);
        throw_lua_error(env, err ? err : "Execution error");
        lua_pop(L, 2);
        return NULL;
    }

    int nresults = lua_gettop(L) - err_handler;
    jobjectArray res = (*env)->NewObjectArray(env, nresults, g_class_LuaValue, NULL);
    for (int i = 0; i < nresults; i++) {
        jobject v = lua_value_from_stack(env, L, err_handler + 1 + i, ptr);
        (*env)->SetObjectArrayElement(env, res, i, v);
        (*env)->DeleteLocalRef(env, v);
    }
    lua_pop(L, nresults + 1);
    return res;
}

// =========================================================================
// Legacy LuaEngine bindings (Maintained for full backward compatibility)
// =========================================================================

JNIEXPORT jlong JNICALL
Java_com_github_anaruto_lua_LuaEngine_nativeNewState(JNIEnv *env, jclass clazz) {
    return Java_com_github_anaruto_lua_Globals_nativeNewState(env, clazz);
}

JNIEXPORT void JNICALL
Java_com_github_anaruto_lua_LuaEngine_nativeClose(JNIEnv *env, jclass clazz, jlong ptr) {
    Java_com_github_anaruto_lua_Globals_nativeClose(env, clazz, ptr);
}

JNIEXPORT jstring JNICALL
Java_com_github_anaruto_lua_LuaEngine_nativeGetVersion(JNIEnv *env, jclass clazz) {
    (void)clazz;
    return (*env)->NewStringUTF(env, LUA_RELEASE);
}

JNIEXPORT jstring JNICALL
Java_com_github_anaruto_lua_LuaEngine_nativeDoString(JNIEnv *env, jclass clazz, jlong ptr, jstring jscript) {
    (void)clazz;
    lua_State *L = to_lua_state(ptr);
    if (L == NULL || jscript == NULL) {
        return (*env)->NewStringUTF(env, "Invalid Lua state or null script");
    }

    const char *script = (*env)->GetStringUTFChars(env, jscript, NULL);
    int status = luaL_dostring(L, script);
    (*env)->ReleaseStringUTFChars(env, jscript, script);

    if (status != LUA_OK) {
        const char *err = lua_tostring(L, -1);
        jstring jerr = (*env)->NewStringUTF(env, err ? err : "Unknown Lua error");
        lua_pop(L, 1);
        return jerr;
    }
    return NULL;
}

JNIEXPORT jstring JNICALL
Java_com_github_anaruto_lua_LuaEngine_nativeDoFile(JNIEnv *env, jclass clazz, jlong ptr, jstring jpath) {
    (void)clazz;
    lua_State *L = to_lua_state(ptr);
    if (L == NULL || jpath == NULL) {
        return (*env)->NewStringUTF(env, "Invalid Lua state or null path");
    }

    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    int status = luaL_dofile(L, path);
    (*env)->ReleaseStringUTFChars(env, jpath, path);

    if (status != LUA_OK) {
        const char *err = lua_tostring(L, -1);
        jstring jerr = (*env)->NewStringUTF(env, err ? err : "Unknown Lua file error");
        lua_pop(L, 1);
        return jerr;
    }
    return NULL;
}

JNIEXPORT jstring JNICALL
Java_com_github_anaruto_lua_LuaEngine_nativeEval(JNIEnv *env, jclass clazz, jlong ptr, jstring jexpr) {
    (void)clazz;
    lua_State *L = to_lua_state(ptr);
    if (L == NULL || jexpr == NULL) {
        return NULL;
    }

    const char *expr = (*env)->GetStringUTFChars(env, jexpr, NULL);
    size_t len = strlen(expr) + 8;
    char *buf = (char *)malloc(len);
    if (buf == NULL) {
        (*env)->ReleaseStringUTFChars(env, jexpr, expr);
        return NULL;
    }
    snprintf(buf, len, "return %s", expr);

    int status = luaL_dostring(L, buf);
    free(buf);
    (*env)->ReleaseStringUTFChars(env, jexpr, expr);

    if (status != LUA_OK) {
        lua_pop(L, 1);
        return NULL;
    }

    const char *result = lua_tostring(L, -1);
    jstring jres = result ? (*env)->NewStringUTF(env, result) : NULL;
    lua_pop(L, 1);
    return jres;
}

JNIEXPORT void JNICALL
Java_com_github_anaruto_lua_LuaEngine_nativeSetGlobalString(JNIEnv *env, jclass clazz, jlong ptr, jstring jname, jstring jval) {
    (void)clazz;
    lua_State *L = to_lua_state(ptr);
    if (L == NULL || jname == NULL) return;

    const char *name = (*env)->GetStringUTFChars(env, jname, NULL);
    if (jval != NULL) {
        const char *val = (*env)->GetStringUTFChars(env, jval, NULL);
        lua_pushstring(L, val);
        (*env)->ReleaseStringUTFChars(env, jval, val);
    } else {
        lua_pushnil(L);
    }
    lua_setglobal(L, name);
    (*env)->ReleaseStringUTFChars(env, jname, name);
}

JNIEXPORT void JNICALL
Java_com_github_anaruto_lua_LuaEngine_nativeSetGlobalNumber(JNIEnv *env, jclass clazz, jlong ptr, jstring jname, jdouble val) {
    (void)clazz;
    lua_State *L = to_lua_state(ptr);
    if (L == NULL || jname == NULL) return;

    const char *name = (*env)->GetStringUTFChars(env, jname, NULL);
    lua_pushnumber(L, (lua_Number)val);
    lua_setglobal(L, name);
    (*env)->ReleaseStringUTFChars(env, jname, name);
}

JNIEXPORT void JNICALL
Java_com_github_anaruto_lua_LuaEngine_nativeSetGlobalInteger(JNIEnv *env, jclass clazz, jlong ptr, jstring jname, jlong val) {
    (void)clazz;
    lua_State *L = to_lua_state(ptr);
    if (L == NULL || jname == NULL) return;

    const char *name = (*env)->GetStringUTFChars(env, jname, NULL);
    lua_pushinteger(L, (lua_Integer)val);
    lua_setglobal(L, name);
    (*env)->ReleaseStringUTFChars(env, jname, name);
}

JNIEXPORT void JNICALL
Java_com_github_anaruto_lua_LuaEngine_nativeSetGlobalBoolean(JNIEnv *env, jclass clazz, jlong ptr, jstring jname, jboolean val) {
    (void)clazz;
    lua_State *L = to_lua_state(ptr);
    if (L == NULL || jname == NULL) return;

    const char *name = (*env)->GetStringUTFChars(env, jname, NULL);
    lua_pushboolean(L, val ? 1 : 0);
    lua_setglobal(L, name);
    (*env)->ReleaseStringUTFChars(env, jname, name);
}

JNIEXPORT jstring JNICALL
Java_com_github_anaruto_lua_LuaEngine_nativeGetGlobalString(JNIEnv *env, jclass clazz, jlong ptr, jstring jname) {
    (void)clazz;
    lua_State *L = to_lua_state(ptr);
    if (L == NULL || jname == NULL) return NULL;

    const char *name = (*env)->GetStringUTFChars(env, jname, NULL);
    lua_getglobal(L, name);
    (*env)->ReleaseStringUTFChars(env, jname, name);

    const char *str = lua_tostring(L, -1);
    jstring res = str ? (*env)->NewStringUTF(env, str) : NULL;
    lua_pop(L, 1);
    return res;
}

JNIEXPORT jdouble JNICALL
Java_com_github_anaruto_lua_LuaEngine_nativeGetGlobalNumber(JNIEnv *env, jclass clazz, jlong ptr, jstring jname) {
    (void)clazz;
    lua_State *L = to_lua_state(ptr);
    if (L == NULL || jname == NULL) return 0.0;

    const char *name = (*env)->GetStringUTFChars(env, jname, NULL);
    lua_getglobal(L, name);
    (*env)->ReleaseStringUTFChars(env, jname, name);

    lua_Number num = lua_tonumber(L, -1);
    lua_pop(L, 1);
    return (jdouble)num;
}

JNIEXPORT jlong JNICALL
Java_com_github_anaruto_lua_LuaEngine_nativeGetGlobalInteger(JNIEnv *env, jclass clazz, jlong ptr, jstring jname) {
    (void)clazz;
    lua_State *L = to_lua_state(ptr);
    if (L == NULL || jname == NULL) return 0;

    const char *name = (*env)->GetStringUTFChars(env, jname, NULL);
    lua_getglobal(L, name);
    (*env)->ReleaseStringUTFChars(env, jname, name);

    lua_Integer num = lua_tointeger(L, -1);
    lua_pop(L, 1);
    return (jlong)num;
}

JNIEXPORT jboolean JNICALL
Java_com_github_anaruto_lua_LuaEngine_nativeGetGlobalBoolean(JNIEnv *env, jclass clazz, jlong ptr, jstring jname) {
    (void)clazz;
    lua_State *L = to_lua_state(ptr);
    if (L == NULL || jname == NULL) return JNI_FALSE;

    const char *name = (*env)->GetStringUTFChars(env, jname, NULL);
    lua_getglobal(L, name);
    (*env)->ReleaseStringUTFChars(env, jname, name);

    int b = lua_toboolean(L, -1);
    lua_pop(L, 1);
    return b ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_github_anaruto_lua_LuaEngine_nativePushNil(JNIEnv *env, jclass clazz, jlong ptr) {
    (void)env;
    (void)clazz;
    lua_State *L = to_lua_state(ptr);
    if (L != NULL) lua_pushnil(L);
}

JNIEXPORT void JNICALL
Java_com_github_anaruto_lua_LuaEngine_nativePushBoolean(JNIEnv *env, jclass clazz, jlong ptr, jboolean val) {
    (void)env;
    (void)clazz;
    lua_State *L = to_lua_state(ptr);
    if (L != NULL) lua_pushboolean(L, val ? 1 : 0);
}

JNIEXPORT void JNICALL
Java_com_github_anaruto_lua_LuaEngine_nativePushInteger(JNIEnv *env, jclass clazz, jlong ptr, jlong val) {
    (void)env;
    (void)clazz;
    lua_State *L = to_lua_state(ptr);
    if (L != NULL) lua_pushinteger(L, (lua_Integer)val);
}

JNIEXPORT void JNICALL
Java_com_github_anaruto_lua_LuaEngine_nativePushNumber(JNIEnv *env, jclass clazz, jlong ptr, jdouble val) {
    (void)env;
    (void)clazz;
    lua_State *L = to_lua_state(ptr);
    if (L != NULL) lua_pushnumber(L, (lua_Number)val);
}

JNIEXPORT void JNICALL
Java_com_github_anaruto_lua_LuaEngine_nativePushString(JNIEnv *env, jclass clazz, jlong ptr, jstring jstr) {
    (void)clazz;
    lua_State *L = to_lua_state(ptr);
    if (L == NULL) return;
    if (jstr != NULL) {
        const char *str = (*env)->GetStringUTFChars(env, jstr, NULL);
        lua_pushstring(L, str);
        (*env)->ReleaseStringUTFChars(env, jstr, str);
    } else {
        lua_pushnil(L);
    }
}

JNIEXPORT jstring JNICALL
Java_com_github_anaruto_lua_LuaEngine_nativeToString(JNIEnv *env, jclass clazz, jlong ptr, jint index) {
    (void)clazz;
    lua_State *L = to_lua_state(ptr);
    if (L == NULL) return NULL;
    const char *str = lua_tostring(L, index);
    return str ? (*env)->NewStringUTF(env, str) : NULL;
}

JNIEXPORT jdouble JNICALL
Java_com_github_anaruto_lua_LuaEngine_nativeToNumber(JNIEnv *env, jclass clazz, jlong ptr, jint index) {
    (void)env;
    (void)clazz;
    lua_State *L = to_lua_state(ptr);
    return L ? (jdouble)lua_tonumber(L, index) : 0.0;
}

JNIEXPORT jlong JNICALL
Java_com_github_anaruto_lua_LuaEngine_nativeToInteger(JNIEnv *env, jclass clazz, jlong ptr, jint index) {
    (void)env;
    (void)clazz;
    lua_State *L = to_lua_state(ptr);
    return L ? (jlong)lua_tointeger(L, index) : 0;
}

JNIEXPORT jboolean JNICALL
Java_com_github_anaruto_lua_LuaEngine_nativeToBoolean(JNIEnv *env, jclass clazz, jlong ptr, jint index) {
    (void)env;
    (void)clazz;
    lua_State *L = to_lua_state(ptr);
    return (L && lua_toboolean(L, index)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_github_anaruto_lua_LuaEngine_nativeGetTop(JNIEnv *env, jclass clazz, jlong ptr) {
    (void)env;
    (void)clazz;
    lua_State *L = to_lua_state(ptr);
    return L ? (jint)lua_gettop(L) : 0;
}

JNIEXPORT void JNICALL
Java_com_github_anaruto_lua_LuaEngine_nativePop(JNIEnv *env, jclass clazz, jlong ptr, jint n) {
    (void)env;
    (void)clazz;
    lua_State *L = to_lua_state(ptr);
    if (L != NULL) lua_pop(L, n);
}

JNIEXPORT jstring JNICALL
Java_com_github_anaruto_lua_LuaEngine_nativePCall(JNIEnv *env, jclass clazz, jlong ptr, jint nArgs, jint nResults) {
    (void)clazz;
    lua_State *L = to_lua_state(ptr);
    if (L == NULL) {
        return (*env)->NewStringUTF(env, "Invalid Lua state");
    }
    int status = lua_pcall(L, nArgs, nResults, 0);
    if (status != LUA_OK) {
        const char *err = lua_tostring(L, -1);
        jstring jerr = (*env)->NewStringUTF(env, err ? err : "Lua pcall execution error");
        lua_pop(L, 1);
        return jerr;
    }
    return NULL;
}
