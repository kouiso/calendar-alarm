plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.calendaralarm"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.calendaralarm"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        // 配布のたびに上がる番号 (#309)。配布の workflow が -PversionCode=<run_number> で渡す。
        // 渡さない手元のビルドは 1。数でない・1 未満の値はビルドを止める (黙って 1 に戻すと番号が下がるため)
        versionCode = providers.gradleProperty("versionCode").orNull?.let { raw ->
            raw.toIntOrNull()?.takeIf { it >= 1 }
                ?: throw GradleException("versionCode must be a positive integer: $raw")
        } ?: 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // AI のキーは利用者に入力させず、ビルドする側が渡す (局長決定 2026-10-17 で入力欄を廃止)。
        // 渡さないビルドでは AI の入口を出さない
        val openRouterKey = providers.gradleProperty("openrouterApiKey")
            .orElse(providers.environmentVariable("OPENROUTER_API_KEY"))
            .getOrElse("")
        buildConfigField("String", "OPENROUTER_API_KEY", "\"${openRouterKey.replace("\"", "")}\"")
    }

    buildTypes {
        release {
            // R8 (縮小・難読化)。戻す時はこの 1 行を false にする (#220)
            isMinifyEnabled = true
            isShrinkResources = isMinifyEnabled
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            // FAD で配るのは debug APK。停止 E2E を R8 後の APK で回す時だけ -PminifyDebug=true を渡す (#220)。
            // 渡さない通常の debug は従来どおり minify なし
            isMinifyEnabled = providers.gradleProperty("minifyDebug").orNull == "true"
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                // このマシンの egress は repo.maven.apache.org が 429 を返すため GCS ミラーへ
                it.systemProperty(
                    "robolectric.dependency.repo.url",
                    "https://maven-central.storage-download.googleapis.com/maven2",
                )
                it.systemProperty("robolectric.dependency.repo.id", "gcs-mirror")
            }
        }
    }

    // 今ある指摘は lint-baseline.xml に固定し、新しい指摘だけでビルドを落とす (#300)
    lint {
        baseline = file("lint-baseline.xml")
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    implementation(project(":shared"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)

    implementation(libs.core.ktx)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.work.runtime)
    implementation(libs.datastore.preferences)
    implementation(libs.ktor.client.cio)
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)

    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlinx.coroutines.android)
    // Googleカレンダー連携 (Calendar API の eventType 取得に必要)
    implementation("com.google.android.gms:play-services-auth:21.3.0")
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.pdfbox.android)

    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlin.test)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
}
