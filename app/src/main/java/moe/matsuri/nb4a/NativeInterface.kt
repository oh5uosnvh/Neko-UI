package moe.matsuri.nb4a

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
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
import libcore.NetworkInterfaceProvider
import libcore.NB4AInterface
import java.net.InetSocketAddress
import java.net.NetworkInterface

class NativeInterface : BoxPlatformInterface, NB4AInterface, NetworkInterfaceProvider {

    //  libbox interface

    override fun autoDetectInterfaceControl(fd: Int) {
        val service = DataStore.vpnService
            ?: throw IllegalStateException("socket protect: VPN service unavailable")
        requireSocketProtection(fd, service::protect)
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

    // One fresh framework snapshot per call. Never cache Java interfaces by name:
    // Android can recreate wlan0/rmnet with a different index after a reconnect.
    private fun networkRecords(): List<PlatformNetworkRecord> {
        val connectivity = SagerNet.connectivity
        @Suppress("DEPRECATION") val networks = connectivity.allNetworks
        return networks.mapNotNull { network ->
            val link = connectivity.getLinkProperties(network) ?: return@mapNotNull null
            val caps = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
            val name = link.interfaceName ?: return@mapNotNull null
            // Java's Android implementation is supported; Go net.Interfaces is not.
            // Lookup only framework-reported names, not a full Java enumeration.
            val javaInterface = NetworkInterface.getByName(name) ?: return@mapNotNull null
            val addresses = link.linkAddresses.mapNotNull { address ->
                address.address.hostAddress?.let { "${it.substringBefore('%')}/${address.prefixLength}" }
            }
            if (addresses.isEmpty()) return@mapNotNull null // network disappeared during snapshot
            var flags = 0
            if (javaInterface.isUp) flags = flags or AndroidNetworkSnapshot.UP or AndroidNetworkSnapshot.RUNNING
            if (javaInterface.isLoopback) flags = flags or AndroidNetworkSnapshot.LOOPBACK
            if (javaInterface.isPointToPoint) flags = flags or AndroidNetworkSnapshot.POINT_TO_POINT
            if (javaInterface.supportsMulticast()) flags = flags or AndroidNetworkSnapshot.MULTICAST
            val mtu = link.mtu.takeIf { it > 0 } ?: javaInterface.mtu
            PlatformNetworkRecord(
                id = network.toString(), name = name, index = javaInterface.index, mtu = mtu, flags = flags,
                addresses = addresses,
                type = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                    else -> "other"
                },
                dnsServers = link.dnsServers.mapNotNull { it.hostAddress },
                dnsSearchDomains = link.domains.orEmpty().split(Regex("\\s+")).filter { it.isNotEmpty() },
                gateways = link.routes.filter { it.gateway?.isAnyLocalAddress == false }
                    .mapNotNull { it.gateway?.hostAddress },
                metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
                vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                    !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN),
                internet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                // API 21/22 does not expose VALIDATED. INTERNET is its only
                // framework bootstrap signal; newer devices must be validated.
                validated = Build.VERSION.SDK_INT < VERSION_CODES.M ||
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            )
        }
    }

    private fun activeNetworkId(): String? = if (Build.VERSION.SDK_INT >= VERSION_CODES.M) {
        SagerNet.connectivity.activeNetwork?.toString()
    } else null

    override fun socketProtectionRequired(): Boolean {
        // Service exists before establish(): protect must already be mandatory.
        return AndroidNetworkSnapshot.protectionRequired(DataStore.vpnService != null) {
            val connectivity = SagerNet.connectivity
            @Suppress("DEPRECATION") val networks = connectivity.allNetworks
            networks.map { network ->
                // A racing/failed state read is NOT evidence that VPN is disabled.
                val caps = connectivity.getNetworkCapabilities(network)
                    ?: throw IllegalStateException("VPN protection state unavailable for $network")
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                    !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            }
        }
    }

    override fun networkInterfacesJSON(): String = AndroidNetworkSnapshot.encode(
        networkRecords(), SagerNet.underlyingNetwork?.toString(), activeNetworkId(),
    )

    // Legacy getters remain ABI compatible, but use the same selection policy.
    private fun defaultPhysicalLink(): LinkProperties? {
        val records = networkRecords()
        val selected = AndroidNetworkSnapshot.selectDefault(
            records, SagerNet.underlyingNetwork?.toString(), activeNetworkId(),
        ) ?: return null
        @Suppress("DEPRECATION") val network = SagerNet.connectivity.allNetworks
            .firstOrNull { it.toString() == selected.id } ?: return null
        return SagerNet.connectivity.getLinkProperties(network)
    }

    override fun defaultNetworkInterface(): String = defaultPhysicalLink()?.interfaceName.orEmpty()

    override fun defaultNetworkDNSServers(): String = defaultPhysicalLink()?.dnsServers
        ?.mapNotNull { it.hostAddress }?.joinToString("\n").orEmpty()

    override fun defaultNetworkGateways(): String = defaultPhysicalLink()?.routes
        ?.mapNotNull { it.gateway?.hostAddress }?.distinct()?.joinToString("\n").orEmpty()

    // nb4a interface

    override fun useOfficialAssets(): Boolean {
        return DataStore.rulesProvider == 0
    }

    override fun selector_OnProxySelected(selectorTag: String, tag: String) {
        if (selectorTag != "proxy") {
            Logs.d("other selector: $selectorTag")
            return
        }
        Libcore.resetAllConnections(true)
        DataStore.baseService?.apply {
            runOnDefaultDispatcher {
                val id = data.proxy!!.config.profileTagMap
                    .filterValues { it == tag }.keys.firstOrNull() ?: -1
                val ent = SagerDatabase.proxyDao.getById(id) ?: return@runOnDefaultDispatcher
                // traffic & title
                data.proxy?.apply {
                    looper?.selectMain(id)
                    displayProfileName = ServiceNotification.genTitle(ent)
                    data.notification?.postNotificationTitle(displayProfileName)
                }
                // post binder
                data.binder.broadcast { b ->
                    b.cbSelectorUpdate(id)
                }
            }
        }
    }

}
