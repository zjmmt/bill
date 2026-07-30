package dev.bill.ocr.paddle

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.paddle.ocr.EngineConfig
import com.paddle.ocr.PaddleOCR
import com.paddle.ocr.PaddleOCRConfig
import com.paddle.ocr.util.OpenCVUtils
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BundledPaddleOcrInstrumentedTest {
    @Test
    fun bundledModelRecognizesChineseEnglishAndJapanesePaymentText() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue("OpenCV native library must load", OpenCVUtils.init(context))

        val bitmap = multilingualPaymentFixture()
        val ocr = PaddleOCR.create(
            context = context,
            config = PaddleOCRConfig(
                detMaxSideLimit = 1_600,
                detMaxCandidates = 512,
                recScoreThresh = 0.35f,
                recBatchSize = 1,
            ),
            engineConfig = EngineConfig(numThreads = 2),
            detModelAssetPath = "ocr/ppocrv6-small/det/inference.onnx",
            recModelAssetPath = "ocr/ppocrv6-small/rec/inference.onnx",
            recConfigAssetPath = "ocr/ppocrv6-small/rec/inference.yml",
        )
        try {
            val text = ocr.recognize(bitmap).results.joinToString("\n") { it.text }
            assertTrue("Chinese fixture was not recognized: $text", "付款成功" in text)
            assertTrue("English fixture was not recognized: $text", "Payment complete" in text)
            assertTrue("Japanese fixture was not recognized: $text", "受取完了" in text)
            assertTrue("CNY amount was not recognized: $text", "123.45" in text)
            assertTrue("USD amount was not recognized: $text", "67.89" in text)
            assertTrue("JPY-like fixture amount was not recognized: $text", "987" in text)
        } finally {
            ocr.release()
            bitmap.recycle()
        }
    }

    private fun multilingualPaymentFixture(): Bitmap {
        val bitmap = Bitmap.createBitmap(1_200, 720, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 72f
        }
        canvas.drawText("付款成功 ¥123.45", 56f, 170f, paint)
        canvas.drawText("Payment complete USD 67.89", 56f, 360f, paint)
        canvas.drawText("受取完了 ￥987", 56f, 550f, paint)
        return bitmap
    }
}
