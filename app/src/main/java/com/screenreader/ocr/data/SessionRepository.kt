package com.screenreader.ocr.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Documents/ScreenReaderOCR/ 配下のセッションフォルダを管理するリポジトリ。
 * MediaStore API を使用してファイルの読み書きを行う。
 */
class SessionRepository(private val context: Context) {

    companion object {
        private const val TAG = "SessionRepository"
        private const val BASE_DIR = "ScreenReaderOCR"
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())
    }

    /**
     * 新しいセッションフォルダ名を生成する（タイムスタンプベース）
     */
    fun generateSessionId(): String {
        return DATE_FORMAT.format(System.currentTimeMillis())
    }

    /**
     * セッションフォルダにテキストファイルを保存する
     * @return 保存したファイルのUri（失敗時はnull）
     */
    fun saveText(sessionId: String, fileIndex: Int, text: String): Uri? {
        val fileName = String.format("%03d.txt", fileIndex)
        val relativePath = Environment.DIRECTORY_DOCUMENTS + "/$BASE_DIR/$sessionId"

        val contentValues = ContentValues().apply {
            put(MediaStore.Files.FileColumns.DISPLAY_NAME, fileName)
            put(MediaStore.Files.FileColumns.MIME_TYPE, "text/plain")
            put(MediaStore.Files.FileColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.Files.FileColumns.IS_PENDING, 1)
        }

        return try {
            val uri = context.contentResolver.insert(
                MediaStore.Files.getContentUri("external"),
                contentValues
            )
            if (uri != null) {
                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(text.toByteArray(Charsets.UTF_8))
                }
                // Mark as complete
                contentValues.clear()
                contentValues.put(MediaStore.Files.FileColumns.IS_PENDING, 0)
                context.contentResolver.update(uri, contentValues, null, null)
                Log.d(TAG, "Saved text: $relativePath/$fileName")
            }
            uri
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save text: ${e.message}", e)
            null
        }
    }

    /**
     * 全セッションフォルダの一覧を取得する（新しい順）
     */
    fun getAllSessions(): List<SessionFolder> {
        val sessions = mutableMapOf<String, MutableList<SessionTextFile>>()

        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.RELATIVE_PATH,
            MediaStore.Files.FileColumns.DATE_ADDED
        )

        val selection = "${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.Files.FileColumns.MIME_TYPE} = ?"
        val selectionArgs = arrayOf(
            "%${Environment.DIRECTORY_DOCUMENTS}/$BASE_DIR/%",
            "text/plain"
        )

        val sortOrder = "${MediaStore.Files.FileColumns.DISPLAY_NAME} ASC"

        try {
            context.contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.RELATIVE_PATH)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val fileName = cursor.getString(nameColumn)
                    val relativePath = cursor.getString(pathColumn)

                    // Extract session folder name from path
                    // Path format: Documents/ScreenReaderOCR/{sessionId}/
                    val pathParts = relativePath.trimEnd('/').split("/")
                    val sessionId = pathParts.lastOrNull() ?: continue

                    // Skip if it's the base dir itself
                    if (sessionId == BASE_DIR) continue

                    val uri = Uri.withAppendedPath(
                        MediaStore.Files.getContentUri("external"),
                        id.toString()
                    )

                    // Read text content
                    val text = readTextFromUri(uri)

                    val textFile = SessionTextFile(
                        fileName = fileName,
                        text = text,
                        uri = uri
                    )

                    sessions.getOrPut(sessionId) { mutableListOf() }.add(textFile)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading sessions: ${e.message}", e)
        }

        return sessions.map { (sessionId, files) ->
            val createdAt = try {
                DATE_FORMAT.parse(sessionId)?.time ?: 0L
            } catch (e: Exception) {
                0L
            }
            SessionFolder(
                folderName = sessionId,
                createdAt = createdAt,
                textFiles = files.sortedBy { it.fileName }
            )
        }.sortedByDescending { it.createdAt }
    }

    /**
     * セッションフォルダ内の全ファイルを削除する
     */
    fun deleteSession(sessionId: String) {
        val selection = "${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.Files.FileColumns.MIME_TYPE} = ?"
        val selectionArgs = arrayOf(
            "%${Environment.DIRECTORY_DOCUMENTS}/$BASE_DIR/$sessionId/%",
            "text/plain"
        )

        try {
            val deleted = context.contentResolver.delete(
                MediaStore.Files.getContentUri("external"),
                selection,
                selectionArgs
            )
            Log.d(TAG, "Deleted $deleted files from session $sessionId")
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting session: ${e.message}", e)
        }
    }

    /**
     * 特定のテキストファイルを削除する
     */
    fun deleteTextFile(uri: Uri) {
        try {
            context.contentResolver.delete(uri, null, null)
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting file: ${e.message}", e)
        }
    }

    /**
     * 全セッションを削除する
     */
    fun deleteAllSessions() {
        val selection = "${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.Files.FileColumns.MIME_TYPE} = ?"
        val selectionArgs = arrayOf(
            "%${Environment.DIRECTORY_DOCUMENTS}/$BASE_DIR/%",
            "text/plain"
        )

        try {
            val deleted = context.contentResolver.delete(
                MediaStore.Files.getContentUri("external"),
                selection,
                selectionArgs
            )
            Log.d(TAG, "Deleted all $deleted files")
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting all sessions: ${e.message}", e)
        }
    }

    private fun readTextFromUri(uri: Uri): String {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).readText()
            } ?: ""
        } catch (e: Exception) {
            Log.e(TAG, "Error reading file: ${e.message}", e)
            ""
        }
    }
}
