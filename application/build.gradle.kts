plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core:model"))
    api(project(":core:domain"))
    implementation(project(":core:ledger"))
    implementation(project(":source:contract"))
    implementation(project(":source:generic-photo-ocr"))
    implementation(project(":source:generic-delimited-statement"))
    implementation(project(":source:generic-receipt-image"))
    implementation(project(":source:generic-notification"))
    implementation(project(":source:pipeline"))
    api(project(":source:review-contract"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(project(":source:generic-share-text"))
}
