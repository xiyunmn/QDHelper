-dontwarn io.github.libxposed.annotation.**
-dontwarn io.github.libxposed.api.**

-adaptresourcefilecontents META-INF/xposed/java_init.list

-keep class com.xiyunmn.qdhelper.entry.QDHelperModule {
    public <init>();
}
