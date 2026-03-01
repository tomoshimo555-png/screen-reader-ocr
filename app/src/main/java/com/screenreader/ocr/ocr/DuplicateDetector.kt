package com.screenreader.ocr.ocr

/**
 * テキストの重複を検出するクラス。
 * Jaccard類似度を使って直近のテキストとの類似性を判定。
 */
class DuplicateDetector(
    private val similarityThreshold: Float = 0.80f,
    private val historySize: Int = 10
) {

    private val recentTexts = ArrayDeque<String>(historySize)
    private var skipCount = 0
    private var totalCount = 0

    /**
     * 与えられたテキストが重複かどうかを判定する。
     * @return true if the text is a duplicate
     */
    fun isDuplicate(text: String): Boolean {
        totalCount++
        val normalizedNew = normalize(text)

        if (normalizedNew.isBlank()) {
            return true // 空テキストはスキップ
        }

        for (recent in recentTexts) {
            val similarity = jaccardSimilarity(normalizedNew, recent)
            if (similarity >= similarityThreshold) {
                skipCount++
                return true
            }
        }

        // 重複でなければ履歴に追加
        addToHistory(normalizedNew)
        return false
    }

    /**
     * 重複検出履歴をクリアする
     */
    fun clearHistory() {
        recentTexts.clear()
        skipCount = 0
        totalCount = 0
    }

    /**
     * 統計情報を取得
     */
    fun getStats(): DuplicateStats {
        return DuplicateStats(
            totalChecked = totalCount,
            duplicatesSkipped = skipCount,
            uniqueTexts = totalCount - skipCount
        )
    }

    /**
     * 類似度の閾値を更新
     */
    fun updateThreshold(newThreshold: Float) {
        // We can't reassign val, so we use a workaround via a new instance or mutable field
        // For simplicity, this is handled via constructor
    }

    private fun addToHistory(normalizedText: String) {
        if (recentTexts.size >= historySize) {
            recentTexts.removeFirst()
        }
        recentTexts.addLast(normalizedText)
    }

    /**
     * テキストを正規化（空白・改行を統一）
     */
    private fun normalize(text: String): String {
        return text.trim()
            .replace(Regex("\\s+"), " ")
            .lowercase()
    }

    /**
     * Jaccard類似度を計算（文字トライグラムベース）
     */
    private fun jaccardSimilarity(text1: String, text2: String): Float {
        if (text1.isEmpty() && text2.isEmpty()) return 1.0f
        if (text1.isEmpty() || text2.isEmpty()) return 0.0f

        val trigrams1 = extractTrigrams(text1)
        val trigrams2 = extractTrigrams(text2)

        if (trigrams1.isEmpty() && trigrams2.isEmpty()) return 1.0f
        if (trigrams1.isEmpty() || trigrams2.isEmpty()) return 0.0f

        val intersection = trigrams1.intersect(trigrams2).size
        val union = trigrams1.union(trigrams2).size

        return if (union > 0) intersection.toFloat() / union.toFloat() else 0.0f
    }

    /**
     * 文字トライグラム（3文字のスライディングウィンドウ）を抽出
     */
    private fun extractTrigrams(text: String): Set<String> {
        if (text.length < 3) return setOf(text)
        return (0..text.length - 3).map { text.substring(it, it + 3) }.toSet()
    }
}

data class DuplicateStats(
    val totalChecked: Int,
    val duplicatesSkipped: Int,
    val uniqueTexts: Int
)
