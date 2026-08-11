package com.secureguard.mdm.firewall.engine

import android.net.VpnService
import com.celzero.firestack.backend.DNSOpts
import com.celzero.firestack.backend.DNSSummary
import com.celzero.firestack.backend.ServerSummary
import com.celzero.firestack.backend.Tab
import com.celzero.firestack.intra.Bridge
import com.celzero.firestack.intra.FlowSummary
import com.celzero.firestack.intra.Intra
import com.celzero.firestack.intra.Mark
import com.celzero.firestack.intra.PreMark
import com.celzero.firestack.intra.Tunnel
import com.celzero.firestack.settings.Settings
import com.secureguard.mdm.utils.FileLogger

/**
 * Narrow boundary around the vendored Firestack engine.
 *
 * The first spike deliberately permits direct egress for every captured flow. The [Bridge]
 * callbacks are the sole future integration points for per-package, domain/IP, QUIC, and
 * destination-history policy; they keep Firestack's unstable generated API out of the service.
 */
class FirestackEngineAdapter(
    private val vpnService: VpnService,
    private val selfUid: Int,
) {
    private var tunnel: Tunnel? = null

    @Synchronized
    fun start(tunFd: Int, mtu: Int) {
        check(tunnel == null) { "Firestack tunnel is already running" }

        // Firestack takes ownership of the detached TUN descriptor and forwards both IP families.
        Settings.defaultTunMode()
        Settings.dupTunFd(false)
        Intra.setSELF_UID(selfUid.toString())

        tunnel = Intra.newTunnel(
            tunFd.toLong(),
            mtu.toLong(),
            "10.8.0.1/24,fd66:f83a:c650::1/120",
            "",
            null,
            FirewallBridge(),
        )
        FileLogger.log(TAG, "Firestack tunnel started (fd=$tunFd, mtu=$mtu).")
    }

    @Synchronized
    fun stop() {
        val activeTunnel = tunnel ?: return
        tunnel = null
        runCatching { activeTunnel.disconnect() }
            .onFailure { FileLogger.log(TAG, "Error stopping Firestack tunnel: ${it.message}") }
    }

    private inner class FirewallBridge : Bridge {
        override fun bind4(who: String, addrport: String, fd: Long) = Unit

        override fun bind6(who: String, addrport: String, fd: Long) = Unit

        override fun protect(who: String, fd: Long) {
            if (!vpnService.protect(fd.toInt())) {
                FileLogger.log(TAG, "Unable to protect $who socket fd=$fd from VPN routing.")
            }
        }

        override fun preflow(protocol: Int, uid: Int, src: String, dst: String): PreMark =
            PreMark().apply {
                this.uid = uid.toString()
                isUidSelf = uid == selfUid
            }

        override fun flow(
            protocol: Int,
            uid: Int,
            src: String,
            dst: String,
            origdsts: String,
            domains: String,
            probableDomains: String,
            blocklists: String,
            dstIsAlg: Boolean,
        ): Mark = Mark().apply {
            // "Base" is Firestack's direct, protected egress proxy. Later policy can return
            // "Block" here without changing service or packet-loop ownership.
            pidcsv = DIRECT_EGRESS_PROXY
            this.uid = uid.toString()
        }

        override fun inflow(protocol: Int, uid: Int, src: String, dst: String): Mark =
            Mark().apply {
                pidcsv = DIRECT_EGRESS_PROXY
                this.uid = uid.toString()
            }

        override fun flowing(mark: Mark) = Unit

        override fun postflow(summary: FlowSummary) {
            // Metadata only; persistent history is added with the firewall data layer.
            FileLogger.log(
                TAG,
                "Flow closed: ${summary.proto} ${summary.target}, uid=${summary.uid}, " +
                    "tx=${summary.tx}, rx=${summary.rx}, msg=${summary.msg}",
            )
        }

        override fun onQuery(
            url: String,
            qname: String,
            qtype: String,
            uid: Long,
        ): DNSOpts? = null

        override fun onResponse(summary: DNSSummary) = Unit

        override fun onUpstreamAnswer(
            id: String,
            summary: DNSSummary,
            options: DNSOpts,
            server: String,
        ): DNSOpts? = null

        override fun onDNSAdded(id: String) = Unit
        override fun onDNSRemoved(id: String) = Unit
        override fun onDNSStopped() = Unit
        override fun onProxiesStopped() = Unit
        override fun onProxyAdded(id: String, addr: String) = Unit
        override fun onProxyRemoved(id: String, addr: String) = Unit
        override fun onProxyStopped(id: String, addr: String) = Unit
        override fun onProxyUpdated(id: String, addr: String) = Unit
        override fun onSvcComplete(summary: ServerSummary) = Unit
        override fun svcRoute(
            sid: String,
            pid: String,
            network: String,
            sipport: String,
            dipport: String,
        ): Tab? = null
    }

    private companion object {
        const val TAG = "FirestackEngine"
        const val DIRECT_EGRESS_PROXY = "Base"
    }
}
