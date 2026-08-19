package com.secureguard.mdm.firewall.data

import com.secureguard.mdm.firewall.engine.FirewallRuleSnapshot
import com.secureguard.mdm.firewall.engine.RuleEvaluator
import com.secureguard.mdm.firewall.model.FirewallAppPolicy
import com.secureguard.mdm.firewall.model.FirewallPolicyMode
import com.secureguard.mdm.firewall.model.FirewallRule
import com.secureguard.mdm.firewall.model.FirewallRuleAction
import com.secureguard.mdm.firewall.model.FirewallRuleType
import com.secureguard.mdm.firewall.model.FirewallProtocol
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirewallPolicyRepositoryImpl @Inject constructor(
    private val dao: FirewallDao,
) : FirewallPolicyRepository {
    override fun observePolicies(): Flow<List<FirewallAppPolicy>> =
        dao.observePolicies().map { entities -> entities.map { it.toModel() } }

    override fun observeSnapshot(): Flow<FirewallRuleSnapshot> = combine(
        dao.observePolicies(),
        dao.observeEnabledRules(),
    ) { policies, rules -> createSnapshot(policies, rules) }

    override suspend fun loadSnapshot(): FirewallRuleSnapshot =
        createSnapshot(dao.getPolicies(), dao.getEnabledRules())

    override suspend fun selectedPackageNames(): Set<String> = loadSnapshot().selectedPackages

    override suspend fun replaceSelectedPackages(packageNames: Set<String>) {
        dao.replaceSelectedPackages(packageNames, System.currentTimeMillis())
    }

    override suspend fun ensurePackageForCapture(packageName: String) {
        dao.ensurePackageForCapture(packageName, System.currentTimeMillis())
    }

    override suspend fun upsertSimpleBlockRule(rule: FirewallRule): Long {
        val now = System.currentTimeMillis()
        return dao.upsertSimpleBlockRule(rule.toEntity(), now)
    }

    override suspend fun updatePolicyMode(packageName: String, mode: FirewallPolicyMode) {
        val now = System.currentTimeMillis()
        val existing = dao.getPolicies().firstOrNull { it.packageName == packageName }
        dao.upsertPolicy(
            existing?.copy(policyMode = mode.name, enabled = mode != FirewallPolicyMode.DISABLED, updatedAt = now)
                ?: FirewallAppPolicyEntity(packageName, mode.name, false, false, mode != FirewallPolicyMode.DISABLED, now, now),
        )
    }

    override suspend fun updateTransportOptions(packageName: String, blockQuic: Boolean, blockDot: Boolean) {
        val now = System.currentTimeMillis()
        val existing = dao.getPolicies().firstOrNull { it.packageName == packageName }
            ?: FirewallAppPolicyEntity(packageName, FirewallPolicyMode.MONITOR_ONLY.name, false, false, true, now, now)
        dao.upsertPolicy(existing.copy(blockQuic = blockQuic, blockDot = blockDot, updatedAt = now))
    }

    override suspend fun upsertRule(rule: FirewallRule): Long {
        val entity = rule.toEntity()
        val existingId = dao.findRuleId(
            entity.packageName,
            entity.ruleType,
            entity.action,
            entity.value,
            entity.protocol,
            entity.portStart,
            entity.portEnd,
        )
        return dao.upsertRule(entity.copy(id = existingId ?: entity.id))
    }

    override suspend fun deleteRule(ruleId: Long) = dao.deleteRule(ruleId)

    private fun createSnapshot(
        policies: List<FirewallAppPolicyEntity>,
        rules: List<FirewallRuleEntity>,
    ): FirewallRuleSnapshot {
        val policyModels = policies.map { it.toModel() }
        return FirewallRuleSnapshot(
            policies = policyModels.associateBy { it.packageName },
            rulesByPackage = rules.map { it.toModel() }.groupBy { it.packageName },
        )
    }

    private fun FirewallAppPolicyEntity.toModel() = FirewallAppPolicy(
        packageName = packageName,
        policyMode = enumValueOrDefault(policyMode, FirewallPolicyMode.MONITOR_ONLY),
        blockQuic = blockQuic,
        blockDot = blockDot,
        enabled = enabled,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun FirewallRuleEntity.toModel() = FirewallRule(
        id = id,
        packageName = packageName,
        ruleType = enumValueOrDefault(ruleType, FirewallRuleType.DOMAIN_EXACT),
        action = enumValueOrDefault(action, FirewallRuleAction.BLOCK),
        value = value,
        protocol = enumValueOrDefault(protocol, FirewallProtocol.ANY),
        portStart = portStart,
        portEnd = portEnd,
        priority = priority,
        enabled = enabled,
        source = source,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun FirewallRule.toEntity(): FirewallRuleEntity {
        if (ruleType in setOf(FirewallRuleType.PORT, FirewallRuleType.IP_PORT, FirewallRuleType.DOMAIN_PORT)) {
            require(portStart != null && portStart in 1..65535) { "A valid port is required" }
            require(portEnd == null || portEnd in portStart..65535) { "Invalid port range" }
        }
        val normalizedValue = when (ruleType) {
            FirewallRuleType.DOMAIN_EXACT, FirewallRuleType.DOMAIN_SUFFIX, FirewallRuleType.DOMAIN_PORT ->
                RuleEvaluator.normalizeDomain(value)
            FirewallRuleType.IP_EXACT, FirewallRuleType.IP_PORT -> RuleEvaluator.normalizeIp(value)
            FirewallRuleType.CIDR -> RuleEvaluator.normalizeCidr(value)
            FirewallRuleType.PORT -> value.trim()
        }
        return FirewallRuleEntity(
            id, packageName, ruleType.name, action.name, normalizedValue, protocol.name,
            portStart, portEnd, priority, enabled, source, createdAt, updatedAt,
        )
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String, default: T): T =
        runCatching { enumValueOf<T>(value) }.getOrDefault(default)
}
