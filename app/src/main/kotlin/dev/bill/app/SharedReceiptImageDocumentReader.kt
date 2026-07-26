package dev.bill.app

import android.content.ContentResolver
import android.net.Uri
import dev.bill.application.SharedReceiptImageEvidence
import dev.bill.application.SharedReceiptImageIngestionService
import dev.bill.application.SourceCaptureError
import dev.bill.source.contract.ImageEvidenceMediaTypes
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface SharedReceiptImageReadResult {
    data class Success(val evidence: SharedReceiptImageEvidence) : SharedReceiptImageReadResult

    data class Failure(val error: SourceCaptureError) : SharedReceiptImageReadResult
}

/**
 * Android boundary for one image explicitly shared through the system Sharesheet.
 *
 * It accepts a temporary `content://` grant only for this call, never persists the URI, filename,
 * or media-library permission, and copies at most one bounded PNG into memory on an IO dispatcher.
 */
interface SharedReceiptImageDocumentReader {
    suspend fun read(
        uriString: String?,
        declaredMediaType: String?,
    ): SharedReceiptImageReadResult

    data object Unavailable : SharedReceiptImageDocumentReader {
        override suspend fun read(
            uriString: String?,
            declaredMediaType: String?,
        ): SharedReceiptImageReadResult = SharedReceiptImageReadResult.Failure(
            SourceCaptureError.PARSER_UNAVAILABLE,
        )
    }
}

class ContentResolverSharedReceiptImageDocumentReader(
    private val contentResolver: ContentResolver,
) : SharedReceiptImageDocumentReader {
    override suspend fun read(
        uriString: String?,
        declaredMediaType: String?,
    ): SharedReceiptImageReadResult = withContext(Dispatchers.IO) {
        val uri = try {
            uriString?.let(Uri::parse)
        } catch (_: RuntimeException) {
            null
        } ?: return@withContext failure()
        if (uri.scheme != CONTENT_SCHEME) return@withContext failure()

        val declared = declaredMediaType
            ?.let(ImageEvidenceMediaTypes::canonicalize)
            ?: return@withContext failure()
        if (declared != ImageEvidenceMediaTypes.PNG && declared != ImageEvidenceMediaTypes.IMAGE_WILDCARD) {
            return@withContext failure()
        }

        try {
            val resolved = contentResolver.getType(uri)
                ?.let(ImageEvidenceMediaTypes::canonicalize)
            val mediaType = when {
                resolved == ImageEvidenceMediaTypes.PNG -> resolved
                resolved == null && declared == ImageEvidenceMediaTypes.PNG -> declared
                else -> return@withContext failure()
            }
            val boundedRead = contentResolver.openInputStream(uri)
                ?.use { stream ->
                    BoundedDocumentReader.copy(
                        input = stream,
                        maxBytes = MAX_SHARED_RECEIPT_IMAGE_BYTES,
                    )
                }
                ?: return@withContext failure()
            when (boundedRead) {
                is BoundedDocumentRead.Success -> SharedReceiptImageReadResult.Success(
                    SharedReceiptImageEvidence(mediaType = mediaType, bytes = boundedRead.bytes),
                )

                BoundedDocumentRead.TooLarge -> SharedReceiptImageReadResult.Failure(
                    SourceCaptureError.CONTENT_TOO_LARGE,
                )
            }
        } catch (_: SecurityException) {
            failure()
        } catch (_: IOException) {
            failure()
        } catch (_: RuntimeException) {
            failure()
        }
    }

    private fun failure() = SharedReceiptImageReadResult.Failure(SourceCaptureError.PARSE_REJECTED)

    private companion object {
        const val CONTENT_SCHEME = "content"
        val MAX_SHARED_RECEIPT_IMAGE_BYTES =
            SharedReceiptImageIngestionService.MAX_SHARED_RECEIPT_IMAGE_BYTES.toInt()
    }
}
