package com.screenreader.ocr.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface OcrTextDao {

    @Query("SELECT * FROM ocr_texts ORDER BY timestamp DESC")
    fun getAllTexts(): Flow<List<OcrTextEntity>>

    @Query("SELECT * FROM ocr_texts WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getTextsBySession(sessionId: String): Flow<List<OcrTextEntity>>

    @Query("SELECT * FROM ocr_texts ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentTexts(limit: Int): List<OcrTextEntity>

    @Query("SELECT * FROM ocr_texts WHERE text LIKE '%' || :query || '%' ORDER BY timestamp DESC")
    fun searchTexts(query: String): Flow<List<OcrTextEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(text: OcrTextEntity): Long

    @Delete
    suspend fun delete(text: OcrTextEntity)

    @Query("DELETE FROM ocr_texts")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM ocr_texts")
    suspend fun getCount(): Int

    @Query("SELECT COUNT(*) FROM ocr_texts WHERE sessionId = :sessionId")
    suspend fun getSessionCount(sessionId: String): Int
}
