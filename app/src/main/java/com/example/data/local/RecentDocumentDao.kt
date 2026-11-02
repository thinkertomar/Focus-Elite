package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.model.RecentDocumentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RecentDocumentDao {
    @Query("SELECT * FROM recent_documents ORDER BY lastOpened DESC LIMIT 30")
    fun getRecentDocuments(): Flow<List<RecentDocumentEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecent(document: RecentDocumentEntity): Long

    @Query("UPDATE recent_documents SET isStarred = :isStarred WHERE uriString = :uriString")
    suspend fun updateStarred(uriString: String, isStarred: Boolean)

    @Query("DELETE FROM recent_documents WHERE uriString = :uriString")
    suspend fun deleteByUri(uriString: String)

    @Query("DELETE FROM recent_documents")
    suspend fun clearAll()
}
