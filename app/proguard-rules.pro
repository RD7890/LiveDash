-keep class com.rohan.livedash.** { *; }

# ZXing / QR code scanner
-keep class com.google.zxing.** { *; }
-keep class com.journeyapps.** { *; }

# Java-WebSocket
-keep class org.java_websocket.** { *; }

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Kotlin serialization / reflection
-dontwarn kotlin.reflect.**
-keep class kotlin.Metadata { *; }

# DataStore
-keep class androidx.datastore.** { *; }

-dontwarn java.lang.invoke.StringConcatFactory
