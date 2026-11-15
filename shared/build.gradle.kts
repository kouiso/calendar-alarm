plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.library)
}

kotlin {
    applyDefaultHierarchyTemplate()
    androidTarget()
    jvm()
    // iOS: Swift から共有ドメインを呼ぶため Framework をエクスポート
    listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach {
        it.binaries.framework {
            baseName = "SharedKit"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.cio)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
        }
        val iosMain by getting {
            dependencies {
                implementation(libs.ktor.client.darwin)
            }
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

// 判定の表 (#301) はテストの入力。Gradle に知らせないと、表だけ直しても jvmTest が UP-TO-DATE で飛ばされる
tasks.withType<Test>().configureEach {
    inputs.file(rootProject.layout.projectDirectory.file("testdata/shared-decisions.json"))
        .withPropertyName("sharedDecisionTable")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

android {
    namespace = "com.calendaralarm.shared"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }
}
