package moe.matsuri.nb4a

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Build.VERSION_CODES
import androidx.annotation.RequiresApi
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.ServiceNotification
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.utils.PackageCache
import libcore.BoxPlatformInterface
import libcore.Libcore
import libcore.NB4AInterface
import java.net.InetSocketAddress

class NativeInterface : BoxPlatformInterface, NB4AInterface {

    //  libbox interface

    override fun autoDetectInterfaceControl(fd: Int) {
        val service = DataStore.vpnService
            ?: throw IllegalStateException("socket protect: VPN service unavailable")
        check(service.protect(fd)) { "socket protect failed for fd=$fd" }
    }

    override fun openTun(singTunOptionsJson: String, tunPlatformOptionsJson: String): Long {
        if (DataStore.vpnService == null) {
            throw Exception("no VpnService")
        }
        return DataStore.vpnService!!.startVpn(singTunOptionsJson, tunPlatformOptionsJson).toLong()
    }

    override fun useProcFS(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun findConnectionOwner(
        ipProto: Int, srcIp: String, srcPort: Int, destIp: String, destPort: Int
    ): Int {
        return SagerNet.connectivity.getConnectionOwnerUid(
            ipProto, InetSocketAddress(srcIp, srcPort), InetSocketAddress(destIp, destPort)
        )
    }

    override fun packageNameByUid(uid: Int): String {
        PackageCache.awaitLoadSync()

        if (uid <= 1000L) {
            return "android"
        }

        val packageNames = PackageCache.uidMap[uid]
        if (!packageNames.isNullOrEmpty()) for (packageName in packageNames) {
            return packageName
        }

        error("unknown uid $uid")
    }

    override fun uidByPackageName(packageName: String): Int {
        PackageCache.awaitLoadSync()
        return PackageCache[packageName] ?: 0
    }

    // TODO: 'getter for connectionInfo: WifiInfo!' is deprecated
    override fun wifiState(): String {
        val wifiManager =
            app.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val connectionInfo = wifiManager.connectionInfo
        return "${connectionInfo.ssid},${connectionInfo.bssid}"
    }

    override fun defaultNetworkInterface(): String {
        val network = SagerNet.underlyingNetwork ?: SagerNet.connectivity.activeNetwork ?: return ""
        return SagerNet.connectivity.getLinkProperties(network)?.interfaceName.orEmpty()
    }

    override fun defaultNetworkDNSServers(): String {
        val network = SagerNet.underlyingNetwork ?: SagerNet.connectivity.activeNetwork ?: return ""
        return SagerNet.connectivity.getLinkProperties(network)?.dnsServers
            ?.mapNotNull { it.hostAddress }
            ?.joinToString("\n")
            .orEmpty()
    }

    override fun defaultNetworkGateways(): String {
        val network = SagerNet.underlyingNetwork ?: SagerNet.connectivity.activeNetwork ?: return ""
        val link = SagerNet.connectivity.getLinkProperties(network) ?: return ""
        return link.routes.mapNotNull { it.gateway?.hostAddress }.distinct().joinToString("\n")
    }

    // nb4a interface

    override fun useOfficialAssets(): Boolean {
        return DataStore.rulesProvider == 0
    }

    override fun selector_OnProxySelected(selectorTag: String, tag: String) {
        if (selectorTag != "proxy") {
            Logs.d("other selector: $selectorTag")
            return
        }
        DataStore.baseService?.apply {
            runOnDefaultDispatcher {
                val running = data.proxy ?: return@runOnDefaultDispatcher
                val id = running.config.profileTagMap
                    .filterValues { it == tag }.keys.firstOrNull() ?: return@runOnDefaultDispatcher
                // The running immutable tag/traffic map is the authoritative
                // membership proof. Do not call Room/buildConfig() here: this
                // callback is emitted after native selector ACK and must stay fast.
                val compiled = running.config.trafficMap[tag].orEmpty()
                val ent = compiled.firstOrNull { it.id == id }
                if (ent == null || tag.isBlank()) {
                    Logs.w("reject selector callback outside current compiled membership: $tag/$id")
                    return@runOnDefaultDispatcher
                }
                if (data.proxy !== running || !data.state.connected) return@runOnDefaultDispatcher
                // Callbacks may arrive out of order after rapid A -> B -> C taps.
                // The core performs the old-generation connection retirement;
                // a delayed Android global reset would kill brand-new C flows.
                if (running.box.selectedOutbound() != tag) return@runOnDefaultDispatcher
                // Service-side state is authoritative for both Binder and Clash
                // selections; a delayed UI callback must never write it back.
                val committed = synchronized(data.selectorMutationLock) {
                    if (data.proxy !== running || running.box.selectedOutbound() != tag) false else {
                        DataStore.selectedProxy = id
                        DataStore.currentProfile = id
                        true
                    }
                }
                if (!committed) return@runOnDefaultDispatcher
                // traffic & title
                running.apply {
                    looper?.selectMain(id)
                    displayProfileName = ServiceNotification.genTitle(ent)
                    data.notification?.postNotificationTitle(displayProfileName)
                }
                // Traffic accounting can suspend. Recheck the native selection
                // before publishing, so a stale callback cannot repaint the UI.
                if (data.proxy !== running || running.box.selectedOutbound() != tag) return@runOnDefaultDispatcher
                data.binder.broadcast { b ->
                    b.cbSelectorUpdate(id)
                }
            }
        }
    }

}
