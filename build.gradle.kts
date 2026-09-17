import com.vanniktech.maven.publish.MavenPublishBaseExtension

plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.vanniktechMavenPublish) apply false
}

allprojects {
    group = "com.thelightphone.backup"
    version = "0.0.6"

    plugins.withId("com.vanniktech.maven.publish") {
        extensions.configure<MavenPublishBaseExtension> {
            publishToMavenCentral()
            signAllPublications()

            pom {
                name.set(project.name)
                description.set("light-backup: a LightOS plugin for backing up user data to the cloud.")
                inceptionYear.set("2026")
                url.set("https://github.com/lightphone/light-backup")

                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://github.com/lightphone/light-backup/blob/main/LICENSE")
                        distribution.set("https://github.com/lightphone/light-backup/blob/main/LICENSE")
                    }
                }

                developers {
                    developer {
                        id.set("thelightphone")
                        name.set("The Light Phone")
                        url.set("https://github.com/lightphone")
                    }
                }

                scm {
                    url.set("https://github.com/lightphone/light-backup")
                    connection.set("scm:git:git://github.com/lightphone/light-backup.git")
                    developerConnection.set("scm:git:ssh://git@github.com/lightphone/light-backup.git")
                }
            }
        }
    }
}
