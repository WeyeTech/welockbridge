# WeLockBridge BLE SDK - ProGuard Rules
# These rules are applied when building the SDK in release mode

# =====================================================
# KEEP PUBLIC API
# =====================================================

# Keep all public classes and interfaces
-keep public class com.welockbridge.sdk.WeLockBridgeSdk { *; }
-keep public class com.welockbridge.sdk.WeLockBridgeSdk$* { *; }

# Keep core types and interfaces
-keep public class com.welockbridge.sdk.core.** { *; }
-keep public interface com.welockbridge.sdk.core.** { *; }
-keep public enum com.welockbridge.sdk.core.** { *; }

# =====================================================
# OBFUSCATE INTERNAL IMPLEMENTATION
# =====================================================

# Obfuscate internal package
-keeppackagenames !com.welockbridge.sdk.internal.**
-repackageclasses 'com.welockbridge.sdk.internal'

# =====================================================
# REMOVE DEBUG LOGGING IN RELEASE
# =====================================================

-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# =====================================================
# KOTLIN COROUTINES
# =====================================================

-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# =====================================================
# KOTLIN SPECIFICS
# =====================================================

-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlin.Metadata {
    public <methods>;
}

# Keep data class component functions
-keepclassmembers class com.welockbridge.sdk.core.** {
    public ** component*();
    public ** copy(...);
}

# =====================================================
# ANDROID BLUETOOTH
# =====================================================

-keep class android.bluetooth.** { *; }

# =====================================================
# CRYPTO (AES)
# =====================================================

-keep class javax.crypto.** { *; }
-keep class javax.crypto.spec.** { *; }
