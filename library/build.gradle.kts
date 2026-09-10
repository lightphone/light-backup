plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.androidLibrary)
    `maven-publish`
    alias(libs.plugins.vanniktechMavenPublish)
}

android {
    namespace = "com.thelightphone.backup.library"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvm()
    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
        publishLibraryVariants("release")
    }

    sourceSets {
        // jvm() and androidTarget() both run on the JVM, so rather than sharing code through
        // commonMain (which every KMP target depends on) we introduce a source set that only
        // jvmMain and androidMain depend on. That lets shared code use JVM-only APIs directly,
        // without expect/actual.
        val jvmSharedMain by creating {
            dependsOn(commonMain.get())
        }
        val jvmSharedTest by creating {
            dependsOn(commonTest.get())
        }
        jvmMain.get().dependsOn(jvmSharedMain)
        androidMain.get().dependsOn(jvmSharedMain)
        jvmTest.get().dependsOn(jvmSharedTest)

        jvmSharedMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.io.core)
            implementation(libs.light.toolmanager.server)
            implementation(libs.ktor.clientCore)
            implementation(libs.ktor.clientCio)
            implementation(libs.ktor.clientWebsockets)
        }

        jvmSharedTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlin.testJunit)
        }

        androidMain.dependencies {
            implementation(libs.androidx.work.runtime)
        }
    }
}
