# Keep kotlinx.serialization generated serializers for our model classes.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class com.youhao.fueltrack.** {
    *** Companion;
}
-keepclasseswithmembers class com.youhao.fueltrack.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# androidx.security:security-crypto pulls in Tink, whose bytecode carries Error Prone annotations.
# They are compile-time only and are absent at runtime, so R8 only needs to stop warning about
# them — keeping anything here would just bloat the APK.
-dontwarn com.google.errorprone.annotations.**
