import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import com.android.build.gradle.BaseExtension
import org.gradle.api.JavaVersion

// Top-level build file where you can add configuration options common to all sub-projects/modules.

buildscript {
    repositories {
        mavenCentral()
        google()
    }
    dependencies {
        classpath(libs.com.android.tools.build)
        classpath(libs.com.google.gms)
        classpath(libs.com.google.firebase.gradle)

        // NOTE: Do not place your application dependencies here; they belong
        // in the individual module build.gradle files

        classpath(libs.kotlin.gradlePlugin)
        classpath(libs.kotlin.allopen)
        classpath(libs.kotlin.serialization)
    }
}

plugins {
    alias(libs.plugins.klint)
    alias(libs.plugins.moduleDependencyGraph)
    alias(libs.plugins.ksp)
    alias(libs.plugins.compose.compiler) apply false
    id(libs.plugins.android.test.get().pluginId) apply false
    id(libs.plugins.kotlin.android.get().pluginId) apply false
}

allprojects {
    repositories {
        mavenCentral()
        google()
        maven("https://jitpack.io")
    }
    
    // 配置 Kotlin 编译选项
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            freeCompilerArgs.add("-opt-in=kotlin.RequiresOptIn")
            freeCompilerArgs.add("-opt-in=kotlin.ExperimentalUnsignedTypes")
            freeCompilerArgs.add("-Xannotation-default-target=param-property")
            freeCompilerArgs.add("-Xjvm-default=all") //Support @JvmDefault
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    
    // 配置 Java 编译选项
    gradle.projectsEvaluated {
        tasks.withType<JavaCompile> {
            sourceCompatibility = JavaVersion.VERSION_17.toString()
            targetCompatibility = JavaVersion.VERSION_17.toString()
            val compilerArgs = options.compilerArgs
            compilerArgs.add("-Xlint:deprecation")
            compilerArgs.add("-Xlint:unchecked")
        }
    }

    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "jacoco")
}

subprojects {
    // 为 Android 项目配置编译选项
    afterEvaluate {
        // 检查是否是 Android 项目
        if (project.plugins.hasPlugin("com.android.application") || 
            project.plugins.hasPlugin("com.android.library")) {
            
            // 获取 Android 扩展并配置
            extensions.findByType<BaseExtension>()?.apply {
                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_17
                    targetCompatibility = JavaVersion.VERSION_17
                }
            }
        }
    }
}

// Setup all reports aggregation
apply(from = "jacoco_aggregation.gradle.kts")

tasks.register<Delete>("clean").configure {
    delete(rootProject.layout.buildDirectory)
}
