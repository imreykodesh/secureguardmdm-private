package com.secureguard.mdm.ministore.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface MiniStoreUpdateCheckDao {
    @Query("SELECT * FROM mini_store_update_check ORDER BY package_name")
    fun observeAll(): Flow<List<MiniStoreUpdateCheckEntity>>

    @Query("SELECT * FROM mini_store_update_check WHERE package_name = :packageName LIMIT 1")
    suspend fun get(packageName: String): MiniStoreUpdateCheckEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MiniStoreUpdateCheckEntity)

    @Query("DELETE FROM mini_store_update_check WHERE package_name = :packageName")
    suspend fun delete(packageName: String)

    @Query("SELECT package_name FROM mini_store_update_check")
    suspend fun getPackageNames(): List<String>

    @Transaction
    suspend fun retainOnly(packageNames: Set<String>) {
        getPackageNames().filterNot(packageNames::contains).forEach { delete(it) }
    }
}
