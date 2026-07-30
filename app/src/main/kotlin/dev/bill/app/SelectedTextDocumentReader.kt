package dev.bill.app

import android.content.ContentResolver
import android.net.Uri
import dev.bill.application.SelectedDelimitedStatementEvidence
import dev.bill.application.SelectedTextFileIngestionService
import dev.bill.application.SelectedTextFileEvidence
import dev.bill.application.SourceCaptureError
import dev.bill.source.contract.TextEvidenceMediaTypes
import dev.bill.source.genericdelimited.DelimitedReadLimits
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface SelectedTextDocumentReadResult {
    data class Success(val evidence: SelectedTextFileEvidence) : SelectedTextDocumentReadResult

    data class Failure(val error: SourceCaptureError) : SelectedTextDocumentReadResult
}

/**
 * Android boundary for one document selected by the user.
 *
 * Implementations must not persist the URI, filename, or display name. The caller consumes the
 * returned in-memory bytes immediately into the app-private evidence store.
 */
interface SelectedTextDocumentReader {
    suspend fun read(uriString: String): SelectedTextDocumentReadResult

    object Unavailable : SelectedTextDocumentReader {
        override suspend fun read(uriString: String): SelectedTextDocumentReadResult =
            SelectedTextDocumentReadResult.Failure(SourceCaptureError.PARSER_UNAVAILABLE)
    }
}

/**
 * Reads a bounded copy from a user-selected `content://` document without retaining URI access.
 */
class ContentResolverSelectedTextDocumentReader(
    private val contentResolver: ContentResolver,
) : SelectedTextDocumentReader {
    override suspend fun read(uriString: String): SelectedTextDocumentReadResult = withContext(Dispatchers.IO) {
        val uri = try {
            Uri.parse(uriString)
        } catch (_: RuntimeException) {
            return@withContext SelectedTextDocumentReadResult.Failure(
                SourceCaptureError.PARSE_REJECTED,
            )
        }
        if (uri.scheme != CONTENT_SCHEME) {
            return@withContext SelectedTextDocumentReadResult.Failure(
                SourceCaptureError.PARSE_REJECTED,
            )
        }

        try {
            val mediaType = contentResolver.getType(uri)
                ?.let(TextEvidenceMediaTypes::canonicalize)
                ?: return@withContext SelectedTextDocumentReadResult.Failure(
                    SourceCaptureError.PARSE_REJECTED,
                )
            if (mediaType !in TextEvidenceMediaTypes.USER_SELECTED_TEXT_FILE) {
                return@withContext SelectedTextDocumentReadResult.Failure(
                    SourceCaptureError.PARSE_REJECTED,
                )
            }

            val boundedRead = contentResolver.openInputStream(uri)
                ?.use { stream ->
                    BoundedDocumentReader.copy(
                        input = stream,
                        maxBytes = MAX_TEXT_FILE_BYTES,
                    )
                }
                ?: return@withContext SelectedTextDocumentReadResult.Failure(
                    SourceCaptureError.PARSE_REJECTED,
                )
            when (boundedRead) {
                is BoundedDocumentRead.Success -> SelectedTextDocumentReadResult.Success(
                    SelectedTextFileEvidence(mediaType = mediaType, bytes = boundedRead.bytes),
                )

                BoundedDocumentRead.TooLarge -> SelectedTextDocumentReadResult.Failure(
                    SourceCaptureError.CONTENT_TOO_LARGE,
                )
            }
        } catch (_: SecurityException) {
            SelectedTextDocumentReadResult.Failure(SourceCaptureError.PARSE_REJECTED)
        } catch (_: IOException) {
            SelectedTextDocumentReadResult.Failure(SourceCaptureError.PARSE_REJECTED)
        } catch (error: RuntimeException) {
            if (error is CancellationException) throw error
            SelectedTextDocumentReadResult.Failure(SourceCaptureError.PARSE_REJECTED)
        }
    }

    private companion object {
        const val CONTENT_SCHEME = "content"
        val MAX_TEXT_FILE_BYTES =
            SelectedTextFileIngestionService.MAX_SELECTED_TEXT_FILE_BYTES.toInt()
    }
}

enum class SelectedDelimitedStatementReadError {
    INVALID_DOCUMENT,
    UNSUPPORTED_MEDIA_TYPE,
    CONTENT_TOO_LARGE,
    READ_FAILED,
}

sealed interface SelectedDelimitedStatementReadResult {
    data class Success(
        val evidence: SelectedDelimitedStatementEvidence,
    ) : SelectedDelimitedStatementReadResult

