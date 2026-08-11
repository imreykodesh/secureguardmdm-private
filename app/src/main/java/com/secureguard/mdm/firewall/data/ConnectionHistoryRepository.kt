package com.secureguard.mdm.firewall.data

import com.secureguard.mdm.firewall.model.ConnectionEvent
import com.secureguard.mdm.firewall.model.ConnectionHistory
import kotlinx.coroutines.flow.Flow

interface ConnectionHistoryRepository {
    fun observeRecent(limit: Int = 1_000): Flow<List<ConnectionHistory>>
    suspend fun recordBatch(events: List<ConnectionEvent>)
    suspend fun clear()
    suspend fun purge(olderThan: Long, maximumRecords: Int = 10_000)
}
