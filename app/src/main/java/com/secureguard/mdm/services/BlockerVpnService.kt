package com.secureguard.mdm.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.secureguard.mdm.R
import com.secureguard.mdm.firewall.engine.FirestackEngineAdapter
import com.secureguard.mdm.utils.FileLogger
import java.io.IOException

class BlockerVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var firestackEngine: FirestackEngineAdapter? = null
    private val tag = "NetfreeVpnService"
    private lateinit var connectivityManager: ConnectivityManager

    companion object {
        const val ACTION_CONNECT = "com.secureguard.mdm.ACTION_CONNECT"
        const val ACTION_DISCONNECT = "com.secureguard.mdm.ACTION_DISCONNECT"
        const val ACTION_UPDATE_POLICY = "ACTION_UPDATE_POLICY"
        const val EXTRA_PREFERRED_NETWORK = "EXTRA_PREFERRED_NETWORK"

        private const val VPN_NOTIFICATION_CHANNEL_ID = "BlockerVpnChannel"
        private const val VPN_NOTIFICATION_ID = 1002
        private const val VPN_MTU = 1500
    }

    override fun onCreate() {
        super.onCreate()
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                VPN_NOTIFICATION_ID,
                createNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(VPN_NOTIFICATION_ID, createNotification())
        }

        when (intent?.action) {
            ACTION_CONNECT -> {
                FileLogger.log(tag, "Received ACTION_CONNECT. Starting internal firewall VPN.")
                stopVpn()
                startVpn(preferredNetwork = null)
            }
            ACTION_DISCONNECT -> {
                FileLogger.log(tag, "Received ACTION_DISCONNECT. Stopping VPN.")
                stopVpn()
                stopSelf()
            }
            ACTION_UPDATE_POLICY -> {
                val preferredNetwork: Network? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_PREFERRED_NETWORK, Network::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_PREFERRED_NETWORK)
                }
                FileLogger.log(tag, "Received ACTION_UPDATE_POLICY. Preferred network: $preferredNetwork")
                stopVpn()
                startVpn(preferredNetwork)
            }
            null -> {
                // Android can restart an Always-On VpnService without the original intent.
                if (firestackEngine == null && vpnInterface == null) {
                    FileLogger.log(tag, "Service restarted without an intent. Restoring firewall VPN.")
                    startVpn(preferredNetwork = null)
                }
            }
        }
        return START_STICKY
    }

    private fun startVpn(preferredNetwork: Network?) {
        try {
            val builder = Builder()
                .setSession(getString(R.string.app_name))
                .setMtu(VPN_MTU)
                .addAddress("10.8.0.1", 24)
                .addAddress("fd66:f83a:c650::1", 120)
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)
                // The first spike protects this app only. Selected packages are added by the
                // policy coordinator after its rule/data layer is implemented.
                .addAllowedApplication(packageName)

            if (preferredNetwork != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    FileLogger.log(tag, "Using preferred underlying network: $preferredNetwork")
                    setUnderlyingNetworks(arrayOf(preferredNetwork))
                } else {
                    FileLogger.log(tag, "Binding service process to preferred network: $preferredNetwork")
                    if (!connectivityManager.bindProcessToNetwork(preferredNetwork)) {
                        FileLogger.log(tag, "Failed to bind process to network $preferredNetwork.")
                    }
                }
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setUnderlyingNetworks(null)
                }
            }

            val establishedInterface = builder.establish()
            if (establishedInterface == null) {
                FileLogger.log(tag, "VPN interface is null; firewall was not started.")
                return
            }

            // Firestack owns and closes this detached descriptor. Its outgoing sockets call
            // VpnService.protect() through the adapter to prevent a routing loop.
            val tunFd = establishedInterface.detachFd()
            firestackEngine = FirestackEngineAdapter(this, applicationInfo.uid).also {
                it.start(tunFd, VPN_MTU)
            }
            FileLogger.log(tag, "Internal Firestack forwarding engine established.")
        } catch (e: Exception) {
            FileLogger.log(tag, "Error establishing internal firewall VPN: ${e.message}")
            stopVpn()
        }
    }

    private fun stopVpn() {
        try {
            firestackEngine?.stop()
            firestackEngine = null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                connectivityManager.bindProcessToNetwork(null)
            }
            vpnInterface?.close()
        } catch (e: IOException) {
            FileLogger.log(tag, "Error closing VPN interface: ${e.message}")
        } finally {
            vpnInterface = null
        }
    }

    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                VPN_NOTIFICATION_CHANNEL_ID,
                getString(R.string.vpn_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.vpn_notification_channel_description)
            }
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

    override fun onRevoke() {
        FileLogger.log(tag, "VPN permission was revoked.")
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        FileLogger.log(tag, "VpnService is being destroyed.")
        stopVpn()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)
}
