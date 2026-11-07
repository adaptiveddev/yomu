# Tachiyomi/Mihon extensions are loaded at runtime and link against these classes by name:
# keep the host API and the public API of every library the extensions expect the host to provide.

# Injekt reads the injected type from the generic superclass of its type tokens
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*
-keep,allowshrinking class * extends uy.kohesive.injekt.api.FullTypeReference

# Keep EVERY member of the host API, private ones included: extensions built on the Keiyoushi
# multisrc libs (KeiSource, lib 1.6+) patch private fields through reflection, e.g.
#   HttpSource::class.java.getDeclaredField("headers$delegate")
# and check interceptor classes by simple name. With `public protected *` R8 renamed the private
# `headers$delegate` field and such extensions failed with NoSuchFieldException (Yomu 1.1.0).
-keep class eu.kanade.tachiyomi.** { *; }
-keep class eu.kanade.tachiyomi.**$* { *; }
-keepnames class eu.kanade.tachiyomi.** { *; }

-keep,allowoptimization class androidx.preference.** { public protected *; }
-keep,allowoptimization class kotlin.** { public protected *; }
-keep,allowoptimization class kotlinx.coroutines.** { public protected *; }
-keep,allowoptimization class kotlinx.serialization.** { public protected *; }
-keep,allowoptimization class okhttp3.** { public protected *; }
-keep,allowoptimization class okio.** { public protected *; }
-keep,allowoptimization class org.jsoup.** { public protected *; }
-keep,allowoptimization class rx.** { public protected *; }
-keep,allowoptimization class app.cash.quickjs.** { public protected *; }
-keep,allowoptimization class uy.kohesive.injekt.** { public protected *; }
-keep,allowoptimization class com.squareup.zstd.** { public protected *; }

-keepclassmembers class * implements java.io.Serializable {
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# RxJava 1.x
-dontwarn sun.misc.**
-keepclassmembers class rx.internal.util.unsafe.*ArrayQueue*Field* {
   long producerIndex;
   long consumerIndex;
}
-keepclassmembers class rx.internal.util.unsafe.BaseLinkedQueueProducerNodeRef {
    rx.internal.util.atomic.LinkedQueueNode producerNode;
}
-keepclassmembers class rx.internal.util.unsafe.BaseLinkedQueueConsumerNodeRef {
    rx.internal.util.atomic.LinkedQueueNode consumerNode;
}
-dontnote rx.internal.util.PlatformDependent

# kotlinx.serialization used by extensions (serializers are looked up through Companion / serializer())
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep class kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.** { <methods>; }

# QuickJS native bridge
-keep class app.cash.quickjs.** { *; }
-dontwarn org.jspecify.annotations.**
