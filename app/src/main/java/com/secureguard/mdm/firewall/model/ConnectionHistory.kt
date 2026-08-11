package com.secureguard.mdm.firewall.model

enum class ConnectionDecision { ALLOWED, BLOCKED, MONITORED }
enum class MetadataSource { DNS, TLS_SNI, IP_ONLY }
enum class NetworkType { WIFI, CELLULAR, ETHERNET, OTHER }

data class ConnectionEvent(
    val packageName: String,
    val uid: Int,
    val domain: String?,
    val destinationIp: String,
    val destinationPort: Int,
    val protocol: FirewallProtocol,
    val decision: ConnectionDecision,
    val decisionReason: String,
    val metadataSource: MetadataSource,
    val networkType: NetworkType,
    val timestamp: Long = System.currentTimeMillis(),
)

data class ConnectionHistory(
    val id: Long,
    val packageName: String,
    val uid: Int,
    val normalizedDestination: String,
    val domain: String?,
    val destinationIp: String,
    val destinationPort: Int,
    val protocol: FirewallProtocol,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val connectionCount: Long,
    val lastDecision: ConnectionDecision,
    val decisionReason: String,
    val metadataSource: MetadataSource,
    val networkType: NetworkType,
)
