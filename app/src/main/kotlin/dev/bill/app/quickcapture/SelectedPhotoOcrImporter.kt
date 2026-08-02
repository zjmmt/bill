package dev.bill.app.quickcapture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import dev.bill.app.BoundedDocumentRead
import dev.bill.app.BoundedDocumentReader
import dev.bill.application.PhotoOcrTranscriptCapture
import dev.bill.application.PhotoOcrTranscriptEvidence
import dev.bill.application.SourceCaptureError
import dev.bill.application.SourceCaptureResult
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

sealed interface SelectedImageOcrReadResult {
    data class Lines(
        val values: List<OcrTranscript.RecognizedLine>,
    ) : SelectedImageOcrReadResult

    /** Image decoding and OCR succeeded, but no usable text was recognized. */
    data object Empty : SelectedImageOcrReadResult

    data class Failure(
        val error: SourceCaptureError,
    ) : SelectedImageOcrReadResult
}

interface SelectedImageOcrReader {
    suspend fun read(uriString: String?): SelectedImageOcrReadResult

    data object Unavailable : SelectedImageOcrReader {
        override suspend fun read(uriString: String?): SelectedImageOcrReadResult =
            SelectedImageOcrReadResult.Failure(SourceCaptureError.PARSER_UNAVAILABLE)
    }
}

/**
 * Reads and recognizes only one image explicitly returned by Photo Picker/SAF.
 *
 * No URI, filename, media-library grant, source image, Bitmap, or transcript is persisted here.
 */
class ContentResolverSelectedImageOcrReader(
    private val applicationContext: android.content.Context,
    private val contentResolver: android.content.ContentResolver,
) : SelectedImageOcrReader {
    override suspend fun read(uriString: String?): SelectedImageOcrReadResult {
        val uri = try {
            uriString?.let(Uri::parse)
        } catch (_: RuntimeException) {
            null
        } ?: return rejected()
        if (uri.scheme != "content") return rejected()

        val bytes = when (val read = readBoundedImage(uri)) {
            is ImageReadResult.Success -> read.bytes
            ImageReadResult.TooLarge -> return failed(SourceCaptureError.CONTENT_TOO_LARGE)
            ImageReadResult.Rejected -> return rejected()
        }
        return try {
            withContext(Dispatchers.Default) {
                var bitmap: Bitmap? = null
                try {
                    bitmap = decodeBounded(bytes) ?: return@withContext rejected()
                    when (val ocr = BundledLocalOcrEngine.recognize(applicationContext, bitmap)) {
                        is LocalOcrResult.Lines -> SelectedImageOcrReadResult.Lines(ocr.values)
                        LocalOcrResult.Empty -> SelectedImageOcrReadResult.Empty
                        // The user still needs an editable draft when the local model cannot run.
                        LocalOcrResult.Failed -> SelectedImageOcrReadResult.Empty
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: RuntimeException) {
                    rejected()
                } finally {
                    bitmap?.recycle()
                }
            }
        } finally {
            bytes.fill(0)
        }
    }

    private suspend fun readBoundedImage(uri: Uri): ImageReadResult = withContext(Dispatchers.IO) {
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
            Bitmap.createScaledBitmap(decoded, target.first, target.second, true)
                .also { scaled = it }
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
            "image/png", "image/jpeg", "image/webp", "image/heic", "image/heif",
        )
    }
}

class ContentResolverSelectedPhotoOcrImporter(
    private val reader: SelectedImageOcrReader,
    private val capture: PhotoOcrTranscriptCapture,
) : SelectedPhotoOcrImporter {
    override suspend fun ingest(
        commandId: String,
        uriString: String?,
    ): SourceCaptureResult = when (val read = reader.read(uriString)) {
        is SelectedImageOcrReadResult.Lines -> {
            val transcript = OcrTranscript.encodeSpatial(read.values)
                ?: return failure(SourceCaptureError.CONTENT_TOO_LARGE)
            capture.ingest(commandId, PhotoOcrTranscriptEvidence(transcript))
        }

        SelectedImageOcrReadResult.Empty -> capture.ingest(
            commandId,
            PhotoOcrTranscriptEvidence(OcrTranscript.encodeEmpty()),
        )

        is SelectedImageOcrReadResult.Failure -> failure(read.error)
    }
}

private fun failed(error: SourceCaptureError): SelectedImageOcrReadResult =
    SelectedImageOcrReadResult.Failure(error)

private fun rejected(): SelectedImageOcrReadResult = failed(SourceCaptureError.PARSE_REJECTED)

private fun failure(error: SourceCaptureError) = SourceCaptureResult.Failure(
    error = error,
    diagnosticCode = null,
)
