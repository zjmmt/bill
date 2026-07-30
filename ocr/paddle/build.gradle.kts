import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.bill.ocr.paddle"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    androidResources {
        noCompress += "onnx"
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
    implementation(libs.onnxruntime.android)
    implementation(libs.opencv.android)
    implementation(libs.kotlinx.coroutines.android)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit)
}

private fun java.io.File.sha256(): String =
    MessageDigest.getInstance("SHA-256")
        .let { digest ->
            inputStream().buffered().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            digest.digest().joinToString("") { byte ->
                "%02x".format(byte.toInt() and 0xff)
            }
        }

val verifyBundledOcrModels by tasks.registering {
    val expectedFiles = linkedMapOf(
        "src/main/assets/ocr/ppocrv6-small/det/inference.onnx" to
            "d73e0058b7a8086bbd57f3d10b8bcd4ff95363f67e06e2762b5e814fe9c9410e",
        "src/main/assets/ocr/ppocrv6-small/rec/inference.onnx" to
            "5435fd747c9e0efe15a96d0b378d5bd157e9492ed8fd80edf08f30d02fa24634",
        "src/main/assets/ocr/ppocrv6-small/rec/inference.yml" to
            "ab078671bb49f06228eadccd34f1bb501e157f7a047095ffb943ba81512c77d1",
    )
    val modelFiles = expectedFiles.keys.map(layout.projectDirectory::file)
    val marker = layout.buildDirectory.file("verification/bundled-ocr-models.sha256")

    inputs.files(modelFiles)
    outputs.file(marker)

    doLast {
        expectedFiles.forEach { (relativePath, expectedHash) ->
            val modelFile = layout.projectDirectory.file(relativePath).asFile
            check(modelFile.isFile) { "Missing bundled OCR asset: $relativePath" }
            check(modelFile.sha256() == expectedHash) {
                "Bundled OCR asset hash mismatch: $relativePath"
            }
        }
        val recognitionConfig = modelFiles.last().asFile.readText(Charsets.UTF_8)
        check("  - A\n" in recognitionConfig) { "OCR dictionary is missing Latin text" }
        check("  - 中\n" in recognitionConfig) { "OCR dictionary is missing Chinese text" }
        check("  - あ\n" in recognitionConfig && "  - ア\n" in recognitionConfig) {
            "OCR dictionary is missing Japanese text"
        }
        marker.get().asFile.apply {
            parentFile.mkdirs()
            writeText(expectedFiles.values.joinToString("\n", postfix = "\n"))
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn(verifyBundledOcrModels)
}
