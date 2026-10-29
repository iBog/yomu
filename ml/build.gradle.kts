plugins {
    id("com.android.library")
}

android {
    namespace = "com.yomu.ml"
    compileSdk = 37
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild {
            cmake {
                cFlags += "-O2"
                cppFlags += "-std=c++17 -O2"
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
        // CI passes -Pyomu.abis=arm64-v8a on PRs to compile llama.cpp once instead of twice.
        val abis = (findProperty("yomu.abis") as String?)?.split(",") ?: listOf("arm64-v8a", "x86_64")
        ndk { abiFilters += abis }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.31.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("org.mockito:mockito-core:5.24.0")
}
