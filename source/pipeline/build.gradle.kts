plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":source:contract"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.junit)
}
