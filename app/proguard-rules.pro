# MediaPipe GenAI ships native code reached via JNI; keep its entry points.
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**

# kotlinx.serialization generates serializers reflectively referenced by name.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.ghost.agent.** {
    *** Companion;
}
-keepclasseswithmembers class com.ghost.agent.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# The accessibility service is instantiated by the OS from the manifest name.
-keep class com.ghost.agent.service.GhostAccessibilityService { *; }
