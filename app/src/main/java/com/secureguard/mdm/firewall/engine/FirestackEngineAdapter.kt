package com.secureguard.mdm.firewall.engine

import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
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
import com.secureguard.mdm.firewall.model.FirewallProtocol
import com.secureguard.mdm.utils.FileLogger
import java.net.IDN
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/** Narrow boundary around the pinned Firestack engine and its unstable generated API. */
class FirestackEngineAdapter(
    private val vpnService: VpnService,
    private val selfUid: Int,
    initialSnapshot: FirewallRuleSnapshot,
) {
    private val snapshot = AtomicReference(initialSnapshot)
    private val evaluator = RuleEvaluator()
    private val connectivityManager = vpnService.getSystemService(ConnectivityManager::class.java)
    private val uidPackages = ConcurrentHashMap<Int, Set<String>>()
    private var tunnel: Tunnel? = null

    @Synchronized
    fun start(tunFd: Int, mtu: Int) {
        check(tunnel == null) { "Firestack tunnel is already running" }
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

    fun updateSnapshot(updatedSnapshot: FirewallRuleSnapshot) {
        snapshot.set(updatedSnapshot)
    }

    @Synchronized
    fun stop() {
        val activeTunnel = tunnel ?: return
        tunnel = null
        runCatching { activeTunnel.disconnect() }
            .onFailure { FileLogger.log(TAG, "Error stopping Firestack tunnel: ${it.message}") }
        uidPackages.clear()
    }

    private inner class FirewallBridge : Bridge {
        override fun bind4(who: String, addrport: String, fd: Long) = Unit
        override fun bind6(who: String, addrport: String, fd: Long) = Unit

        override fun protect(who: String, fd: Long) {
            if (!vpnService.protect(fd.toInt())) {
                FileLogger.log(TAG, "Unable to protect $who socket fd=$fd from VPN routing.")
            }
        }

        override fun preflow(protocol: Int, uid: Int, src: String, dst: String): PreMark {
            val ownerUid = resolveOwnerUid(protocol, uid, src, dst)
            return PreMark().apply {
                this.uid = ownerUid.toString()
                isUidSelf = ownerUid == selfUid
            }
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
        ): Mark {
            val endpoint = parseEndpoint(dst)
            val packageNames = packagesForUid(uid)
            val knownDomains = parseDomains(domains) + parseDomains(probableDomains)
            val decision = evaluator.evaluate(
                snapshot.get(),
                FirewallFlow(
                    packageNames = packageNames,
                    protocol = protocol.toFirewallProtocol(),
                    destinationIp = endpoint.first,
                    destinationPort = endpoint.second,
                    domains = knownDomains,
                ),
            )
            if (decision.blocked) {
                FileLogger.log(TAG, "Blocked flow for uid=$uid (${decision.reason}).")
            }
            return Mark().apply {
                pidcsv = if (decision.blocked) BLOCK_PROXY else DIRECT_EGRESS_PROXY
                this.uid = uid.toString()
            }
        }

        override fun inflow(protocol: Int, uid: Int, src: String, dst: String): Mark =
            Mark().apply {
                pidcsv = DIRECT_EGRESS_PROXY
                this.uid = uid.toString()
            }

        override fun flowing(mark: Mark) = Unit
        override fun postflow(summary: FlowSummary) = Unit

        override fun onQuery(url: String, qname: String, qtype: String, uid: Long): DNSOpts? = null
        override fun onResponse(summary: DNSSummary) = Unit
        override fun onUpstreamAnswer(id: String, summary: DNSSummary, options: DNSOpts, server: String): DNSOpts? = null
        override fun onDNSAdded(id: String) = Unit
        override fun onDNSRemoved(id: String) = Unit
        override fun onDNSStopped() = Unit
        override fun onProxiesStopped() = Unit
        override fun onProxyAdded(id: String, addr: String) = Unit
        override fun onProxyRemoved(id: String, addr: String) = Unit
        override fun onProxyStopped(id: String, addr: String) = Unit
        override fun onProxyUpdated(id: String, addr: String) = Unit
        override fun onSvcComplete(summary: ServerSummary) = Unit
        override fun svcRoute(sid: String, pid: String, network: String, sipport: String, dipport: String): Tab? = null
    }

    private fun resolveOwnerUid(protocol: Int, reportedUid: Int, src: String, dst: String): Int {
        if (reportedUid >= 0 || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return reportedUid
        val local = parseSocketAddress(src) ?: return reportedUid
        val remote = parseSocketAddress(dst) ?: return reportedUid
        return runCatching { connectivityManager.getConnectionOwnerUid(protocol, local, remote) }
            .getOrDefault(reportedUid)
    }

    private fun parseSocketAddress(raw: String): InetSocketAddress? {
        val (host, port) = parseEndpoint(raw)
        if (host == null || port == null) return null
        return runCatching { InetSocketAddress(InetAddress.getByName(host), port) }.getOrNull()
    }

    private fun packagesForUid(uid: Int): Set<String> {
        if (uid == selfUid) return setOf(vpnService.packageName)
        return uidPackages.getOrPut(uid) {
            vpnService.packageManager.getPackagesForUid(uid)?.toSet().orEmpty()
        }
    }

    private fun parseDomains(raw: String): Set<String> = raw
        .split(',', ';', ' ')
        .asSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .mapNotNull { runCatching { IDN.toASCII(it.trimEnd('.').lowercase()) }.getOrNull() }
        .toSet()

    private fun parseEndpoint(raw: String): Pair<String?, Int?> {
        val value = raw.trim().substringAfterLast("//")
        val host: String
        val port: Int?
        if (value.startsWith("[")) {
            host = value.substringAfter('[').substringBefore(']')
            port = value.substringAfter("]:", "").toIntOrNull()
        } else {
            val possiblePort = value.substringAfterLast(':', "").toIntOrNull()
            if (possiblePort != null && value.count { it == ':' } <= 1) {
                host = value.substringBeforeLast(':')
                port = possiblePort
            } else {
                host = value
                port = possiblePort
            }
        }
        val normalizedIp = runCatching { InetAddress.getByName(host).hostAddress }.getOrNull()
        return normalizedIp to port
    }

    private fun Int.toFirewallProtocol(): FirewallProtocol = when (this) {
        6 -> FirewallProtocol.TCP
        17 -> FirewallProtocol.UDP
        else -> FirewallProtocol.ANY
    }

    private companion object {
        const val TAG = "FirestackEngine"
        const val DIRECT_EGRESS_PROXY = "Base"
        const val BLOCK_PROXY = "Block"
    }
}
