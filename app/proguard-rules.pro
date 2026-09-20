# kotlinx.serialization: keep @Serializable metadata and generated serializers.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class com.lpecom.gemglasses.gemini.protocol.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.lpecom.gemglasses.**$$serializer { *; }

# OkHttp / Okio
-dontwarn okhttp3.**
-dontwarn okio.**

# The real Meta DAT backend is loaded reflectively — keep it when present.
-keep class com.lpecom.gemglasses.glasses.real.RealGlassesBackend { *; }

# --- META DAT SDK & KOTLIN COROUTINES ---
# Prevent R8 from stripping Kotlin coroutine internals (fixes SpillingKt crash)
-keep class kotlin.coroutines.jvm.internal.** { *; }

# Keep Meta Wearables DAT SDK classes and suppress missing warnings
-keep class com.meta.wearable.** { *; }
-dontwarn com.meta.wearable.**
-dontwarn com.facebook.soloader.**
