# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in C:\Users\...\AppData\Local\Android\sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.

# Keep Shizuku classes
-keep class rikka.shizuku.** { *; }

# Keep ContinuityDbBridge
-keep class com.sergeron.mixflipcontinuity.ContinuityDbBridge {
    public static void main(java.lang.String[]);
}
