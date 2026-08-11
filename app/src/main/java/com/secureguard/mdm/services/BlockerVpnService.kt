package com.secureguard.mdm.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.secureguard.mdm.R
import com.secureguard.mdm.firewall.data.FirewallPolicyRepository
import com.secureguard.mdm.firewall.engine.FirestackEngineAdapter
import com.secureguard.mdm.firewall.engine.FirewallRuleSnapshot
import com.secureguard.mdm.utils.FileLogger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

@AndroidEntryPoint
class BlockerVpnService : VpnService() {
    @Inject lateinit var firewallRepository: FirewallPolicyRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val actionGeneration = AtomicLong(0)
    private var rebuildJob: Job? = null
    private var firestackEngine: FirestackEngineAdapter? = null
    private lateinit var connectivityManager: ConnectivityManager

    companion object {
        const val ACTION_START = "com.secureguard.mdm.firewall.ACTION_START"
        const val ACTION_STOP = "com.secureguard.mdm.firewall.ACTION_STOP"
        const val ACTION_RELOAD_RULES = "com.secureguard.mdm.firewall.ACTION_RELOAD_RULES"
        const val ACTION_REBUILD_INTERFACE = "com.secureguard.mdm.firewall.ACTION_REBUILD_INTERFACE"
        const val ACTION_NETWORK_CHANGED = "com.secureguard.mdm.firewall.ACTION_NETWORK_CHANGED"

        const val ACTION_CONNECT = ACTION_START
        const val ACTION_DISCONNECT = ACTION_STOP
        const val ACTION_UPDATE_POLICY = ACTION_NETWORK_CHANGED
        const val EXTRA_PREFERRED_NETWORK = "EXTRA_PREFERRED_NETWORK"

        private const val TAG = "InternalFirewallVpn"
        private const val VPN_NOTIFICATION_CHANNEL_ID = "BlockerVpnChannel"
        private const val VPN_NOTIFICATION_ID = 1002
        private const val VPN_MTU = 1500
    }

    override fun onCreate() {
        super.onCreate()
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        val preferredNetwork = intent?.readPreferredNetwork()
        when (intent?.action) {
            ACTION_STOP -> stopAndInvalidate()
            ACTION_RELOAD_RULES -> serviceScope.launch {
                val activeEngine = synchronized(this@BlockerVpnService) { firestackEngine }
                if (activeEngine == null) {
                    stopSelf()
                    return@launch
                }
                val snapshot = firewallRepository.loadSnapshot()
                activeEngine.updateSnapshot(snapshot)
                FileLogger.log(TAG, "Reloaded ${snapshot.rulesByPackage.values.sumOf(List<*>::size)} firewall rules.")
            }
            ACTION_START, ACTION_REBUILD_INTERFACE, ACTION_NETWORK_CHANGED, null -> rebuildVpn(preferredNetwork)
            else -> FileLogger.log(TAG, "Ignoring unknown action ${intent.action}.")
        }
        return START_STICKY
    }

    private fun stopAndInvalidate() {
        actionGeneration.incrementAndGet()
        rebuildJob?.cancel()
        stopVpn()
        stopSelf()
    }

    private fun rebuildVpn(preferredNetwork: Network?) {
        val generation = actionGeneration.incrementAndGet()
        rebuildJob?.cancel()
        rebuildJob = serviceScope.launch {
            stopVpn()
            val snapshot = firewallRepository.loadSnapshot()
            if (!isCurrent(generation)) return@launch

            val selectedPackages = eligiblePackages(snapshot.selectedPackages)
            if (selectedPackages.isEmpty()) {
                FileLogger.log(TAG, "No eligible selected applications; VPN interface was not established.")
                stopSelf()
                return@launch
            }

            var establishedInterface: ParcelFileDescriptor? = null
            try {
                val builder = Builder()
                    .setSession(getString(R.string.app_name))
                    .setMtu(VPN_MTU)
                    .addAddress("10.8.0.1", 24)
                    .addAddress("fd66:f83a:c650::1", 120)
                    .addRoute("0.0.0.0", 0)
                    .addRoute("::", 0)

                selectedPackages.forEach(builder::addAllowedApplication)
                applyUnderlyingNetwork(preferredNetwork)
                if (!isCurrent(generation)) return@launch

                establishedInterface = builder.establish()
                if (establishedInterface == null) {
                    FileLogger.log(TAG, "VPN establish() returned null; firewall is not active.")
                    stopSelf()
                    return@launch
                }
                if (!installEngineIfCurrent(generation, establishedInterface, snapshot)) return@launch
                establishedInterface = null // The engine owns the detached descriptor.
                FileLogger.log(TAG, "Firewall VPN active for ${selectedPackages.size} selected package(s).")
            } catch (error: Exception) {
                FileLogger.log(TAG, "Unable to establish firewall VPN: ${error.message}")
                if (isCurrent(generation)) {
                    stopVpn()
                    stopSelf()
                }
            } finally {
                establishedInterface?.close()
            }
        }
    }

    @Synchronized
    private fun installEngineIfCurrent(
        generation: Long,
        descriptor: ParcelFileDescriptor,
        snapshot: FirewallRuleSnapshot,
    ): Boolean {
        if (!isCurrent(generation)) return false
        val tunFd = descriptor.detachFd()
        val engine = FirestackEngineAdapter(this, applicationInfo.uid, snapshot)
        engine.start(tunFd, VPN_MTU)
        if (!isCurrent(generation)) {
            engine.stop()
            return false
        }
        firestackEngine = engine
        return true
    }

    private fun eligiblePackages(selectedPackages: Set<String>): Set<String> = selectedPackages.mapNotNull { candidate ->
        if (candidate == packageName) return@mapNotNull null
        val appInfo = try {
            packageManager.getApplicationInfo(candidate, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            FileLogger.log(TAG, "Skipping removed package $candidate during VPN rebuild.")
            return@mapNotNull null
        }
        val packagesForUid = packageManager.getPackagesForUid(appInfo.uid).orEmpty().toSet()
        if (packagesForUid.size > 1) {
            FileLogger.log(TAG, "Skipping $candidate because UID ${appInfo.uid} is shared by $packagesForUid.")
            return@mapNotNull null
        }
        candidate
    }.toSet()

    private fun isCurrent(generation: Long): Boolean = actionGeneration.get() == generation

    private fun applyUnderlyingNetwork(network: Network?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            setUnderlyingNetworks(network?.let { arrayOf(it) })
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            connectivityManager.bindProcessToNetwork(network)
        }
    }

    @Synchronized
    private fun stopVpn() {
        firestackEngine?.stop()
        firestackEngine = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            connectivityManager.bindProcessToNetwork(null)
        }
    }

    private fun startInForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                VPN_NOTIFICATION_ID,
                createNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(VPN_NOTIFICATION_ID, createNotification())
        }
    }

    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                VPN_NOTIFICATION_CHANNEL_ID,
                getString(R.string.vpn_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = getString(R.string.vpn_notification_channel_description) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, VPN_NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.vpn_notification_content))
            .setSmallIcon(R.drawable.ic_netguard_shield)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun Intent.readPreferredNetwork(): Network? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(EXTRA_PREFERRED_NETWORK, Network::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(EXTRA_PREFERRED_NETWORK)
    }

    override fun onRevoke() {
        FileLogger.log(TAG, "VPN permission revoked; firewall stopped.")
        stopAndInvalidate()
        super.onRevoke()
    }

    override fun onDestroy() {
        actionGeneration.incrementAndGet()
        rebuildJob?.cancel()
        stopVpn()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)
}
