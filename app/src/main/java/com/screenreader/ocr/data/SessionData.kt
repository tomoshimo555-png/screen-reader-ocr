package com.screenreader.ocr.data

/**
 * セッションフォルダを表すデータクラス
 */
data class SessionFolder(
    val folderName: String,        // e.g. "2026-03-04_23-52-09"
    val createdAt: Long,           // timestamp parsed from folder name
    val textFiles: List<SessionTextFile>
)

/**
 * セッション内のテキストファイルを表すデータクラス
 */
data class SessionTextFile(
    val fileName: String,          // e.g. "001.txt"
    val text: String,
    val uri: android.net.Uri?
)
