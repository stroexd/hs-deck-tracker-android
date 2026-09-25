# kotlinx.serialization: keep our @Serializable classes
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

# OkHttp optional platforms
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
