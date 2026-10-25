plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.yomu.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.yomu.app"
        minSdk = 26
        targetSdk = 36
        // The release workflow passes both from the tag (#405); local builds keep the defaults.
        versionCode = (findProperty("yomu.versionCode") as String?)?.toInt() ?: 1
        versionName = findProperty("yomu.versionName") as String? ?: "1.0"
        testInstrumentationRunner = "com.yomu.app.CustomTestRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        // Signs only where the keystore secrets exist (the release workflow); elsewhere assembleRelease stays unsigned.
        System.getenv("YOMU_KEYSTORE_FILE")?.let { keystore ->
            create("release") {
                storeFile = file(keystore)
                storePassword = System.getenv("YOMU_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("YOMU_KEY_ALIAS")
                keyPassword = System.getenv("YOMU_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions { unitTests.isIncludeAndroidResources = true }

    buildFeatures { compose = true }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
        // ggml finds its CPU backend variants by listing the native lib dir, which is empty unless extracted.
        jniLibs { useLegacyPackaging = true }
    }
}

dependencies {
    implementation(project(":pipeline"))
    implementation(project(":ml"))
    implementation(project(":core"))

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.activity:activity-compose:1.8.1")

    implementation(platform("androidx.compose:compose-bom:2023.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.navigation:navigation-compose:2.7.5")

    implementation("com.google.dagger:hilt-android:2.59.2")
    ksp("com.google.dagger:hilt-android-compiler:2.59.2")
    kspAndroidTest("com.google.dagger:hilt-android-compiler:2.59.2")
    androidTestImplementation("com.google.dagger:hilt-android-testing:2.59.2")
    implementation("androidx.hilt:hilt-navigation-compose:1.1.0")

    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")

    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.google.code.gson:gson:2.8.5")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("io.coil-kt:coil-compose:2.5.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.11.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("org.mockito:mockito-core:5.8.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    androidTestImplementation(platform("androidx.compose:compose-bom:2023.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
