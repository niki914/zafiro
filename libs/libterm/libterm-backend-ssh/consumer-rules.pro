# jsch (mwiede fork): cipher/mac/kex/signature/keypair 实现类全部通过配置字符串反射加载
-keep class com.jcraft.jsch.** { *; }

# 可选 runtime 依赖（未引入时避免 R8 fullMode 因缺类报错失败）
-dontwarn org.bouncycastle.**
-dontwarn org.slf4j.**
-dontwarn org.newsclub.**
-dontwarn javax.**
-dontwarn com.sun.jna.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.ietf.jgss.**
