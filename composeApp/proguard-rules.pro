# R8 keep rules for the release build. Libraries ship consumer rules where they can;
# these cover what they do not: JNI, AIDL/Binder, serialization lookups and PDFBox reflection.

# Attributes needed by kotlinx.serialization and readable crash reports.
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod, RuntimeVisibleAnnotations
-keepattributes SourceFile, LineNumberTable

# kotlinx.serialization: generated serializers are found through Companion.serializer() and $$serializer.
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class **$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class app.vezuna.**$$serializer { *; }
-dontnote kotlinx.serialization.**

# Llamatik / llama.cpp: native code resolves Java methods and fields by name.
-keep class com.llamatik.** { *; }
-keepclasseswithmembernames class * { native <methods>; }

# Play licensing (LVL): AIDL stubs and the vendored client are bound by name over Binder.
-keep class com.android.vending.licensing.** { *; }
-keep class app.vezuna.license.** { *; }

# PDFBox-Android loads fonts, filters and glyph lists reflectively and optionally references JP2/AWT classes.
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-dontwarn com.gemalto.jp2.**
-dontwarn java.awt.**
-dontwarn javax.imageio.**
-dontwarn org.apache.commons.logging.**

# Room (pulled in by WorkManager via ML Kit): generated *_Impl classes are created reflectively through their no-arg constructor.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class androidx.work.impl.WorkDatabase_Impl { <init>(); }

# ML Kit and Firebase-style component discovery instantiates registrars reflectively by manifest name.
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }
-keep class com.google.mlkit.** { <init>(); }
-keep class * extends androidx.startup.Initializer { <init>(); }
-keep class androidx.work.** { <init>(); }
