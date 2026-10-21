-dontwarn io.github.libxposed.annotation.**
-dontwarn io.github.libxposed.api.**

# Parent-first module loaders must not resolve short names from the host/runtime.
-repackageclasses com.xiyunmn.qdhelper.obfuscated

-adaptresourcefilecontents META-INF/xposed/java_init.list

-keep class com.xiyunmn.qdhelper.entry.QDHelperModule {
    public <init>();
}
