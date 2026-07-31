plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":source:contract"))
    implementation(project(":source:generic-notification"))
    testImplementation(libs.junit)
}
