package com.screenreader.ocr.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ocr_texts")
data class OcrTextEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val wordCount: Int = 0,
    val language: String = "",
    val sessionId: String = ""
)
