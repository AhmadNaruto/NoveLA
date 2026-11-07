-keep class com.seanproctor.lua.LuaJni {
    native <methods>;
    static int nativeCallback(long, long, int);
}
-keep class com.seanproctor.lua.internal.HandlerRegistry { *; }
-keep class com.seanproctor.lua.internal.LeakTracker { *; }
