package com.secureguard.mdm.firewall.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface FirewallDao {
    @Query("SELECT * FROM firewall_app_policy ORDER BY package_name")
    fun observePolicies(): Flow<List<FirewallAppPolicyEntity>>

    @Query("SELECT * FROM firewall_rule WHERE enabled = 1 ORDER BY priority DESC, id ASC")
    fun observeEnabledRules(): Flow<List<FirewallRuleEntity>>

    @Query("SELECT * FROM connection_history ORDER BY last_seen_at DESC LIMIT :limit")
    fun observeRecentHistory(limit: Int): Flow<List<ConnectionHistoryEntity>>

    @Query("SELECT * FROM firewall_app_policy")
    suspend fun getPolicies(): List<FirewallAppPolicyEntity>

    @Query("SELECT * FROM firewall_rule WHERE enabled = 1 ORDER BY priority DESC, id ASC")
    suspend fun getEnabledRules(): List<FirewallRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPolicy(policy: FirewallAppPolicyEntity)

    @Query(
        """SELECT id FROM firewall_rule
           WHERE package_name = :packageName AND rule_type = :ruleType AND action = :action
             AND value = :value AND protocol = :protocol
             AND port_start IS :portStart AND port_end IS :portEnd
           LIMIT 1""",
    )
    suspend fun findRuleId(
        packageName: String,
        ruleType: String,
        action: String,
        value: String,
        protocol: String,
        portStart: Int?,
        portEnd: Int?,
    ): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRule(rule: FirewallRuleEntity): Long

    @Query("DELETE FROM firewall_app_policy WHERE package_name = :packageName")
    suspend fun deletePolicy(packageName: String)

    @Query("DELETE FROM firewall_rule WHERE package_name = :packageName")
    suspend fun deleteRulesForPackage(packageName: String)

    @Query("DELETE FROM firewall_rule WHERE id = :ruleId")
    suspend fun deleteRule(ruleId: Long)

    @Query(
        """UPDATE connection_history
           SET last_seen_at = :lastSeenAt,
               connection_count = connection_count + :increment,
               domain = :domain,
               destination_ip = :destinationIp,
               uid = :uid,
               decision_reason = :decisionReason,
               metadata_source = :metadataSource,
               network_type = :networkType
           WHERE package_name = :packageName
             AND normalized_destination = :normalizedDestination
             AND destination_port = :destinationPort
             AND protocol = :protocol
             AND last_decision = :lastDecision""",
    )
    suspend fun updateHistoryAggregate(
        packageName: String,
        normalizedDestination: String,
        destinationPort: Int,
        protocol: String,
        lastDecision: String,
        lastSeenAt: Long,
        increment: Long,
        domain: String?,
        destinationIp: String,
        uid: Int,
        decisionReason: String,
        metadataSource: String,
        networkType: String,
    ): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertHistory(history: ConnectionHistoryEntity): Long

    @Query("DELETE FROM connection_history")
    suspend fun clearHistory()

    @Query("DELETE FROM connection_history WHERE last_seen_at < :olderThan")
    suspend fun deleteHistoryOlderThan(olderThan: Long)

    @Query("DELETE FROM connection_history WHERE id NOT IN (SELECT id FROM connection_history ORDER BY last_seen_at DESC LIMIT :maximumRecords)")
    suspend fun trimHistory(maximumRecords: Int)

    @Transaction
    suspend fun ensurePackageForCapture(packageName: String, now: Long) {
        val existing = getPolicies().firstOrNull { it.packageName == packageName }
        val captureMode = when (existing?.policyMode) {
            null, "DISABLED" -> "MONITOR_ONLY"
            else -> existing.policyMode
        }
        upsertPolicy(
            existing?.copy(
                policyMode = captureMode,
                enabled = true,
                updatedAt = now,
            ) ?: FirewallAppPolicyEntity(
                packageName = packageName,
                policyMode = captureMode,
                blockQuic = false,
                blockDot = false,
                enabled = true,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    @Transaction
    suspend fun upsertSimpleBlockRule(rule: FirewallRuleEntity, now: Long): Long {
        val existingPolicy = getPolicies().firstOrNull { it.packageName == rule.packageName }
        val effectiveMode = when (existingPolicy?.policyMode) {
            null, "MONITOR_ONLY", "DISABLED" -> "BLOCKLIST"
            else -> existingPolicy.policyMode
        }
        upsertPolicy(
            existingPolicy?.copy(
                policyMode = effectiveMode,
                enabled = true,
                updatedAt = now,
            ) ?: FirewallAppPolicyEntity(
                packageName = rule.packageName,
                policyMode = effectiveMode,
                blockQuic = false,
                blockDot = false,
                enabled = true,
                createdAt = now,
                updatedAt = now,
            ),
        )
        val existingRuleId = findRuleId(
            packageName = rule.packageName,
            ruleType = rule.ruleType,
            action = rule.action,
            value = rule.value,
            protocol = rule.protocol,
            portStart = rule.portStart,
            portEnd = rule.portEnd,
        )
        return upsertRule(rule.copy(id = existingRuleId ?: rule.id, updatedAt = now))
    }

    @Transaction
    suspend fun replaceSelectedPackages(packageNames: Set<String>, now: Long) {
        val existingPolicies = getPolicies().associateBy { it.packageName }
        existingPolicies.keys.filterNot(packageNames::contains).forEach { packageName ->
            deleteRulesForPackage(packageName)
            deletePolicy(packageName)
        }
        packageNames.forEach { packageName ->
            val existing = existingPolicies[packageName]
            upsertPolicy(
                existing?.copy(enabled = true, updatedAt = now) ?: FirewallAppPolicyEntity(
                    packageName = packageName,
                    policyMode = "MONITOR_ONLY",
                    blockQuic = false,
                    blockDot = false,
                    enabled = true,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
    }

    @Transaction
    suspend fun recordHistoryBatch(rows: List<ConnectionHistoryEntity>) {
        rows.groupBy {
            listOf(it.packageName, it.normalizedDestination, it.destinationPort, it.protocol, it.lastDecision)
        }.values.forEach { duplicates ->
            val latest = duplicates.maxBy { it.lastSeenAt }
            val firstSeenAt = duplicates.minOf { it.firstSeenAt }
            val increment = duplicates.sumOf { it.connectionCount }
            val updated = updateHistoryAggregate(
                packageName = latest.packageName,
                normalizedDestination = latest.normalizedDestination,
                destinationPort = latest.destinationPort,
                protocol = latest.protocol,
                lastDecision = latest.lastDecision,
                lastSeenAt = latest.lastSeenAt,
                increment = increment,
                domain = latest.domain,
                destinationIp = latest.destinationIp,
                uid = latest.uid,
                decisionReason = latest.decisionReason,
                metadataSource = latest.metadataSource,
                networkType = latest.networkType,
            )
            if (updated == 0) {
                val inserted = insertHistory(
                    latest.copy(firstSeenAt = firstSeenAt, connectionCount = increment),
                )
                if (inserted == -1L) {
                    updateHistoryAggregate(
                        latest.packageName,
                        latest.normalizedDestination,
                        latest.destinationPort,
                        latest.protocol,
                        latest.lastDecision,
                        latest.lastSeenAt,
                        increment,
                        latest.domain,
                        latest.destinationIp,
                        latest.uid,
                        latest.decisionReason,
                        latest.metadataSource,
                        latest.networkType,
                    )
                }
            }
        }
    }
}
