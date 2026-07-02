# Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.cursormobile.**$$serializer { *; }
-keepclassmembers class com.cursormobile.** {
    *** Companion;
}
-keepclasseswithmembers class com.cursormobile.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Ktor
-keepclassmembers class io.ktor.** { *; }
-dontwarn io.netty.**
-dontwarn org.slf4j.**

# Hilt
-keep class dagger.hilt.** { *; }
