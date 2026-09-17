package com.alivpn.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import io.nekohasekai.libbox.*

class AliVpnService : VpnService(), PlatformInterface, CommandServerHandler {

    companion object {
        const val ACTION_STATE = "com.alivpn.app.STATE"
        const val EXTRA_STATE = "state"
        const val STATE_CONNECTING = "CONNECTING"
        const val STATE_CONNECTED = "CONNECTED"
        const val STATE_DISCONNECTED = "DISCONNECTED"
        const val STATE_ERROR = "ERROR"

        const val ACTION_STOP = "com.alivpn.app.STOP"

        private const val CHANNEL_ID = "alivpn_vpn"
        private const val NOTIFICATION_ID = 1001
    }

    private var commandServer: CommandServer? = null
    private var tunnelFd: android.os.ParcelFileDescriptor? = null
    private var currentConfig: String? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Enter foreground immediately to satisfy Android's FGS timing rules.
        startForegroundCompat("AliVPN", "Подготавливаем VPN")

        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val config = intent?.getStringExtra("config")
        if (config.isNullOrBlank()) {
            broadcastState(STATE_ERROR, "Конфигурация отсутствует")
            stopSelf()
            return START_NOT_STICKY
        }

        if (commandServer != null) return START_NOT_STICKY

        currentConfig = config
        Thread {
            runCatching {
                val server = CommandServer(this, this)
                server.start()
                commandServer = server
                server.startOrReloadService(
                    config,
                    OverrideOptions().also { it.autoRedirect = false }
                )
                updateNotification("AliVPN", "VPN подключён")
                broadcastState(STATE_CONNECTED, null)
            }.onFailure {
                Log.e("AliVPN", "Core start failed", it)
                broadcastState(STATE_ERROR, it.message ?: "Не удалось запустить VPN")
                closeCore()
                stopSelf()
            }
        }.start()

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        closeCore()
        super.onDestroy()
        broadcastState(STATE_DISCONNECTED, null)
    }

    private fun closeCore() {
        runCatching {
            tunnelFd?.close()
            tunnelFd = null
        }
        runCatching {
            commandServer?.close()
            commandServer = null
        }
        currentConfig = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "AliVPN VPN",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    private fun startForegroundCompat(title: String, text: String) {
        val notification = buildNotification(title, text)
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun updateNotification(title: String, text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(title, text))
    }

    private fun buildNotification(title: String, text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun broadcastState(state: String, message: String?) {
        sendBroadcast(
            Intent(ACTION_STATE)
                .setPackage(packageName)
                .putExtra(EXTRA_STATE, state)
                .putExtra("message", message ?: "")
        )
    }

    // ----- CommandServerHandler -----

    override fun serviceStop() {
        stopSelf()
    }

    override fun serviceReload() {
        val cfg = currentConfig ?: return
        commandServer?.startOrReloadService(cfg, OverrideOptions())
    }

    override fun getSystemProxyStatus(): SystemProxyStatus {
        return SystemProxyStatus().also {
            it.available = false
            it.enabled = false
        }
    }

    override fun setSystemProxyEnabled(enabled: Boolean) = Unit

    override fun triggerNativeCrash() {
        throw UnsupportedOperationException("Disabled")
    }

    override fun writeDebugMessage(message: String?) {
        Log.d("AliVPN", message ?: "")
    }

    override fun connectSSHAgent(): Int = -1

    override fun sendNotification(notification: Notification) = Unit

    override fun cancelNotification(identifier: String, typeID: Int) = Unit

    // ----- PlatformInterface -----

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun autoDetectInterfaceControl(fd: Int) {
        if (!protect(fd)) {
            Log.w("AliVPN", "protect($fd) failed")
        }
    }

    override fun openTun(options: TunOptions): Int {
        if (VpnService.prepare(this) != null) {
            error("VPN permission is missing")
        }

        val builder = Builder()
            .setSession("AliVPN")
            .setMtu(options.mtu)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        val v4 = options.inet4Address
        while (v4.hasNext()) {
            val p = v4.next()
            builder.addAddress(p.address(), p.prefix())
        }

        val v6 = options.inet6Address
        while (v6.hasNext()) {
            val p = v6.next()
            builder.addAddress(p.address(), p.prefix())
        }

        if (options.autoRoute) {
            val dns = options.dnsServerAddress
            while (dns.hasNext()) {
                builder.addDnsServer(dns.next())
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val r4 = options.inet4RouteAddress
                while (r4.hasNext()) {
                    builder.addRoute(r4.next().toIpPrefix())
                }
                val r6 = options.inet6RouteAddress
                while (r6.hasNext()) {
                    builder.addRoute(r6.next().toIpPrefix())
                }

                val x4 = options.inet4RouteExcludeAddress
                while (x4.hasNext()) {
                    builder.excludeRoute(x4.next().toIpPrefix())
                }
                val x6 = options.inet6RouteExcludeAddress
                while (x6.hasNext()) {
                    builder.excludeRoute(x6.next().toIpPrefix())
                }
            } else {
                val r4 = options.inet4RouteRange
                while (r4.hasNext()) {
                    val p = r4.next()
                    builder.addRoute(p.address(), p.prefix())
                }
                val r6 = options.inet6RouteRange
                while (r6.hasNext()) {
                    val p = r6.next()
                    builder.addRoute(p.address(), p.prefix())
                }
            }
        }

        val included = options.includePackage
        while (included.hasNext()) {
            runCatching { builder.addAllowedApplication(included.next()) }
        }

        val excluded = options.excludePackage
        while (excluded.hasNext()) {
            runCatching { builder.addDisallowedApplication(excluded.next()) }
        }

        val proxyServer = options.httpProxyServer
        if (options.isHTTPProxyEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setHttpProxy(
                ProxyInfo.buildDirectProxy(
                    proxyServer,
                    options.httpProxyServerPort
                )
            )
        }

        tunnelFd?.close()
        tunnelFd = builder.establish()
            ?: error("Android refused to establish the VPN interface")

        return tunnelFd!!.fd
    }

    override fun useProcFS(): Boolean = false

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int
    ): ConnectionOwner {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            error("Connection owner API requires Android 10+")
        }
        val uid = getSystemService(android.net.ConnectivityManager::class.java)
            .getConnectionOwnerUid(
                ipProtocol,
                java.net.InetSocketAddress(sourceAddress, sourcePort),
                java.net.InetSocketAddress(destinationAddress, destinationPort)
            )

        return ConnectionOwner().also {
            it.userId = uid
            val pkgs = packageManager.getPackagesForUid(uid)
            it.userName = pkgs?.firstOrNull() ?: ""
            it.setAndroidPackageNames(StringArray(pkgs?.asList()?.iterator() ?: emptyList<String>().iterator()))
        }
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) = Unit
    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) = Unit

    override fun getInterfaces(): NetworkInterfaceIterator {
        val cm = getSystemService(android.net.ConnectivityManager::class.java)
        val javaInterfaces = java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        val result = ArrayList<io.nekohasekai.libbox.NetworkInterface>()

        for (network in cm.allNetworks) {
            val lp = cm.getLinkProperties(network) ?: continue
            val caps = cm.getNetworkCapabilities(network) ?: continue
            val name = lp.interfaceName ?: continue
            val ni = javaInterfaces.firstOrNull { it.name == name } ?: continue

            val box = io.nekohasekai.libbox.NetworkInterface().also {
                it.name = name
                it.index = ni.index
                it.mtu = runCatching { ni.mtu }.getOrDefault(1500)
                it.dnsServer = StringArray(
                    lp.dnsServers.mapNotNull { a -> a.hostAddress }.iterator()
                )
                it.gateway = StringArray(
                    lp.routes.mapNotNull { route ->
                        route.gateway?.takeIf { gw -> !gw.isAnyLocalAddress }?.hostAddress
                    }.iterator()
                )
                it.addresses = StringArray(
                    ni.interfaceAddresses.map { a ->
                        val host = a.address.hostAddress
                        "$host/${a.networkPrefixLength}"
                    }.iterator()
                )
                it.type = when {
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ->
                        Libbox.InterfaceTypeWIFI
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) ->
                        Libbox.InterfaceTypeCellular
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) ->
                        Libbox.InterfaceTypeEthernet
                    else -> Libbox.InterfaceTypeOther
                }
                it.metered = !caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                it.flags = 0
            }
            result += box
        }

        val iterator = result.iterator()
        return object : NetworkInterfaceIterator {
            override fun hasNext(): Boolean = iterator.hasNext()
            override fun next(): io.nekohasekai.libbox.NetworkInterface = iterator.next()
        }
    }

    override fun underNetworkExtension(): Boolean = false
    override fun includeAllNetworks(): Boolean = false
    override fun clearDNSCache() = Unit
    override fun readWIFIState(): WIFIState? = null
    override fun localDNSTransport(): LocalDNSTransport? = null

    override fun startNeighborMonitor(listener: NeighborUpdateListener?) = Unit
    override fun closeNeighborMonitor(listener: NeighborUpdateListener?) = Unit

    override fun usePlatformShell(): Boolean = false
    override fun checkPlatformShell() = error("Shell access is not available")
    override fun openShellSession(
        user: PlatformUser?,
        command: String?,
        environ: StringIterator?,
        term: String?,
        rows: Int,
        cols: Int
    ): ShellSession = error("Shell access is not available")

    override fun readSystemSSHHostKey(): String = error("Not supported")
    override fun lookupSFTPServer(): String = error("Not supported")
    override fun tailscaleHostname(): String = "${Build.MANUFACTURER} ${Build.MODEL}"

    override fun usePlatformBridge(): Boolean = false
    override fun createBridge(options: BridgeOptions?): BridgeSession =
        error("Bridge is not available")

    override fun usePlatformAutoRedirect(): Boolean = false
    override fun createAutoRedirect(
        options: ByteArray?,
        handler: AutoRedirectHandler?
    ): AutoRedirectSession = error("Auto redirect is not available")

    override fun lookupUser(username: String?): PlatformUser {
        return PlatformUser().also {
            it.username = username ?: packageName
            it.uid = android.os.Process.myUid()
            it.gid = android.os.Process.myUid()
            it.homeDir = filesDir.absolutePath
        }
    }

    override fun registerMyInterface(name: String?) = Unit

    class StringArray(values: Iterator<String>) : StringIterator {
        private val data = values.asSequence().toList()
        private var index = 0

        override fun len(): Int = data.size
        override fun hasNext(): Boolean = index < data.size
        override fun next(): String = data[index++]
    }

}
