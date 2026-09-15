import java.util.Properties

plugins {
    alias(libs.plugins.hilt)
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    alias(libs.plugins.google.services)
}

val localSecrets = Properties()
val secretsFile = rootProject.file("local.properties")
if (secretsFile.exists()) {
    secretsFile.inputStream().use { input -> localSecrets.load(input) }
}

// Android talks to Payroll.Web only through the configured local/domain endpoint.
// Render is intentionally not hard-coded.
val biometricApiBaseUrl: String =
    providers.gradleProperty("BIOMETRIC_API_BASE_URL").orNull
        ?: localSecrets.getProperty("BIOMETRIC_API_BASE_URL")
        ?: "http://192.168.31.38:5000/"

android {
    namespace = "com.biometric.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.biometric.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField(
            "String",
            "BIOMETRIC_API_BASE_URL",
            "\"${biometricApiBaseUrl.replace("\\", "\\\\").replace("\"", "\\\")}\""
        )
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    kotlin {
        jvmToolchain(17)
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("com.google.android.flexbox:flexbox:3.0.0")

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation("androidx.hilt:hilt-work:1.3.0")
    ksp("androidx.hilt:hilt-compiler:1.3.0")

    // Firebase
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.database)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.auth)
    implementation(libs.firebase.storage)

    // Razorpay
    implementation(libs.razorpay)

    // Excel Export
    implementation(libs.poi)
    implementation(libs.poi.ooxml)

    // Google Drive & Auth
    implementation(libs.google.api.client)
    implementation(libs.google.api.services.drive)
    implementation(libs.google.auth.library)
    implementation(libs.play.services.auth)

    // Retrofit & Networking
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp.logging.interceptor)

    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)

    // Lifecycle & ViewModel
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // Fragment & Activity
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.fragment.ktx)

    // Biometric Login
    implementation(libs.androidx.biometric)

    // ML Kit Text Recognition
    implementation(libs.mlkit.text.recognition)
    implementation(libs.mlkit.barcode.scanning)

    // QR Code Generation
    implementation(libs.zxing)

    // CameraX
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Location & Mapping
    implementation("org.osmdroid:osmdroid-android:6.1.18")
    implementation(libs.play.services.location)

    // Existing SignalR compatibility/realtime path
    implementation("com.microsoft.signalr:signalr:8.0.0")
    implementation("io.reactivex.rxjava3:rxjava:3.1.8")
    implementation("org.slf4j:slf4j-android:1.7.36")

    // Paging
    implementation(libs.androidx.paging.runtime)

    // Charts / UI
    implementation(libs.mpandroidchart)
    implementation(libs.generativeai)
    implementation(libs.lottie)
    implementation(libs.shimmer)
    implementation(libs.glide)
    ksp(libs.glide.compiler)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.1")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
