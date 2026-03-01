package com.screenreader.ocr.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class OcrProcessor {

    private val latinRecognizer: TextRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private val japaneseRecognizer: TextRecognizer =
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())

    enum class OcrLanguage {
        LATIN, JAPANESE, AUTO
    }

    /**
     * Bitmapからテキストを認識する
     */
    suspend fun recognizeText(
        bitmap: Bitmap,
        language: OcrLanguage = OcrLanguage.AUTO
    ): OcrResult {
        val image = InputImage.fromBitmap(bitmap, 0)

        return when (language) {
            OcrLanguage.LATIN -> recognizeWithRecognizer(image, latinRecognizer)
            OcrLanguage.JAPANESE -> recognizeWithRecognizer(image, japaneseRecognizer)
            OcrLanguage.AUTO -> {
                // まず日本語で試行し、結果が貧弱ならラテン文字で再試行
                val japResult = recognizeWithRecognizer(image, japaneseRecognizer)
                if (japResult.text.isNotBlank()) {
                    japResult
                } else {
                    recognizeWithRecognizer(image, latinRecognizer)
                }
            }
        }
    }

    /**
     * オーバーレイUIのテキストかどうかを判定
     */
    private fun isOverlayText(text: String): Boolean {
        val trimmed = text.trim()
        val uiPatterns = listOf(
            "待機中", "キャプチャ中", "一時停止中", "キャプチャ領域"
        )
        if (uiPatterns.any { trimmed.contains(it) }) {
            return true
        }
        if (trimmed.contains("保存:") && trimmed.contains("スキップ:")) {
            return true
        }
        return false
    }

    private suspend fun recognizeWithRecognizer(
        image: InputImage,
        recognizer: TextRecognizer
    ): OcrResult = suspendCancellableCoroutine { cont ->
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                // Extract ALL lines from ALL blocks with their bounding boxes
                val allLines = mutableListOf<LineWithRect>()
                for (block in visionText.textBlocks) {
                    for (line in block.lines) {
                        if (!isOverlayText(line.text)) {
                            allLines.add(LineWithRect(line.text, line.boundingBox))
                        }
                    }
                }

                // Sort lines for Japanese vertical reading order
                // Primary: right to left (x descending), Secondary: top to bottom (y ascending)
                val sortedLines = allLines.sortedWith(
                    compareByDescending<LineWithRect> {
                        it.rect?.right ?: 0
                    }.thenBy {
                        it.rect?.top ?: 0
                    }
                )

                val sortedText = sortedLines.joinToString("\n") { it.text }

                val blocks = sortedLines.map { line ->
                    TextBlock(
                        text = line.text,
                        boundingBox = line.rect,
                        lines = listOf(line.text)
                    )
                }

                cont.resume(
                    OcrResult(
                        text = sortedText,
                        blocks = blocks,
                        confidence = if (blocks.isNotEmpty()) 1.0f else 0.0f
                    )
                )
            }
            .addOnFailureListener { e ->
                cont.resumeWithException(e)
            }
    }

    private data class LineWithRect(val text: String, val rect: android.graphics.Rect?)

    fun close() {
        latinRecognizer.close()
        japaneseRecognizer.close()
    }
}

data class OcrResult(
    val text: String,
    val blocks: List<TextBlock>,
    val confidence: Float
)

data class TextBlock(
    val text: String,
    val boundingBox: android.graphics.Rect?,
    val lines: List<String>
)