    data class Failure(
        val error: SelectedDelimitedStatementReadError,
    ) : SelectedDelimitedStatementReadResult
}

/**
 * Android boundary for a structured CSV/TSV import.
 *
 * The selected URI and display name remain at this boundary. Only a bounded in-memory byte copy
 * and a canonical media type cross into the application layer.
 */
interface SelectedDelimitedStatementDocumentReader {
    suspend fun read(uriString: String): SelectedDelimitedStatementReadResult

    data object Unavailable : SelectedDelimitedStatementDocumentReader {
        override suspend fun read(uriString: String): SelectedDelimitedStatementReadResult =
            SelectedDelimitedStatementReadResult.Failure(
                SelectedDelimitedStatementReadError.READ_FAILED,
            )
    }
}

class ContentResolverSelectedDelimitedStatementDocumentReader(
    private val contentResolver: ContentResolver,
) : SelectedDelimitedStatementDocumentReader {
    override suspend fun read(
        uriString: String,
    ): SelectedDelimitedStatementReadResult = withContext(Dispatchers.IO) {
        val uri = try {
            Uri.parse(uriString)
        } catch (_: RuntimeException) {
            return@withContext failure(SelectedDelimitedStatementReadError.INVALID_DOCUMENT)
        }
        if (uri.scheme != CONTENT_SCHEME) {
            return@withContext failure(SelectedDelimitedStatementReadError.INVALID_DOCUMENT)
        }

        try {
            val mediaType = contentResolver.getType(uri)
                ?.let(TextEvidenceMediaTypes::canonicalize)
                ?: TextEvidenceMediaTypes.TEXT_PLAIN
            if (mediaType !in TextEvidenceMediaTypes.USER_SELECTED_TEXT_FILE) {
                return@withContext failure(
                    SelectedDelimitedStatementReadError.UNSUPPORTED_MEDIA_TYPE,
                )
            }
            val boundedRead = contentResolver.openInputStream(uri)
                ?.use { stream ->
                    BoundedDocumentReader.copy(
                        input = stream,
                        maxBytes = DelimitedReadLimits.DEFAULT_MAX_BYTES,
                    )
                }
                ?: return@withContext failure(
                    SelectedDelimitedStatementReadError.INVALID_DOCUMENT,
                )
            when (boundedRead) {
                is BoundedDocumentRead.Success -> SelectedDelimitedStatementReadResult.Success(
                    SelectedDelimitedStatementEvidence(
                        mediaType = mediaType,
                        bytes = boundedRead.bytes,
                    ),
                )

                BoundedDocumentRead.TooLarge -> failure(
                    SelectedDelimitedStatementReadError.CONTENT_TOO_LARGE,
                )
            }
        } catch (_: SecurityException) {
            failure(SelectedDelimitedStatementReadError.INVALID_DOCUMENT)
        } catch (_: IOException) {
            failure(SelectedDelimitedStatementReadError.READ_FAILED)
        } catch (error: RuntimeException) {
            if (error is CancellationException) throw error
            failure(SelectedDelimitedStatementReadError.READ_FAILED)
        }
    }

    private fun failure(
        error: SelectedDelimitedStatementReadError,
    ) = SelectedDelimitedStatementReadResult.Failure(error)

    private companion object {
        const val CONTENT_SCHEME = "content"
    }
}

internal sealed interface BoundedDocumentRead {
    data class Success(val bytes: ByteArray) : BoundedDocumentRead

    data object TooLarge : BoundedDocumentRead
}

/** Pure bounded-copy helper kept separate from Android I/O for local regression tests. */
internal object BoundedDocumentReader {
    fun copy(input: InputStream, maxBytes: Int): BoundedDocumentRead {
        require(maxBytes > 0)
        val buffer = ByteArray(maxBytes)
        var count = 0
        try {
            while (count < maxBytes) {
                val read = input.read(buffer, count, maxBytes - count)
                if (read < 0) {
                    return BoundedDocumentRead.Success(buffer.copyOf(count))
                }
                if (read == 0) {
                    val next = input.read()
                    if (next < 0) {
                        return BoundedDocumentRead.Success(buffer.copyOf(count))
                    }
                    buffer[count] = next.toByte()
                    count += 1
                } else {
                    count += read
                }
            }
            return if (input.read() < 0) {
                BoundedDocumentRead.Success(buffer.copyOf())
            } else {
                BoundedDocumentRead.TooLarge
            }
        } finally {
            buffer.fill(0)
        }
    }
}
