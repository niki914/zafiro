# UserService 实现类被 Shizuku 远端进程按类名反射加载，必须保类名 + 构造函数
-keep class com.niki914.libterm.backend.shizuku.internal.LibTermShizukuShellUserService { *; }

# AIDL 生成的接口与 Stub（跨进程契约，签名不可改）
-keep interface com.niki914.libterm.backend.shizuku.ILibTermShizukuShellService { *; }
-keep class com.niki914.libterm.backend.shizuku.ILibTermShizukuShellService$* { *; }
-keep interface com.niki914.libterm.backend.shizuku.ILibTermShizukuShellCallback { *; }
-keep class com.niki914.libterm.backend.shizuku.ILibTermShizukuShellCallback$* { *; }

# Shizuku 库自身（api/provider，含其内部 AIDL 与反射）
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
-dontwarn rikka.shizuku.**
-dontwarn moe.shizuku.**
