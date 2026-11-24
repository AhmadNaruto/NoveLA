# Preserve Lua Native JNI bindings and data classes
-keep class com.github.anaruto.lua.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
