# BlackBox 核心通过反射与 Binder 与虚拟进程通信，不能被混淆/裁剪
-keep class top.niunaijun.blackbox.** { *; }
-keep class com.anrays.yplus.** { *; }
-dontwarn top.niunaijun.blackbox.**

# aidl 生成的接口
-keep interface android.**.I* { *; }

# ===== black-reflection（BlackBox 的反射引擎）=====
# BlackBox 靠**运行时读注解**去反射系统私有 API：代理接口上标 @BClass / @BClassName /
# @BMethod / @BField …，BlackReflection.getClassNameByBlackClass() 运行时 getAnnotation 取出来。
# 🔴 两样都不能丢，否则一开就崩：
#   · -keepattributes *Annotation* —— 没有它 R8 会抹掉注解元数据，getAnnotation 返回 null，
#     启动即崩：RuntimeException: Not found @BlackClass or @BlackStrClass
#   · 包本身要 keep —— 上面只 keep 了 top.niunaijun.blackbox.**，而它的包名是
#     **blackreflection**（不是 blackbox），发布版实测一开就崩（2026-10-29）。
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-keep class top.niunaijun.blackreflection.** { *; }
-keep interface top.niunaijun.blackreflection.** { *; }
-dontwarn top.niunaijun.blackreflection.**

# 被注解标记的代理接口：注解和方法签名要一起留下来
-keepclassmembers class * {
    @top.niunaijun.blackreflection.annotation.* <methods>;
    @top.niunaijun.blackreflection.annotation.* <fields>;
}

# ===== black.** —— BlackBox 的「系统类镜像」（300+ 个）=====
# 这些 mirror 类的包名前缀就是 **black.**（black.java.io.File、black.com.android.internal.*、
# black.libcore.io.Os …），**不在 top.niunaijun.blackbox.** 下面 —— 漏了这条它们全被 R8 处理掉，
# 运行时读不到注解 → 发布版一开就崩 "Not found @BlackClass or @BlackStrClass"（2026-10-29 实测）。
-keep class black.** { *; }
-keep interface black.** { *; }
-dontwarn black.**
