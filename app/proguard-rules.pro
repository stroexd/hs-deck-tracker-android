# kotlinx.serialization – eigene @Serializable-Klassen behalten
-keepattributes *Annotation*, InnerClasses, Signature
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class com.stroexd.hsdecktracker.**$$serializer { *; }
-keepclassmembers class com.stroexd.hsdecktracker.** {
    *** Companion;
}
-keepclasseswithmembers class com.stroexd.hsdecktracker.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers enum com.stroexd.hsdecktracker.** { *; }

# OkHttp optionale Plattformen
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
