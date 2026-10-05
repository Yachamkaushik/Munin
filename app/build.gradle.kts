plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.munin.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.munin.app"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }
    }

    buildFeatures { compose = true }

    // Keep the model readable straight from the APK (no compression pass over 118 MB).
    androidResources { noCompress += listOf("onnx", "model") }

    sourceSets {
        // Python-generated reference data lives in tools/reference and is shared by both test types.
        getByName("test").resources.srcDir("../tools/reference")
        getByName("androidTest").assets.srcDir("../tools/reference")
        // Evaluation corpus manifest and queries (the rendered images are pushed to the device separately).
        getByName("androidTest").assets.srcDir("../tools/eval/data")
        getByName("test").kotlin.directories.add("src/sharedTest/java")
        getByName("androidTest").kotlin.directories.add("src/sharedTest/java")
    }

    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.tooling.preview)
    debugImplementation(libs.compose.tooling)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.coroutines.android)
    implementation(libs.onnxruntime.android)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.work.runtime.ktx)
    implementation(libs.mlkit.text)
    implementation(libs.mlkit.text.devanagari)

    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.coroutines.test)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.coroutines.test)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
