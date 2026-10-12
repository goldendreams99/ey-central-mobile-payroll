# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Preserve line numbers for debugging stack traces
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ============================================
# NativeBridge - JavaScript Interface (CRITICO)
# ============================================
-keepclassmembers class ai.employ.app.NativeBridge {
    public *;
}
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# ============================================
# App Main Classes
# ============================================
-keep class ai.employ.app.MainActivity { *; }
-keep class ai.employ.app.WebSocketService { *; }
-keep class ai.employ.app.NativeBridge { *; }

# ============================================
# Librerías (EY-CENTRAL 2026-10-11, aviso de Play Console "DEX code optimization below threshold", 392:
# optimización/ofuscación/reducción en 15 %, mínimo 25 %). Antes había -keep de paquetes enteros
# (androidx, Firebase, Play Services, OkHttp, biometric, security-crypto, org.json) que dejaban casi todo
# el código sin reducir. Esas librerías traen sus propias reglas (consumer rules) dentro del AAR, así que
# alcanzan. Capacitor se conserva entero: su puente resuelve plugins y métodos por reflexión.
# ============================================
-keep class com.getcapacitor.** { *; }
-dontwarn com.getcapacitor.**
-dontwarn okhttp3.**
-dontwarn okio.**


# ============================================
# LOGGING - Strip debug/verbose logs in release
# ============================================
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
}
