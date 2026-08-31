# Rules for release builds.

# Retrofit
-keepattributes Signature
-keepattributes Exceptions
-keepattributes *Annotation*
-keep class retrofit2.** { *; }
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}
-dontwarn retrofit2.**
-dontwarn javax.annotation.**

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keep class * extends com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Keep data classes for Gson serialization
-keep class hd.kinoshka.app.data.model.** { *; }
-keep class hd.kinoshka.app.data.local.** { *; }
-keep class app.marlboroadvance.mpvex.preferences.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# Koin
-keep class org.koin.** { *; }
-keep class * extends org.koin.core.module.Module

# Coil
-dontwarn coil.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# Kotlin Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class hd.kinoshka.app.**$$serializer { *; }
-keepclassmembers class hd.kinoshka.app.** {
    *** Companion;
}
-keepclasseswithmembers class hd.kinoshka.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# MPV
-keep class is.xyz.mpv.** { *; }
-dontwarn is.xyz.mpv.**

# Java EL is an optional dependency of MBassador used by SMBJ and is not
# available on Android. The EL-backed filter path is not used by the app.
-dontwarn javax.el.**

# Media3 / ExoPlayer
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# SMBJ / Sardine / Commons Net: без keep — R8 оставляет только
# реально достижимые классы сетевых клиентов браузера mpvEx.
# (org.nanohttpd-правило было no-op: реальный пакет nanohttpd — fi.iki.elonen,
# и он целиком вырезан как недостижимый.)
-dontwarn com.hierynomus.smbj.**
-dontwarn com.github.sardine.**
-dontwarn org.apache.commons.net.**

# Keep BuildConfig
-keep class hd.kinoshka.app.BuildConfig { *; }

# AndroidX Security
-keep class androidx.security.crypto.** { *; }
-dontwarn androidx.security.crypto.**

# Keep R class
-keepclassmembers class **.R$* {
    public static <fields>;
}

# Prevent stripping of enum values
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep Parcelable
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# Keep Serializable
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}
