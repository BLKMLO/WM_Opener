plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("io.github.takahirom.roborazzi")
}

// Signature de release : fournie par variables d'environnement (secrets GitHub en CI).
// Sans elles, la clé de debug est utilisée (installable, mais une mise à jour signée
// avec une autre clé exigera de désinstaller l'appli).
val ksPath: String? = System.getenv("WM_KEYSTORE_PATH")

android {
    namespace = "com.wmopener"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.wmopener"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "1.1.1"
    }

    signingConfigs {
        if (ksPath != null) create("release") {
            storeFile = file(ksPath)
            storePassword = System.getenv("WM_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("WM_KEY_ALIAS")
            keyPassword = System.getenv("WM_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (ksPath != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = false }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { it.systemProperty("robolectric.dependency.repo.url", "https://maven-central.storage-download.googleapis.com/maven2") }
        }
    }
    packaging { resources.excludes += setOf("META-INF/*.version", "kotlin/**", "DebugProbesKt.bin") }
}

dependencies {
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("androidx.core:core-ktx:1.15.0")

    // Captures d'écran de l'interface sur la JVM (aucune incidence sur l'APK).
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.work:work-testing:2.10.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.43.0")
}
