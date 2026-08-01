plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val billVersionCodeText = providers.environmentVariable("BILL_VERSION_CODE").orElse("1").get()
val billVersionCodeValue = billVersionCodeText.toIntOrNull()
    ?.takeIf { it > 0 }
    ?: throw GradleException("BILL_VERSION_CODE must be a positive integer.")
val billVersionNameValue = providers.environmentVariable("BILL_VERSION_NAME").orElse("0.1.0").get()
if (
    billVersionNameValue.isBlank() ||
    billVersionNameValue.length > 100 ||
    billVersionNameValue.any { it == '\r' || it == '\n' || it.isISOControl() }
) {
    throw GradleException("BILL_VERSION_NAME must be non-blank, at most 100 characters, and contain no control characters.")
}

val releaseSigningVariableNames = listOf(
    "BILL_RELEASE_STORE_FILE",
    "BILL_RELEASE_STORE_PASSWORD",
    "BILL_RELEASE_KEY_ALIAS",
    "BILL_RELEASE_KEY_PASSWORD",
)
val releaseSigningValues = releaseSigningVariableNames.associateWith { variableName ->
    providers.environmentVariable(variableName).orNull?.takeIf { it.isNotBlank() }
}
val suppliedReleaseSigningValues = releaseSigningValues.filterValues { it != null }
if (suppliedReleaseSigningValues.isNotEmpty() && suppliedReleaseSigningValues.size != releaseSigningValues.size) {
    val missingNames = releaseSigningValues.filterValues { it == null }.keys.joinToString()
    throw GradleException("Release signing environment is incomplete. Missing: $missingNames")
}
val requireSignedRelease = when (
    val value = providers.environmentVariable("BILL_REQUIRE_SIGNED_RELEASE")
        .orElse("false")
        .get()
        .trim()
        .lowercase()
) {
    "true", "1", "yes" -> true
    "false", "0", "no", "" -> false
    else -> throw GradleException("BILL_REQUIRE_SIGNED_RELEASE must be true or false.")
}
val releaseSigningEnabled = suppliedReleaseSigningValues.size == releaseSigningValues.size
if (requireSignedRelease && !releaseSigningEnabled) {
    throw GradleException(
        "A signed release was requested. Set all of: ${releaseSigningVariableNames.joinToString()}",
    )
}
val releaseStoreFile = if (releaseSigningEnabled) {
    file(requireNotNull(releaseSigningValues.getValue("BILL_RELEASE_STORE_FILE"))).canonicalFile.also { candidate ->
        if (!candidate.isFile) {
            throw GradleException("BILL_RELEASE_STORE_FILE must point to an existing file.")
        }
        if (candidate.toPath().startsWith(rootProject.projectDir.canonicalFile.toPath())) {
            throw GradleException("BILL_RELEASE_STORE_FILE must stay outside the repository.")
        }
    }
} else {
    null
}

android {
    namespace = "dev.bill.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.bill.app"
        minSdk = 26
        targetSdk = 36
        versionCode = billVersionCodeValue
        versionName = billVersionNameValue
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseSigningEnabled) {
            create("release") {
                storeFile = requireNotNull(releaseStoreFile)
                storePassword = requireNotNull(releaseSigningValues.getValue("BILL_RELEASE_STORE_PASSWORD"))
                keyAlias = requireNotNull(releaseSigningValues.getValue("BILL_RELEASE_KEY_ALIAS"))
                keyPassword = requireNotNull(releaseSigningValues.getValue("BILL_RELEASE_KEY_PASSWORD"))
            }
        }
    }

    buildTypes {
        getByName("release") {
            if (releaseSigningEnabled) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
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
    implementation(project(":source:alipay"))
    implementation(project(":source:bank:cmb"))
    implementation(project(":source:generic-delimited-statement"))
    implementation(project(":source:generic-photo-ocr"))
    implementation(project(":source:generic-receipt-image"))
    implementation(project(":source:generic-share-text"))
    implementation(project(":source:generic-notification"))
    implementation(project(":source:pipeline"))
    implementation(project(":source:wechat"))

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
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
