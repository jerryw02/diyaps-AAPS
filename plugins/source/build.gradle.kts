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

    buildFeatures {
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    // 一般不需要显式配置 sourceSets，AGP 自动处理
    // 如果确实需要，再取消注释下面的代码
    /*
    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/java", "src/main/kotlin")
            aidl.srcDir("src/main/aidl")
        }
    }
    */
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

