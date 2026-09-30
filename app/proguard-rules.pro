# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.xieguojian.aiseclab.**$$serializer { *; }
-keepclassmembers class com.xieguojian.aiseclab.** { *** Companion; }
-keepclasseswithmembers class com.xieguojian.aiseclab.** { kotlinx.serialization.KSerializer serializer(...); }
