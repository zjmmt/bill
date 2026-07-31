package dev.bill.app.quickcapture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import dev.bill.application.PhotoOcrTranscriptCapture
import dev.bill.application.PhotoOcrTranscriptEvidence
import dev.bill.application.SourceCaptureError
import dev.bill.application.SourceCaptureResult
import dev.bill.app.BoundedDocumentRead
import dev.bill.app.BoundedDocumentReader
import dev.bill.source.genericphotoocr.OcrTranscript
import java.io.IOException
import java.nio.ByteBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface SelectedPhotoOcrImporter {
    suspend fun ingest(commandId: String, uriString: String?): SourceCaptureResult

    data object Unavailable : SelectedPhotoOcrImporter {
        override suspend fun ingest(
            commandId: String,
            uriString: String?,
        ): SourceCaptureResult = failure(SourceCaptureError.PARSER_UNAVAILABLE)
    }
}

/**
 * Reads only images explicitly returned by Photo Picker/SAF, one at a time.
 *
 * No URI, filename, media-library grant, source image, or Bitmap is persisted.
 */
class ContentResolverSelectedPhotoOcrImporter(
    private val applicationContext: android.content.Context,
    private val contentResolver: android.content.ContentResolver,
    private val capture: PhotoOcrTranscriptCapture,
) : SelectedPhotoOcrImporter {
    override suspend fun ingest(
        commandId: String,
        uriString: String?,
    ): SourceCaptureResult {
        val uri = try {
            uriString?.let(Uri::parse)
        } catch (_: RuntimeException) {
            null
        } ?: return failure(SourceCaptureError.PARSE_REJECTED)
        if (uri.scheme != "content") return failure(SourceCaptureError.PARSE_REJECTED)

        val bytes = when (val read = readBoundedImage(uri)) {
            is ImageReadResult.Success -> read.bytes
            ImageReadResult.TooLarge -> return failure(SourceCaptureError.CONTENT_TOO_LARGE)
            ImageReadResult.Rejected -> return failure(SourceCaptureError.PARSE_REJECTED)
        }
        return try {
            withContext(Dispatchers.Default) {
                var bitmap: Bitmap? = null
                try {
                    bitmap = decodeBounded(bytes)
                        ?: return@withContext failure(SourceCaptureError.PARSE_REJECTED)
                    val lines = when (
                        val ocr = BundledLocalOcrEngine.recognize(applicationContext, bitmap)
                    ) {
                        is LocalOcrResult.Lines -> ocr.values
                        LocalOcrResult.Empty ->
                            return@withContext failure(SourceCaptureError.EMPTY_CONTENT)

                        LocalOcrResult.Failed ->
                            return@withContext failure(SourceCaptureError.PARSE_REJECTED)
                    }
                    val transcript = OcrTranscript.encodeSpatial(lines)
                        ?: return@withContext failure(SourceCaptureError.CONTENT_TOO_LARGE)
                    capture.ingest(commandId, PhotoOcrTranscriptEvidence(transcript))
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: RuntimeException) {
                    failure(SourceCaptureError.PARSE_REJECTED)
                } finally {
                    bitmap?.recycle()
                }
            }
        } finally {
            bytes.fill(0)
        }
    }

    private suspend fun readBoundedImage(uri: Uri): ImageReadResult =
        withContext(Dispatchers.IO) {
            try {
                val mediaType = contentResolver.getType(uri)
                    ?.substringBefore(';')
                    ?.trim()
                    ?.lowercase()
                    ?: return@withContext ImageReadResult.Rejected
                if (mediaType !in SUPPORTED_MEDIA_TYPES) {
                    return@withContext ImageReadResult.Rejected
                }
                when (
                    val read = contentResolver.openInputStream(uri)
                        ?.use { BoundedDocumentReader.copy(it, MAX_IMAGE_BYTES) }
                        ?: return@withContext ImageReadResult.Rejected
                ) {
                    is BoundedDocumentRead.Success -> ImageReadResult.Success(read.bytes)
                    BoundedDocumentRead.TooLarge -> ImageReadResult.TooLarge
                }
            } catch (_: SecurityException) {
                ImageReadResult.Rejected
            } catch (_: IOException) {
                ImageReadResult.Rejected
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: RuntimeException) {
                ImageReadResult.Rejected
            }
        }

    private fun decodeBounded(bytes: ByteArray): Bitmap? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            decodeWithImageDecoder(bytes)
        } else {
            decodeWithBitmapFactory(bytes)
        }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.P)
    private fun decodeWithImageDecoder(bytes: ByteArray): Bitmap? = try {
        ImageDecoder.decodeBitmap(
            ImageDecoder.createSource(ByteBuffer.wrap(bytes)),
        ) { decoder, info, _ ->
            val target = targetSize(info.size.width, info.size.height)
                ?: throw ImageBoundsRejected()
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSize(target.first, target.second)
            decoder.setOnPartialImageListener { false }
        }
    } catch (_: ImageBoundsRejected) {
        null
    } catch (_: ImageDecoder.DecodeException) {
        null
    } catch (_: IOException) {
        null
    } catch (_: RuntimeException) {
        null
    }

    private fun decodeWithBitmapFactory(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val target = targetSize(bounds.outWidth, bounds.outHeight) ?: return null
        var sampleSize = 1
        while (
            bounds.outWidth / sampleSize > target.first * 2 ||
            bounds.outHeight / sampleSize > target.second * 2
        ) {
            sampleSize *= 2
        }
        val decoded = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        ) ?: return null
        if (decoded.width == target.first && decoded.height == target.second) return decoded
        var scaled: Bitmap? = null
        return try {
            Bitmap.createScaledBitmap(
                decoded,
                target.first,
                target.second,
                true,
            ).also { scaled = it }
        } finally {
            if (scaled !== decoded) decoded.recycle()
        }
    }

    private fun targetSize(width: Int, height: Int): Pair<Int, Int>? {
        if (width <= 0 || height <= 0) return null
        val pixels = width.toLong() * height.toLong()
        if (
            width > MAX_SOURCE_DIMENSION ||
            height > MAX_SOURCE_DIMENSION ||
            pixels > MAX_SOURCE_PIXELS
        ) {
            return null
        }
        val scale = minOf(
            1.0,
            MAX_OCR_WIDTH.toDouble() / width.toDouble(),
            MAX_OCR_HEIGHT.toDouble() / height.toDouble(),
            kotlin.math.sqrt(MAX_OCR_PIXELS.toDouble() / pixels.toDouble()),
        )
        return Pair(
            (width * scale).toInt().coerceAtLeast(1),
            (height * scale).toInt().coerceAtLeast(1),
        )
    }

    private sealed interface ImageReadResult {
        data class Success(val bytes: ByteArray) : ImageReadResult

        data object TooLarge : ImageReadResult

        data object Rejected : ImageReadResult
    }

    private class ImageBoundsRejected : RuntimeException(null, null, false, false)

    private companion object {
        const val MAX_IMAGE_BYTES = 16 * 1024 * 1024
        const val MAX_SOURCE_DIMENSION = 16_384
        const val MAX_SOURCE_PIXELS = 32_000_000L
        const val MAX_OCR_PIXELS = 2_560_000L
        const val MAX_OCR_WIDTH = 1_600
        const val MAX_OCR_HEIGHT = 1_600
        val SUPPORTED_MEDIA_TYPES = setOf(
            "image/png",
            "image/jpeg",
            "image/webp",
            "image/heic",
            "image/heif",
        )
    }
}

private fun failure(error: SourceCaptureError) = SourceCaptureResult.Failure(
    error = error,
    diagnosticCode = null,
)
