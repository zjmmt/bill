package dev.bill.app.quickcapture

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine

internal sealed interface LocalOcrResult {
    data class Lines(val values: List<String>) : LocalOcrResult

    data object Empty : LocalOcrResult

    data object Failed : LocalOcrResult
}

/**
 * One process-wide bundled OCR instance and one execution slot.
 *
 * Screenshot capture and user-selected images share this gate, so model memory and CPU work cannot
 * multiply when the user triggers both paths close together.
 */
internal object BundledLocalOcrEngine {
    private val executionMutex = Mutex()
    private val recognizer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    suspend fun recognize(bitmap: Bitmap): LocalOcrResult = executionMutex.withLock {
        try {
            val text = recognizer.processAwait(InputImage.fromBitmap(bitmap, 0))
            val lines = text.textBlocks
                .flatMap { it.lines }
                .sortedWith(
                    compareBy<Text.Line>(
                        { it.boundingBox?.top ?: Int.MAX_VALUE },
                        { it.boundingBox?.left ?: Int.MAX_VALUE },
                    ),
                )
                .map { it.text }
            if (lines.isEmpty()) LocalOcrResult.Empty else LocalOcrResult.Lines(lines)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: RuntimeException) {
            LocalOcrResult.Failed
        }
    }

    private suspend fun com.google.mlkit.vision.text.TextRecognizer.processAwait(
        image: InputImage,
    ): Text = suspendCancellableCoroutine { continuation ->
        process(image)
            .addOnSuccessListener { text ->
                if (continuation.isActive) continuation.resume(text)
            }
            .addOnFailureListener { error ->
                if (continuation.isActive) continuation.resumeWithException(error)
            }
    }
}
