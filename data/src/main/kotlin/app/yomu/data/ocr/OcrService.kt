package app.yomu.data.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import app.yomu.core.database.OcrCacheEntity
import app.yomu.core.database.YomuDatabase
import app.yomu.data.translation.awaitTask
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** A recognised text block in image pixel coordinates. */
@Serializable
data class OcrBlock(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    fun contains(x: Int, y: Int, slop: Int = 12) = x in (left - slop)..(right + slop) && y in (top - slop)..(bottom + slop)
}

/** On-device OCR with ML Kit (Latin, Japanese, Chinese and Korean scripts). Results are cached per page key. */
class OcrService(private val db: YomuDatabase) {
    private val recognizers = HashMap<String, TextRecognizer>()
    private val serializer = ListSerializer(OcrBlock.serializer())

    private fun recognizer(lang: String): TextRecognizer = synchronized(recognizers) {
        val script = when (lang) {
            "ja" -> "ja"
            "zh" -> "zh"
            "ko" -> "ko"
            else -> "latin"
        }
        recognizers.getOrPut(script) {
            when (script) {
                "ja" -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
                "zh" -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
                "ko" -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
                else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            }
        }
    }

    suspend fun recognize(bitmap: Bitmap, lang: String, cacheKey: String? = null): List<OcrBlock> {
        val key = cacheKey?.let { "$it|$lang" }
        key?.let { k -> db.ocrCache().get(k)?.let { return Json.decodeFromString(serializer, it.blocksJson) } }
        val result = recognizer(lang).process(InputImage.fromBitmap(bitmap, 0)).awaitTask()
        val blocks = result.textBlocks.mapNotNull { b ->
            val r: Rect = b.boundingBox ?: return@mapNotNull null
            val text = if (lang == "ja" || lang == "zh") b.text.replace("\n", "") else b.text.replace('\n', ' ')
            OcrBlock(text.trim(), r.left, r.top, r.right, r.bottom)
        }.filter { it.text.isNotBlank() }
        key?.let { db.ocrCache().put(OcrCacheEntity(it, Json.encodeToString(serializer, blocks), System.currentTimeMillis())) }
        return blocks
    }

    fun close() = synchronized(recognizers) {
        recognizers.values.forEach { it.close() }
        recognizers.clear()
    }
}
