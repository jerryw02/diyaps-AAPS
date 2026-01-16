plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    id("kotlin-android")
    id("android-module-dependencies")
    id("test-module-dependencies")
    id("jacoco-module-dependencies")
}


android {
    namespace = "app.aaps.plugins.source"

    compileSdk = 35

    buildFeatures {
        aidl = true
    }

    // ✅ 新版 JVM 目标配置（替代 kotlinOptions）
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // ✅ 关键：在 android 块内配置 Kotlin 的 jvmToolchain
    kotlin {
        jvmToolchain(17)
    }
    
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:interfaces"))
    implementation(project(":core:keys"))
    implementation(project(":core:objects"))
    implementation(project(":core:nssdk"))
    implementation(project(":core:ui"))
    implementation(project(":core:utils"))
    implementation(project(":core:validators"))
    implementation(project(":shared:impl"))

    implementation project(':plugins:bus')        // 👈 添加这一行
    implementation project(':utils')
    implementation project(':database')
    implementation project(':shared')

    testImplementation(libs.androidx.work.testing)
    testImplementation(project(":shared:tests"))

    ksp(libs.com.google.dagger.compiler)
    ksp(libs.com.google.dagger.android.processor)
}

// ✅ 强制覆盖：确保所有 Kotlin 编译任务使用 JVM 17
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

/*
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    //id("com.android.library")
    id("kotlin-android")
    id("kotlin-kapt")
    //id("dagger.hilt.android.plugin")
    id("android-module-dependencies")
    id("test-module-dependencies")
    id("jacoco-module-dependencies")
}

android {
    namespace = "app.aaps.plugins.source"

    compileSdk = 34
    namespace = "app.aaps.plugins.source.xDripAidl"

    defaultConfig {
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    sourceSets {
        main {
            aidl.srcDirs = ["src/main/aidl"]
            java.srcDirs = ["src/main/java", "src/main/kotlin"]
            res.srcDirs = ["src/main/res"]
            manifest.srcFile= ["src/main/AndroidManifest.xml"]
        }
    }
    buildFeatures {
        aidl = true
    }
}


dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:interfaces"))
    implementation(project(":core:keys"))
    implementation(project(":core:objects"))
    implementation(project(":core:nssdk"))
    implementation(project(":core:ui"))
    implementation(project(":core:utils"))
    implementation(project(":core:validators"))
    implementation(project(":shared:impl"))

    testImplementation(libs.androidx.work.testing)

    testImplementation(project(":shared:tests"))

    ksp(libs.com.google.dagger.compiler)
    ksp(libs.com.google.dagger.android.processor)

    implementation(project(":app"))  // 依赖主模块

    // Kotlin
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // AndroidX
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-common-java8:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")

    // AAPS 核心依赖
    implementation("info.nightscout.androidaps:core:0.0.0") {
        isTransitive = true
    }

    // RxJava
    implementation("io.reactivex.rxjava3:rxjava:3.1.6")
    implementation("io.reactivex.rxjava3:rxandroid:3.0.2")
    
    // JSON
    implementation("org.json:json:20230227")

}
*/

