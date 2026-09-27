# ShizukuHandler 反射调用 Shizuku.newProcess：方法名不能被混淆改名
-keepclassmembers class rikka.shizuku.Shizuku {
    private static java.lang.Process newProcess(java.lang.String[], java.lang.String[], java.lang.String);
}
