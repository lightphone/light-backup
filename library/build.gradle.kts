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
    jvmToolchain(21)
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
            implementation(libs.ktor.clientCore)
            implementation(libs.ktor.clientCio)
            implementation(libs.ktor.clientWebsockets)
            implementation(libs.ktor.clientAuth)
            // dav4jvm needs an XmlPullParser. Android ships one, and xpp3's copy of the same
            // classes conflicts with it, so xpp3 is only added for the plain JVM target (below).
            val dav4jvm = libs.dav4jvm.get()
            implementation("${dav4jvm.module}:${dav4jvm.versionConstraint.requiredVersion}") {
                exclude(group = "org.ogce", module = "xpp3")
            }
        }

        jvmMain.dependencies {
            implementation(libs.xpp3)
        }

        jvmSharedTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlin.testJunit)
            implementation(libs.ktor.clientMock)
        }

        androidMain.dependencies {
            implementation(libs.androidx.work.runtime)
        }
    }
}
