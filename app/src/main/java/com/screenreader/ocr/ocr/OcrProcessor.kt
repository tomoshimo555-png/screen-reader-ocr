package com.screenreader.ocr.ocr

import android.graphics.Bitmap
import android.util.Log
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class OcrProcessor(private val apiKey: String) {

    private val generativeModel by lazy {
        GenerativeModel(
            modelName = "gemini-1.5-flash",
            apiKey = apiKey
        )
    }

    /**
     * Bitmapからテキストを認識する (Gemini API利用)
     */
    suspend fun recognizeText(
        bitmap: Bitmap
    ): OcrResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            Log.e("OcrProcessor", "API Key is empty. Cannot process OCR.")
            return@withContext OcrResult("", emptyList(), 0f)
        }

        try {
            val prompt = "画像内のテキストを正確にすべて文字起こししてください。\n縦書きの日本語も正確に読み取って横書きテキストに変換してください。\n出力は読み取ったテキスト文字列のみとし、余計な前置き、解説文、マークダウンのコードブロック指定 (```text など) は一切含めないでください。"
            
            val response = generativeModel.generateContent(
                content {
                    image(bitmap)
                    text(prompt)
                }
            )

            // 余計なマークダウン記法が返ってきた場合のサニタイズ
            var resultText = response.text?.trim() ?: ""
            if (resultText.startsWith("```")) {
                resultText = resultText.substringAfter("\n").substringBeforeLast("```").trim()
            }

            Log.d("OcrProcessor", "Gemini OCR result length: ${resultText.length}")

            val blocks = if (resultText.isNotEmpty()) {
                listOf(
                    TextBlock(
                        text = resultText,
                        boundingBox = null,
                        lines = resultText.split("\n")
                    )
                )
            } else {
                emptyList()
            }

            OcrResult(
                text = resultText,
                blocks = blocks,
                confidence = 1.0f
            )
        } catch (e: Exception) {
            Log.e("OcrProcessor", "Gemini API recognition failed", e)
            OcrResult("", emptyList(), 0f)
        }
    }

    fun close() {
        // Nothing to close for GenerativeModel
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
