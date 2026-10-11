# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile


# FreeReflection
-keep class me.weishu.reflection.** {*;}

# Xposed 入口类名必须与 assets/xposed_init 保持一致，仅保护类名与无参构造。
-keep class a0.a0.a0.a0.a0.a0.Entrance {
    <init>();
}

-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    public static *** throwUninitializedProperty(...);
    public static *** throwUninitializedPropertyAccessException(...);
}

-dontwarn java.lang.reflect.AnnotatedType

# libterm / jsch 在 Android 上不会走到的桌面侧可选依赖。
# 这些类来自 Pageant、Log4j2、JGSS 等 JVM/Windows 能力，release 混淆时仅需忽略缺失告警。
# TODO remvoe
-dontwarn com.sun.jna.Memory
-dontwarn com.sun.jna.Pointer
-dontwarn com.sun.jna.platform.win32.BaseTSD$ULONG_PTR
-dontwarn com.sun.jna.platform.win32.Kernel32
-dontwarn com.sun.jna.platform.win32.User32
-dontwarn com.sun.jna.platform.win32.WinBase$SECURITY_ATTRIBUTES
-dontwarn com.sun.jna.platform.win32.WinBase
-dontwarn com.sun.jna.platform.win32.WinDef$HWND
-dontwarn com.sun.jna.platform.win32.WinDef$LPARAM
-dontwarn com.sun.jna.platform.win32.WinDef$LRESULT
-dontwarn com.sun.jna.platform.win32.WinDef$WPARAM
-dontwarn com.sun.jna.platform.win32.WinNT$HANDLE
-dontwarn com.sun.jna.platform.win32.WinUser$COPYDATASTRUCT
-dontwarn org.apache.logging.log4j.Level
-dontwarn org.apache.logging.log4j.LogManager
-dontwarn org.apache.logging.log4j.Logger
-dontwarn org.ietf.jgss.GSSContext
-dontwarn org.ietf.jgss.GSSCredential
-dontwarn org.ietf.jgss.GSSException
-dontwarn org.ietf.jgss.GSSManager
-dontwarn org.ietf.jgss.GSSName
-dontwarn org.ietf.jgss.MessageProp
-dontwarn org.ietf.jgss.Oid

# ktor JVM 调试检测器（IntellijIdeaDebugDetector）读取 java.lang.management，Android 上不存在。
# 它内部 catch(Throwable)，NoClassDefFoundError 会退化为 false，仅需忽略缺失告警。
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean

# =================== 必要的框架和库规则 ===================

# Xposed Framework (保留核心接口即可)
#-keep interface de.robv.android.xposed.IXposedHookLoadPackage { *; }
#-keep interface de.robv.android.xposed.IXposedHookZygoteInit { *; }
#-keep interface de.robv.android.xposed.IXposedHookInitPackageResources { *; }

# Kotlin Coroutines
# 推荐由 AGP 和 R8 的默认规则处理，但如果遇到问题，以下是安全的
#-dontwarn kotlinx.coroutines.flow.**
#-dontwarn kotlinx.coroutines.internal.**
#-dontwarn kotlinx.coroutines.debug.**

# Kotlin 反射和元数据 (必要)
-keep class kotlin.Metadata { *; }
-keep class kotlin.coroutines.Continuation
-keep class kotlin.jvm.internal.DefaultConstructorMarker { *; }
# 如果协程出问题，可以加上这条作为保险
# -keep class * extends kotlin.coroutines.Continuation { *; }


# =================== 标准 Android 规则 (必要) ===================

# 保留 JNI 方法
-keepclasseswithmembernames class * {
    native <methods>;
}

# 保留自定义 View 的构造函数
#-keep public class * extends android.view.View {
#    public <init>(android.content.Context);
#    public <init>(android.content.Context, android.util.AttributeSet);
#    public <init>(android.content.Context, android.util.AttributeSet, int);
#}

# 保留 Parcelable 的 CREATOR 字段
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}

# 保留 Serializable 的特殊方法和字段
-keep class * implements java.io.Serializable {
    static final long serialVersionUID;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}
