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
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // AI のキーは利用者に入力させず、ビルドする側が渡す (局長決定 2026-10-17 で入力欄を廃止)。
        // 渡さないビルドでは AI の入口を出さない
        val openRouterKey = providers.gradleProperty("openrouterApiKey")
            .orElse(providers.environmentVariable("OPENROUTER_API_KEY"))
            .getOrElse("")
        buildConfigField("String", "OPENROUTER_API_KEY", "\"${openRouterKey.replace("\"", "")}\"")
        // AdMob (#140)。未指定 = Google 公式のテストID。本番配信では
        // -PadmobAppId / -PadmobBannerUnitId (または環境変数) で実IDに差し替える
        val admobAppId = providers.gradleProperty("admobAppId")
            .orElse(providers.environmentVariable("ADMOB_APP_ID"))
            .getOrElse("ca-app-pub-3940256099942544~3347511713")
        manifestPlaceholders["admobAppId"] = admobAppId
        val admobBannerUnitId = providers.gradleProperty("admobBannerUnitId")
            .orElse(providers.environmentVariable("ADMOB_BANNER_UNIT_ID"))
            .getOrElse("ca-app-pub-3940256099942544/6300978111")
        buildConfigField("String", "ADMOB_BANNER_UNIT_ID", "\"$admobBannerUnitId\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    // 広告 (#140): AdMob (GMA SDK) + GDPR/EEA 同意 (UMP)
    implementation(libs.play.services.ads)
    implementation(libs.user.messaging.platform)

    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlin.test)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
}
