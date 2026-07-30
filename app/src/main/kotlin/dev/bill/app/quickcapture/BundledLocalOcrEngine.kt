package dev.bill.app.quickcapture

import android.content.Context
import android.graphics.Bitmap
import com.paddle.ocr.EngineConfig
import com.paddle.ocr.PaddleOCR
import com.paddle.ocr.PaddleOCRConfig
import com.paddle.ocr.util.OpenCVUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

internal sealed interface LocalOcrResult {
    data class Lines(val values: List<String>) : LocalOcrResult

    data object Empty : LocalOcrResult

    data object Failed : LocalOcrResult
}

/**
 * One process-wide OCR execution slot backed by bundled PaddleOCR/ONNX assets.
 *
 * Screenshot capture and user-selected images share this gate, so model memory and CPU work cannot
 * multiply when the user triggers both paths close together. Sessions are released after every
 * user-triggered run; there is no resident recognizer, background scan, network call, or telemetry.
 */
internal object BundledLocalOcrEngine {
    private val executionMutex = Mutex()

    suspend fun recognize(
        context: Context,
        bitmap: Bitmap,
    ): LocalOcrResult {
        if (!executionMutex.tryLock()) return LocalOcrResult.Failed
        var recognizer: PaddleOCR? = null
        return try {
            try {
                if (!OpenCVUtils.init(context.applicationContext)) {
                    return LocalOcrResult.Failed
                }
                recognizer = PaddleOCR.create(
                    context = context.applicationContext,
                    config = PaddleOCRConfig(
                        detMaxSideLimit = MAX_DETECTION_SIDE,
                        detMaxCandidates = MAX_DETECTION_CANDIDATES,
                        recScoreThresh = MIN_RECOGNITION_CONFIDENCE,
                        recBatchSize = 1,
                    ),
                    engineConfig = EngineConfig(numThreads = OCR_CPU_THREADS),
                    detModelAssetPath = DETECTION_MODEL_ASSET,
                    recModelAssetPath = RECOGNITION_MODEL_ASSET,
                    recConfigAssetPath = RECOGNITION_CONFIG_ASSET,
                )
                val lines = recognizer
                    .recognize(bitmap)
                    .results
                    .asSequence()
                    .map { it.text.trim() }
                    .filter { it.isNotEmpty() }
                    .toList()
                if (lines.isEmpty()) LocalOcrResult.Empty else LocalOcrResult.Lines(lines)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: LinkageError) {
                LocalOcrResult.Failed
            } catch (_: Exception) {
                LocalOcrResult.Failed
            } finally {
                withContext(NonCancellable) {
                    try {
                        recognizer?.release()
                    } catch (_: LinkageError) {
                        // The wrapper retains no session even if native teardown is unavailable.
                    } catch (_: Exception) {
                        // The capture result is still valid. No session is retained by this wrapper.
                    }
                }
            }
        } finally {
            executionMutex.unlock()
        }
    }

    private const val OCR_CPU_THREADS = 2
    private const val MAX_DETECTION_SIDE = 1_600
    private const val MAX_DETECTION_CANDIDATES = 512
    private const val MIN_RECOGNITION_CONFIDENCE = 0.35f
    private const val DETECTION_MODEL_ASSET =
        "ocr/ppocrv6-small/det/inference.onnx"
    private const val RECOGNITION_MODEL_ASSET =
        "ocr/ppocrv6-small/rec/inference.onnx"
    private const val RECOGNITION_CONFIG_ASSET =
        "ocr/ppocrv6-small/rec/inference.yml"
}
