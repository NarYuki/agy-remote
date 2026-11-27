# R8 rules for the release build. Libraries that ship consumer rules (CameraX, Compose,
# coroutines) bring their own; the entries below are the ones R8 can't infer.

# --- JNI (P2P/agy-p2p-jni) ---
# libagy_p2p_jni.so exports Java_moe_n4tsu_agyremote_net_NativeP2P_*, so the class name and the native
# method names must survive. Rust calls Listener.onEvent(String) by name through env.call_method.
-keepclasseswithmembernames,includedescriptorclasses class moe.n4tsu.agyremote.net.NativeP2P {
    native <methods>;
}
-keep class moe.n4tsu.agyremote.net.NativeP2P { *; }
-keep interface moe.n4tsu.agyremote.net.NativeP2P$Listener { *; }
-keep class * implements moe.n4tsu.agyremote.net.NativeP2P$Listener {
    public void onEvent(java.lang.String);
}

# --- CameraX ---
# Camera2Config.DefaultProvider is looked up by name from the manifest metadata.
-keep class androidx.camera.camera2.Camera2Config$DefaultProvider { *; }
-keep class * implements androidx.camera.core.CameraXConfig$Provider { *; }

# --- Backdrop / Liquid Glass (io.github.kyant0:backdrop, :shapes) ---
# No reflection, but keep it intact so AGSL shader effects can't be stripped or merged.
-keep class com.kyant.backdrop.** { *; }
-keep class com.kyant.shapes.** { *; }
-dontwarn com.kyant.**

# kotlinx.serialization isn't used (JSON goes through org.json), so no rules for it.

# Keep line numbers for readable Play Console crash reports (upload mapping.txt with each release).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# zxing-cpp: native code calls back into these classes by name.
-keep class zxingcpp.** { *; }
