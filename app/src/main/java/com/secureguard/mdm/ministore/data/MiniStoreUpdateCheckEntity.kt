package com.secureguard.mdm.ministore.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "mini_store_update_check")
data class MiniStoreUpdateCheckEntity(
    @PrimaryKey
    @ColumnInfo(name = "package_name")
    val packageName: String,
    @ColumnInfo(name = "installed_version_code")
    val installedVersionCode: Long,
    @ColumnInfo(name = "available_version_code")
    val availableVersionCode: Long?,
    @ColumnInfo(name = "available_version_name")
    val availableVersionName: String?,
    val source: String?,
    val status: String,
    @ColumnInfo(name = "checked_at_epoch_millis")
    val checkedAtEpochMillis: Long,
    @ColumnInfo(name = "failure_code")
    val failureCode: String?,
)

enum class MiniStoreUpdateCheckStatus {
    UPDATE_AVAILABLE,
    NO_UPDATE,
    CHECK_FAILED,
    SOURCE_DISABLED,
}
