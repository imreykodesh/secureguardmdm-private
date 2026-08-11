package com.secureguard.mdm.firewall.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "firewall_app_policy")
data class FirewallAppPolicyEntity(
    @PrimaryKey
    @ColumnInfo(name = "package_name")
    val packageName: String,
    @ColumnInfo(name = "policy_mode")
    val policyMode: String,
    @ColumnInfo(name = "block_quic")
    val blockQuic: Boolean,
    @ColumnInfo(name = "block_dot")
    val blockDot: Boolean,
    val enabled: Boolean,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

@Entity(
    tableName = "firewall_rule",
    indices = [
        Index(value = ["package_name"]),
        Index(value = ["package_name", "enabled"]),
        Index(
            value = ["package_name", "rule_type", "action", "value", "protocol", "port_start", "port_end"],
            unique = true,
        ),
    ],
)
data class FirewallRuleEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "package_name")
    val packageName: String,
    @ColumnInfo(name = "rule_type")
    val ruleType: String,
    val action: String,
    val value: String,
    val protocol: String,
    @ColumnInfo(name = "port_start")
    val portStart: Int?,
    @ColumnInfo(name = "port_end")
    val portEnd: Int?,
    val priority: Int,
    val enabled: Boolean,
    val source: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)
