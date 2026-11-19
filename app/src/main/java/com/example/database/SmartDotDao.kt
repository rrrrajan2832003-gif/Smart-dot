package com.example.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SmartDotDao {

    // CLIPBOARD ACTIONS
    @Query("SELECT * FROM clipboard_entries ORDER BY timestamp DESC LIMIT 30")
    fun getAllClipsFlow(): Flow<List<ClipEntry>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertClip(entry: ClipEntry)

    @Query("DELETE FROM clipboard_entries WHERE id = :id")
    suspend fun deleteClipById(id: Long)

    @Query("DELETE FROM clipboard_entries")
    suspend fun clearAllClips()

    // NOTES ACTIONS
    @Query("SELECT * FROM notes ORDER BY timestamp DESC")
    fun getAllNotesFlow(): Flow<List<NoteEntry>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNote(note: NoteEntry)

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun deleteNoteById(id: Long)
}
