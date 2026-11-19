package com.example.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "clipboard_entries")
data class ClipEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)
