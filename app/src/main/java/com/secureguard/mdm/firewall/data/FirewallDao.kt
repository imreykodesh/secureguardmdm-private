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

    @Query("DELETE FROM firewall_rule WHERE id = :ruleId")
    suspend fun deleteRule(ruleId: Long)

    @Transaction
    suspend fun replaceSelectedPackages(packageNames: Set<String>, now: Long) {
        val existingPolicies = getPolicies().associateBy { it.packageName }
        existingPolicies.keys.filterNot(packageNames::contains).forEach { packageName ->
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
}
