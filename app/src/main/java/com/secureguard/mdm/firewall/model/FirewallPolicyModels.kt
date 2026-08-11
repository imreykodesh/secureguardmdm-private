package com.secureguard.mdm.firewall.model

enum class FirewallPolicyMode {
    /** Record and forward traffic; the safe default for a newly selected app. */
    MONITOR_ONLY,

    /** Forward traffic unless a matching rule blocks it. */
    BLOCKLIST,

    /** Block traffic unless a matching rule explicitly allows it. */
    ALLOWLIST,

    /** Keep the persisted configuration but exclude the app from the VPN. */
    DISABLED,
}

enum class FirewallRuleAction { ALLOW, BLOCK }

enum class FirewallRuleType { DOMAIN_EXACT, DOMAIN_SUFFIX, IP_EXACT, CIDR, PORT, IP_PORT, DOMAIN_PORT }

enum class FirewallProtocol { ANY, TCP, UDP }

data class FirewallAppPolicy(
    val packageName: String,
    val policyMode: FirewallPolicyMode,
    val blockQuic: Boolean,
    val blockDot: Boolean,
    val enabled: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

data class FirewallRule(
    val id: Long = 0,
    val packageName: String,
    val ruleType: FirewallRuleType,
    val action: FirewallRuleAction,
    val value: String,
    val protocol: FirewallProtocol = FirewallProtocol.ANY,
    val portStart: Int? = null,
    val portEnd: Int? = null,
    val priority: Int = 0,
    val enabled: Boolean = true,
    val source: String = "MANUAL",
    val createdAt: Long,
    val updatedAt: Long,
)

data class FirewallDecision(
    val blocked: Boolean,
    val reason: String,
)
