# Shizuku 核心规则
-keep class rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**

-keepclassmembers class rikka.shizuku.Shizuku {
    private static java.lang.Process newProcess(java.lang.String[], java.lang.String[], java.lang.String);
}

# AIDL 接口与存根
-keep interface com.niki914.shizuku.display.IShizukuVirtualDisplayService { *; }
-keep class com.niki914.shizuku.display.IShizukuVirtualDisplayService$* { *; }
-keep class com.niki914.shizuku.display.ShizukuVirtualDisplayService { *; }
