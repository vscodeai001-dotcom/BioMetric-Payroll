# Firebase Entities
-keep class com.biometric.app.data.entity.** { *; }
-keepnames class com.biometric.app.data.entity.** { *; }

# Keep Hilt and Dagger
-keep class dagger.hilt.android.internal.managers.** { *; }
-keep class * extends androidx.lifecycle.ViewModel

# Razorpay
-keep class com.razorpay.** {*;}
-dontwarn com.razorpay.**

# PostgreSQL JDBC Driver
-keep class org.postgresql.** { *; }
-dontwarn org.postgresql.**
-dontwarn java.lang.management.**
-dontwarn javax.management.**
-dontwarn javax.naming.**
