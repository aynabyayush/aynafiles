# QR scanning / generation use reflection inside the ZXing capture activity.
-keep class com.journeyapps.barcodescanner.** { *; }
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# OkHttp platform warnings
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# kotlinx.serialization keeps serializers via generated code; keep names for our models.
-keepclassmembers @kotlinx.serialization.Serializable class com.ayush.apnafiles.** {
    *** Companion;
}
