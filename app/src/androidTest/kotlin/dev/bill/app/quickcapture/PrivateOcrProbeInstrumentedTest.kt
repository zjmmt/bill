package dev.bill.app.quickcapture

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.bill.source.contract.CaptureMethod
import dev.bill.source.contract.CaptureScopeId
import dev.bill.source.contract.EvidenceHash
import dev.bill.source.contract.EvidenceInput
import dev.bill.source.contract.ParseResult
import dev.bill.source.contract.PayloadId
import dev.bill.source.contract.RawEvent
import dev.bill.source.contract.RawEventId
import dev.bill.source.contract.SourceFamily
import dev.bill.source.genericphotoocr.GenericPhotoOcrParser
import dev.bill.source.genericphotoocr.OcrTranscript
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in local probe for ignored, user-provided images.
 *
 * Normal CI runs skip this test. A developer may temporarily package a private asset and pass only
 * its asset name, expected money token and expected minor units as instrumentation arguments. No
 * recognized transcript is logged or committed.
 */
@RunWith(AndroidJUnit4::class)
class PrivateOcrProbeInstrumentedTest {
    @Test
    fun privateAssetCarriesExpectedAmountThroughBundledOcrAndParser() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val assetName = arguments.getString(ARG_ASSET_NAME)
        val expectedToken = arguments.getString(ARG_EXPECTED_TOKEN)
        val expectedSemantic = arguments.getString(ARG_EXPECTED_SEMANTIC)
        val expectedMinorUnits = arguments.getString(ARG_EXPECTED_MINOR_UNITS)?.toLongOrNull()
        assumeTrue(assetName != null && expectedToken != null && expectedMinorUnits != null)

        val testContext = instrumentation.targetContext.createPackageContext(
            instrumentation.componentName.packageName,
            0,
        )
        val bitmap = testContext.assets.open(requireNotNull(assetName)).use { input ->
            requireNotNull(BitmapFactory.decodeStream(input))
        }
        try {
            val recognized = BundledLocalOcrEngine.recognize(
                instrumentation.targetContext,
                bitmap,
            )
            assertTrue(recognized is LocalOcrResult.Lines)
            val lines = (recognized as LocalOcrResult.Lines).values
            assertTrue("Expected money token was not recognized", lines.any {
                requireNotNull(expectedToken) in it.value
            })
            if (expectedSemantic != null) {
                assertTrue("Expected completion semantic was not recognized", lines.any {
                    expectedSemantic in it.value
                })
            }
            val evidence = requireNotNull(OcrTranscript.encodeSpatial(lines))
            try {
                val result = GenericPhotoOcrParser().parse(
                    rawEvent(evidence),
                    EvidenceInput(OcrTranscript.MEDIA_TYPE, evidence),
                ) as ParseResult.NeedsUserReview
                assertEquals(
                    requireNotNull(expectedMinorUnits),
                    result.candidate?.amount?.value?.minorUnits,
                )
            } finally {
                evidence.fill(0)
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun rawEvent(evidence: ByteArray) = RawEvent(
        id = RawEventId("private-local-ocr-probe"),
        sourceFamily = SourceFamily.GENERIC,
        connectorId = GenericPhotoOcrParser().identity.connectorId,
        captureMethod = CaptureMethod.PHOTO_OCR,
        captureScope = CaptureScopeId("private-local-probe"),
        contentHash = EvidenceHash.fromBytes(evidence),
        capturedAt = Instant.parse("2026-08-02T00:00:00Z"),
        payloadId = PayloadId("private-local-ocr-probe-payload"),
        payloadSizeBytes = evidence.size.toLong(),
    )

    private companion object {
        const val ARG_ASSET_NAME = "privateOcrAsset"
        const val ARG_EXPECTED_TOKEN = "privateOcrExpectedToken"
        const val ARG_EXPECTED_SEMANTIC = "privateOcrExpectedSemantic"
        const val ARG_EXPECTED_MINOR_UNITS = "privateOcrExpectedMinorUnits"
    }
}
