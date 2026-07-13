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
