plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

android {
    namespace = "com.kevingong.printer"
    compileSdk = 36

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = false
        aidl = false
        shaders = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(libs.junit)
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = project.findProperty("GROUP") as String
                artifactId = "printer"
                version = project.version.toString().takeIf { it != "unspecified" }
                    ?: project.findProperty("VERSION_NAME") as String
                pom {
                    name.set("Printer-Android")
                    description.set("ESC/POS receipt printer library for Android: BLE, SPP, USB, TCP and a virtual printer.")
                    url.set("https://github.com/KevinGong2013/Printer-Android")
                    licenses {
                        license {
                            name.set("Apache License 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0")
                        }
                    }
                    developers {
                        developer {
                            id.set("KevinGong2013")
                            name.set("Kevin")
                        }
                    }
                    scm {
                        url.set("https://github.com/KevinGong2013/Printer-Android")
                    }
                }
            }
        }
    }
}
