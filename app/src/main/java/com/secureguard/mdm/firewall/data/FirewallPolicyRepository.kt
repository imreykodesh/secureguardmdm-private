package com.secureguard.mdm.firewall.data

import com.secureguard.mdm.firewall.engine.FirewallRuleSnapshot
import com.secureguard.mdm.firewall.model.FirewallAppPolicy
import com.secureguard.mdm.firewall.model.FirewallPolicyMode
import com.secureguard.mdm.firewall.model.FirewallRule
import kotlinx.coroutines.flow.Flow

interface FirewallPolicyRepository {
    fun observePolicies(): Flow<List<FirewallAppPolicy>>
    fun observeSnapshot(): Flow<FirewallRuleSnapshot>
    suspend fun loadSnapshot(): FirewallRuleSnapshot
    suspend fun selectedPackageNames(): Set<String>
    suspend fun replaceSelectedPackages(packageNames: Set<String>)
    suspend fun updatePolicyMode(packageName: String, mode: FirewallPolicyMode)
    suspend fun updateTransportOptions(packageName: String, blockQuic: Boolean, blockDot: Boolean)
    suspend fun upsertRule(rule: FirewallRule): Long
    suspend fun deleteRule(ruleId: Long)
}
