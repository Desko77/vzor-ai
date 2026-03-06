# === Vzor AI ProGuard Rules ===

# --- Retrofit API interfaces + Moshi DTOs (data.remote) ---
# Keep Retrofit service interfaces (method annotations needed at runtime)
-keep,allowobfuscation interface com.vzor.ai.data.remote.*Service { *; }
-keep,allowobfuscation interface com.vzor.ai.data.remote.*Api { *; }
# Keep Moshi data classes (JSON serialization requires field names)
-keepclassmembers class com.vzor.ai.data.remote.** {
    <init>(...);
    <fields>;
}
-keep class com.vzor.ai.data.remote.**Request { *; }
-keep class com.vzor.ai.data.remote.**Response { *; }
-keep class com.vzor.ai.data.remote.**Message { *; }
-keep class com.vzor.ai.data.remote.**Chunk { *; }
-keep class com.vzor.ai.data.remote.**Result { *; }
-keep @com.squareup.moshi.JsonClass class * { *; }
-keepclassmembers class com.vzor.ai.** {
    @com.squareup.moshi.Json <fields>;
}
-keepclassmembers class * {
    @com.squareup.moshi.FromJson <methods>;
    @com.squareup.moshi.ToJson <methods>;
}
-keep class com.squareup.moshi.** { *; }
-keep interface com.squareup.moshi.** { *; }
-dontwarn com.squareup.moshi.**

# --- Room ---
-keep class com.vzor.ai.data.local.** { *; }
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-keep @androidx.room.Database class * { *; }

# --- Hilt / Dagger ---
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }
-dontwarn dagger.hilt.internal.**

# --- Retrofit ---
-keep,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }

# --- OkHttp ---
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-keep class okhttp3.internal.platform.** { *; }

# --- Kotlin Coroutines ---
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.flow.**

# --- Kotlin Reflection ---
-keep class kotlin.Metadata { *; }
-keep class kotlin.reflect.** { *; }
-dontwarn kotlin.reflect.jvm.internal.**

# --- Google Generative AI (Gemini) ---
-keep class com.google.ai.client.generativeai.** { *; }
-dontwarn com.google.ai.client.generativeai.**

# --- AndroidX Security Crypto (EncryptedSharedPreferences) ---
-keep class androidx.security.crypto.** { *; }
-dontwarn androidx.security.crypto.**

# --- SQLCipher ---
-keep class net.zetetic.database.** { *; }
-dontwarn net.zetetic.database.**
-keep class net.sqlcipher.** { *; }
-dontwarn net.sqlcipher.**

# --- MediaPipe ---
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**

# --- ML Kit ---
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# --- Meta Wearables DAT SDK ---
-keep class com.meta.wearable.** { *; }
-dontwarn com.meta.wearable.**

# --- Vzor domain models ---
-keep class com.vzor.ai.domain.model.** { *; }
