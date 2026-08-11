package com.secureguard.mdm.firewall.data

import com.secureguard.mdm.firewall.model.ConnectionDecision
import com.secureguard.mdm.firewall.model.ConnectionEvent
import com.secureguard.mdm.firewall.model.ConnectionHistory
import com.secureguard.mdm.firewall.model.FirewallProtocol
import com.secureguard.mdm.firewall.model.MetadataSource
import com.secureguard.mdm.firewall.model.NetworkType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConnectionHistoryRepositoryImpl @Inject constructor(
    private val dao: FirewallDao,
) : ConnectionHistoryRepository {
    override fun observeRecent(limit: Int): Flow<List<ConnectionHistory>> =
        dao.observeRecentHistory(limit).map { rows -> rows.map { it.toModel() } }

    override suspend fun recordBatch(events: List<ConnectionEvent>) {
        if (events.isEmpty()) return
        dao.recordHistoryBatch(events.map { it.toEntity() })
    }

    override suspend fun clear() = dao.clearHistory()

    override suspend fun purge(olderThan: Long, maximumRecords: Int) {
        dao.deleteHistoryOlderThan(olderThan)
        dao.trimHistory(maximumRecords)
    }

    private fun ConnectionEvent.toEntity(): ConnectionHistoryEntity {
        val normalizedDestination = domain ?: destinationIp
        return ConnectionHistoryEntity(
            packageName = packageName,
            uid = uid,
            normalizedDestination = normalizedDestination,
            domain = domain,
            destinationIp = destinationIp,
            destinationPort = destinationPort,
            protocol = protocol.name,
            firstSeenAt = timestamp,
            lastSeenAt = timestamp,
            connectionCount = 1,
            lastDecision = decision.name,
            decisionReason = decisionReason,
            metadataSource = metadataSource.name,
            networkType = networkType.name,
        )
    }

    private fun ConnectionHistoryEntity.toModel() = ConnectionHistory(
        id = id,
        packageName = packageName,
        uid = uid,
        normalizedDestination = normalizedDestination,
        domain = domain,
        destinationIp = destinationIp,
        destinationPort = destinationPort,
        protocol = enumValueOrDefault(protocol, FirewallProtocol.ANY),
        firstSeenAt = firstSeenAt,
        lastSeenAt = lastSeenAt,
        connectionCount = connectionCount,
        lastDecision = enumValueOrDefault(lastDecision, ConnectionDecision.ALLOWED),
        decisionReason = decisionReason,
        metadataSource = enumValueOrDefault(metadataSource, MetadataSource.IP_ONLY),
        networkType = enumValueOrDefault(networkType, NetworkType.OTHER),
    )

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String, default: T): T =
        runCatching { enumValueOf<T>(value) }.getOrDefault(default)
}
