# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# disable obfuscation
-dontobfuscate

# Keep JNI interface
-keep class com.osfans.trime.core.* { *; }
-keep class com.k2fsa.sherpa.onnx.* { *; }

# remove kotlin null checks
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    static void checkNotNull(...);
    static void checkExpressionValueIsNotNull(...);
    static void checkNotNullExpressionValue(...);
    static void checkReturnedValueIsNotNull(...);
    static void checkFieldIsNotNull(...);
    static void checkParameterIsNotNull(...);
    static void checkNotNullParameter(...);
}

# Uncomment this to preserve the line number information for
# debugging stack traces.
-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Suppress R8 warnings for joni (Oniguruma regex engine used by TextMate)
-dontwarn org.joni.**
-keep class org.joni.** { *; }

# Suppress type-check warnings for eclpse TM4E
-dontwarn org.eclipse.tm4e.**
-keep class org.eclipse.tm4e.** { *; }

-dontwarn io.github.rosemoe.sora.langs.textmate.**
-keep class io.github.rosemoe.sora.langs.textmate.** { *; }
# 苍竹增强（回退 + AI 模式）：整包保留，避免 R8 内联掉
# SecretStore / RevertLedger / BambooActions 这些类被 inlining 后
# 在 dex 里就查不到类名了，出问题（尤其是 Keystore 相关）不好排查。
-keep class com.osfans.trime.actions.bamboo.** { *; }
# 手写：native 方法由 C++ 按名字绑定（Java_com_osfans_trime_actions_handwriting_HandwritingNative_*），
# 整包保留，防止 R8 把暂时没被调用的 nativeRelease 之类裁掉。
-keep class com.osfans.trime.actions.handwriting.** { *; }
-keepclasseswithmembernames class com.osfans.trime.actions.handwriting.HandwritingNative { native <methods>; }