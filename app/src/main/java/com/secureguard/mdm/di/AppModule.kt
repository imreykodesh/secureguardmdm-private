package com.secureguard.mdm.di

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.secureguard.mdm.boot.impl.NetfreeWatchdogBootTask
import com.secureguard.mdm.boot.impl.ShowToastOnBootTask
import com.secureguard.mdm.data.db.AppDatabase
import com.secureguard.mdm.data.db.BlockedAppCacheDao
import com.secureguard.mdm.data.local.PreferencesManager
import com.secureguard.mdm.data.repository.SettingsRepository
import com.secureguard.mdm.data.repository.SettingsRepositoryImpl
import com.secureguard.mdm.firewall.data.ConnectionHistoryRepository
import com.secureguard.mdm.firewall.data.ConnectionHistoryRepositoryImpl
import com.secureguard.mdm.firewall.data.FirewallDao
import com.secureguard.mdm.firewall.data.FirewallPolicyRepository
import com.secureguard.mdm.firewall.data.FirewallPolicyRepositoryImpl
import com.secureguard.mdm.utils.SecureUpdateHelper
import com.secureguard.mdm.utils.update.UpdateManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "secure_guard_database"
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
    }

    @Provides
    @Singleton
    fun provideBlockedAppCacheDao(appDatabase: AppDatabase): BlockedAppCacheDao {
        return appDatabase.blockedAppCacheDao()
    }

    @Provides
    @Singleton
    fun provideFirewallDao(appDatabase: AppDatabase): FirewallDao = appDatabase.firewallDao()

    @Provides
    @Singleton
    fun provideFirewallPolicyRepository(impl: FirewallPolicyRepositoryImpl): FirewallPolicyRepository = impl

    @Provides
    @Singleton
    fun provideConnectionHistoryRepository(
        impl: ConnectionHistoryRepositoryImpl,
    ): ConnectionHistoryRepository = impl

    @Provides
    @Singleton
    fun provideSharedPreferences(@ApplicationContext context: Context): SharedPreferences {
        return context.getSharedPreferences("secure_guard_prefs", Context.MODE_PRIVATE)
    }

    @Provides
    @Singleton
    fun providePreferencesManager(sharedPreferences: SharedPreferences): PreferencesManager {
        return PreferencesManager(sharedPreferences)
    }

    @Provides
    @Singleton
    fun provideDevicePolicyManager(@ApplicationContext context: Context): DevicePolicyManager {
        return context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    }

    @Provides
    @Singleton
    fun provideConnectivityManager(@ApplicationContext context: Context): ConnectivityManager {
        return context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    @Provides
    @Singleton
    fun provideSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository {
        return impl
    }

    @Provides
    @Singleton
    fun provideSecureUpdateHelper(@ApplicationContext context: Context): SecureUpdateHelper {
        return SecureUpdateHelper(context)
    }

    @Provides
    @Singleton
    fun provideUpdateManager(@ApplicationContext context: Context, secureUpdateHelper: SecureUpdateHelper, preferencesManager: PreferencesManager): UpdateManager {
        return UpdateManager(context, secureUpdateHelper, preferencesManager)
    }

    @Provides
    @Singleton
    fun provideShowToastOnBootTask(
        @ApplicationContext context: Context,
        settingsRepository: SettingsRepository
    ): ShowToastOnBootTask {
        return ShowToastOnBootTask(context, settingsRepository)
    }

    @Provides
    @Singleton
    fun provideNetfreeWatchdogBootTask(
        @ApplicationContext context: Context
    ): NetfreeWatchdogBootTask {
        return NetfreeWatchdogBootTask(context)
    }

    @Provides
    @Singleton
    fun provideGson(): Gson {
        return GsonBuilder().create()
    }

    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `firewall_app_policy` (
                    `package_name` TEXT NOT NULL,
                    `policy_mode` TEXT NOT NULL,
                    `block_quic` INTEGER NOT NULL,
                    `block_dot` INTEGER NOT NULL,
                    `enabled` INTEGER NOT NULL,
                    `created_at` INTEGER NOT NULL,
                    `updated_at` INTEGER NOT NULL,
                    PRIMARY KEY(`package_name`)
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `firewall_rule` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `package_name` TEXT NOT NULL,
                    `rule_type` TEXT NOT NULL,
                    `action` TEXT NOT NULL,
                    `value` TEXT NOT NULL,
                    `protocol` TEXT NOT NULL,
                    `port_start` INTEGER,
                    `port_end` INTEGER,
                    `priority` INTEGER NOT NULL,
                    `enabled` INTEGER NOT NULL,
                    `source` TEXT NOT NULL,
                    `created_at` INTEGER NOT NULL,
                    `updated_at` INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_firewall_rule_package_name` ON `firewall_rule` (`package_name`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_firewall_rule_package_name_enabled` ON `firewall_rule` (`package_name`, `enabled`)")
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_firewall_rule_package_name_rule_type_action_value_protocol_port_start_port_end` " +
                    "ON `firewall_rule` (`package_name`, `rule_type`, `action`, `value`, `protocol`, `port_start`, `port_end`)",
            )
        }
    }

    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `connection_history` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `package_name` TEXT NOT NULL,
                    `uid` INTEGER NOT NULL,
                    `normalized_destination` TEXT NOT NULL,
                    `domain` TEXT,
                    `destination_ip` TEXT NOT NULL,
                    `destination_port` INTEGER NOT NULL,
                    `protocol` TEXT NOT NULL,
                    `first_seen_at` INTEGER NOT NULL,
                    `last_seen_at` INTEGER NOT NULL,
                    `connection_count` INTEGER NOT NULL,
                    `last_decision` TEXT NOT NULL,
                    `decision_reason` TEXT NOT NULL,
                    `metadata_source` TEXT NOT NULL,
                    `network_type` TEXT NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_connection_history_last_seen_at` ON `connection_history` (`last_seen_at`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_connection_history_package_name` ON `connection_history` (`package_name`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_connection_history_package_name_normalized_destination` ON `connection_history` (`package_name`, `normalized_destination`)")
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_connection_history_package_name_normalized_destination_destination_port_protocol_last_decision` " +
                    "ON `connection_history` (`package_name`, `normalized_destination`, `destination_port`, `protocol`, `last_decision`)",
            )
        }
    }
}

