package com.example.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [ClipEntry::class, NoteEntry::class], version = 1, exportSchema = false)
abstract class SmartDotDatabase : RoomDatabase() {

    abstract fun smartDotDao(): SmartDotDao

    companion object {
        @Volatile
        private var INSTANCE: SmartDotDatabase? = null

        fun getDatabase(context: Context): SmartDotDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    SmartDotDatabase::class.java,
                    "smart_dot_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
