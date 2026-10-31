# ===== 原生引擎（Vulkan / JNI）=====
# SpektraLabEngine 的 native 方法以 C 端静态符号查找，名字不能被混淆
-keep class app.spektralab.engine.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# JNI 回调 / 序列化实体（引擎参数走 JSON 字符串，但保留内部类名便于排查）
-keepclassmembers class app.spektralab.engine.** {
    <fields>;
    <methods>;
}

# ===== Kotlin =====
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**

# ===== AndroidX / 三方（缺省警告收敛）=====
-dontwarn androidx.**
-dontwarn org.jetbrains.annotations.**

# 保留行号，崩溃栈可读
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# 去掉调试日志（release 下 Log.d/v 仍会被调用但字符串常量可被裁剪）
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
