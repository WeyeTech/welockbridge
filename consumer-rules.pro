# WeLockBridge BLE SDK - Consumer Rules
# These rules are automatically applied to apps that use this SDK

# Keep public API
-keep public class com.welockbridge.sdk.WeLockBridgeSdk { *; }
-keep public class com.welockbridge.sdk.WeLockBridgeSdk$* { *; }
-keep public class com.welockbridge.sdk.core.** { *; }
-keep public interface com.welockbridge.sdk.core.** { *; }
-keep public enum com.welockbridge.sdk.core.** { *; }

# Keep data class functions
-keepclassmembers class com.welockbridge.sdk.core.** {
    public ** component*();
    public ** copy(...);
}

# Keep Flow and coroutines
-keepnames class kotlinx.coroutines.flow.** { *; }
