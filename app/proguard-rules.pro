# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles input property for a given build variant.

# Keep location service
-keep class com.virtualrun.app.service.** { *; }

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Google Maps
-keep class com.google.android.gms.** { *; }
-keep class com.google.maps.** { *; }
