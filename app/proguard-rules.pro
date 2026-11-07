# ---------------------------------------------------------------------------------------------
# Yomu release rules. Most libraries (Room, WorkManager, OkHttp, Coil, ML Kit, kotlinx-serialization)
# ship their own consumer rules; only app specific rules live here.
# ---------------------------------------------------------------------------------------------

-keepattributes SourceFile,LineNumberTable,Signature,InnerClasses,EnclosingMethod,*Annotation*
-renamesourcefileattribute SourceFile

# Type-safe navigation routes and other @Serializable app classes.
-keep @kotlinx.serialization.Serializable class app.yomu.** { *; }
-keepclassmembers class app.yomu.** {
    *** Companion;
}
-keep class app.yomu.navigation.** { *; }

# WebView bridges (EPUB reader, visual editor, catalog web view).
-keepattributes JavascriptInterface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# ---------------------------------------------------------------------------------------------
# Extension API. Extensions are separate APKs compiled against source-api and loaded with a
# class loader whose parent is the app, so the API and the libraries it exposes must keep their
# public names (same approach as Mihon).
# ---------------------------------------------------------------------------------------------
-keep class app.yomu.source.** { *; }
-keep interface app.yomu.source.** { *; }
-keep,allowoptimization class kotlin.** { public protected *; }
-keep,allowoptimization class kotlinx.coroutines.** { public protected *; }
-keep,allowoptimization class kotlinx.serialization.** { public protected *; }
-keep,allowoptimization class okhttp3.** { public protected *; }
-keep,allowoptimization class okio.** { public protected *; }
-keep,allowoptimization class org.jsoup.** { public protected *; }

# Optional dependencies referenced by libraries but not present on Android.
-dontwarn org.jspecify.annotations.**
-dontwarn org.slf4j.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**
-dontwarn re2j.**
# Optional Apache Commons Compress codecs that are not bundled (zstd, brotli).
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**
