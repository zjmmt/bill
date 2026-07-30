plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.bill.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.bill.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":application"))
    implementation(project(":core:designsystem"))
    implementation(project(":data:local"))
    implementation(project(":feature:accounts"))
    implementation(project(":feature:ledger"))
    implementation(project(":feature:overview"))
    implementation(project(":feature:review"))
    implementation(project(":ocr:paddle"))
    implementation(project(":source:generic-delimited-statement"))
    implementation(project(":source:generic-photo-ocr"))
    implementation(project(":source:generic-receipt-image"))
    implementation(project(":source:generic-share-text"))
    implementation(project(":source:generic-notification"))
    implementation(project(":source:pipeline"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
