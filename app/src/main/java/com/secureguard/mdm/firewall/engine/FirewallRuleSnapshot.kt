package com.secureguard.mdm.firewall.engine

import com.secureguard.mdm.firewall.model.FirewallAppPolicy
import com.secureguard.mdm.firewall.model.FirewallDecision
import com.secureguard.mdm.firewall.model.FirewallPolicyMode
import com.secureguard.mdm.firewall.model.FirewallProtocol
import com.secureguard.mdm.firewall.model.FirewallRule
import com.secureguard.mdm.firewall.model.FirewallRuleAction
import com.secureguard.mdm.firewall.model.FirewallRuleType
import java.net.IDN
import java.net.InetAddress

/** Immutable policy state used by Firestack callbacks without database access. */
data class FirewallRuleSnapshot(
    val policies: Map<String, FirewallAppPolicy> = emptyMap(),
    val rulesByPackage: Map<String, List<FirewallRule>> = emptyMap(),
) {
    val selectedPackages: Set<String>
        get() = policies.values.asSequence()
            .filter { it.enabled && it.policyMode != FirewallPolicyMode.DISABLED }
            .map { it.packageName }
            .toSet()
}

data class FirewallFlow(
    val packageNames: Set<String>,
    val protocol: FirewallProtocol,
    val destinationIp: String?,
    val destinationPort: Int?,
    val domains: Set<String>,
)

class RuleEvaluator {
    fun evaluate(snapshot: FirewallRuleSnapshot, flow: FirewallFlow): FirewallDecision {
        val packageName = flow.packageNames.firstOrNull { snapshot.policies[it]?.enabled == true }
            ?: return FirewallDecision(blocked = false, reason = "NO_SELECTED_POLICY")
        val policy = snapshot.policies.getValue(packageName)
        if (policy.policyMode == FirewallPolicyMode.DISABLED) {
            return FirewallDecision(blocked = false, reason = "POLICY_DISABLED")
        }
        if (policy.policyMode == FirewallPolicyMode.MONITOR_ONLY) {
            return FirewallDecision(blocked = false, reason = "MONITOR_ONLY")
        }

        if (policy.blockQuic && flow.protocol == FirewallProtocol.UDP && flow.destinationPort == 443) {
            return FirewallDecision(blocked = true, reason = "QUIC_BLOCKED")
        }
        if (policy.blockDot && flow.destinationPort == 853) {
            return FirewallDecision(blocked = true, reason = "DOT_BLOCKED")
        }

        val matchingRules = snapshot.rulesByPackage[packageName].orEmpty()
            .asSequence()
            .filter { it.enabled && protocolMatches(it.protocol, flow.protocol) }
            .filter { ruleMatches(it, flow) }
            .sortedWith(
                compareByDescending<FirewallRule> { it.priority }
                    .thenByDescending { specificity(it.ruleType) }
                    .thenByDescending { if (it.action == FirewallRuleAction.BLOCK) 1 else 0 },
            )
            .toList()

        matchingRules.firstOrNull()?.let { rule ->
            return FirewallDecision(
                blocked = rule.action == FirewallRuleAction.BLOCK,
                reason = "RULE_${rule.id}_${rule.ruleType}_${rule.action}",
            )
        }

        return when (policy.policyMode) {
            FirewallPolicyMode.ALLOWLIST -> FirewallDecision(true, "ALLOWLIST_DEFAULT_BLOCK")
            FirewallPolicyMode.BLOCKLIST -> FirewallDecision(false, "BLOCKLIST_DEFAULT_ALLOW")
            FirewallPolicyMode.MONITOR_ONLY -> FirewallDecision(false, "MONITOR_ONLY")
            FirewallPolicyMode.DISABLED -> FirewallDecision(false, "POLICY_DISABLED")
        }
    }

    private fun ruleMatches(rule: FirewallRule, flow: FirewallFlow): Boolean {
        if (!portMatches(rule, flow.destinationPort)) return false
        return when (rule.ruleType) {
            FirewallRuleType.DOMAIN_EXACT -> flow.domains.any { it == rule.value }
            FirewallRuleType.DOMAIN_SUFFIX -> flow.domains.any { it == rule.value || it.endsWith(".${rule.value}") }
            FirewallRuleType.IP_EXACT -> flow.destinationIp == rule.value
            FirewallRuleType.CIDR -> flow.destinationIp?.let { cidrContains(rule.value, it) } == true
            FirewallRuleType.PORT -> flow.destinationPort != null
            FirewallRuleType.IP_PORT -> flow.destinationIp == rule.value
            FirewallRuleType.DOMAIN_PORT -> flow.domains.any { it == rule.value || it.endsWith(".${rule.value}") }
        }
    }

    private fun portMatches(rule: FirewallRule, port: Int?): Boolean {
        if (rule.ruleType !in setOf(FirewallRuleType.PORT, FirewallRuleType.IP_PORT, FirewallRuleType.DOMAIN_PORT)) {
            return true
        }
        val actualPort = port ?: return false
        val start = rule.portStart ?: return false
        val end = rule.portEnd ?: start
        if (start !in 1..65535 || end !in start..65535) return false
        return actualPort in start..end
    }

    private fun protocolMatches(rule: FirewallProtocol, actual: FirewallProtocol): Boolean =
        rule == FirewallProtocol.ANY || rule == actual

    private fun specificity(type: FirewallRuleType): Int = when (type) {
        FirewallRuleType.DOMAIN_EXACT, FirewallRuleType.IP_PORT, FirewallRuleType.DOMAIN_PORT -> 3
        FirewallRuleType.IP_EXACT, FirewallRuleType.PORT -> 2
        FirewallRuleType.DOMAIN_SUFFIX, FirewallRuleType.CIDR -> 1
    }

    private fun cidrContains(cidr: String, candidate: String): Boolean = runCatching {
        val parts = cidr.split('/', limit = 2)
        val network = InetAddress.getByName(parts[0]).address
        val address = InetAddress.getByName(candidate).address
        if (network.size != address.size) return@runCatching false
        val prefix = parts[1].toInt()
        if (prefix !in 0..(network.size * 8)) return@runCatching false
        val fullBytes = prefix / 8
        val remainingBits = prefix % 8
        for (index in 0 until fullBytes) {
            if (network[index] != address[index]) return@runCatching false
        }
        if (remainingBits == 0) return@runCatching true
        val mask = (0xFF shl (8 - remainingBits)) and 0xFF
        (network[fullBytes].toInt() and mask) == (address[fullBytes].toInt() and mask)
    }.getOrDefault(false)

    companion object {
        fun normalizeDomain(value: String): String =
            IDN.toASCII(value.trim().trimEnd('.').lowercase())

        fun normalizeIp(value: String): String = InetAddress.getByName(value.trim()).hostAddress.orEmpty()

        fun normalizeCidr(value: String): String {
            val parts = value.trim().split('/', limit = 2)
            require(parts.size == 2) { "CIDR must include a prefix" }
            val address = InetAddress.getByName(parts[0])
            val prefix = parts[1].toInt()
            require(prefix in 0..address.address.size * 8) { "Invalid CIDR prefix" }
            return "${address.hostAddress}/$prefix"
        }
    }
}
