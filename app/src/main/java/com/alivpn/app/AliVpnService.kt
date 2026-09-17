package com.alivpn.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.IpPrefix
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

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        // Android requires the foreground service to enter
        // foreground quickly.
        startForegroundCompat(
            "AliVPN",
            "Подготавливаем VPN"
        )

        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val config = intent?.getStringExtra("config")

        if (config.isNullOrBlank()) {
            broadcastState(
                STATE_ERROR,
                "Конфигурация отсутствует"
            )

            stopSelf()
            return START_NOT_STICKY
        }

        // Don't start another core over an already running one.
        if (commandServer != null) {
            return START_NOT_STICKY
        }

        currentConfig = config

        Thread {
            runCatching {

                val server = CommandServer(
                    this,
                    this
                )

                server.start()

                commandServer = server

                server.startOrReloadService(
                    config,
                    OverrideOptions().also {
                        it.autoRedirect = false
                    }
                )

                // The core has started, but we don't yet claim
                // that external traffic works. MainActivity performs
                // the actual external-IP verification.
                updateNotification(
                    "AliVPN",
                    "Проверяем соединение…"
                )

                broadcastState(
                    STATE_CONNECTING,
                    null
                )

            }.onFailure { error ->

                Log.e(
                    "AliVPN",
                    "Core start failed",
                    error
                )

                broadcastState(
                    STATE_ERROR,
                    error.message ?: "Не удалось запустить VPN"
                )

                closeCore()
                stopSelf()
            }

        }.start()

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        closeCore()

        super.onDestroy()

        broadcastState(
            STATE_DISCONNECTED,
            null
        )
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

            val nm =
                getSystemService(
                    NotificationManager::class.java
                )

            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "AliVPN VPN",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    private fun startForegroundCompat(
        title: String,
        text: String
    ) {

        val notification =
            buildNotification(
                title,
                text
            )

        startForeground(
            NOTIFICATION_ID,
            notification
        )
    }

    private fun updateNotification(
        title: String,
        text: String
    ) {

        val nm =
            getSystemService(
                NotificationManager::class.java
            )

        nm.notify(
            NOTIFICATION_ID,
            buildNotification(
                title,
                text
            )
        )
    }

    private fun buildNotification(
        title: String,
        text: String
    ): Notification {

        return NotificationCompat.Builder(
            this,
            CHANNEL_ID
        )
            .setSmallIcon(
                android.R.drawable.stat_sys_warning
            )
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setCategory(
                NotificationCompat.CATEGORY_SERVICE
            )
            .build()
    }

    private fun broadcastState(
        state: String,
        message: String?
    ) {

        sendBroadcast(
            Intent(ACTION_STATE)
                .setPackage(packageName)
                .putExtra(
                    EXTRA_STATE,
                    state
                )
                .putExtra(
                    "message",
                    message ?: ""
                )
        )
    }

    // ---------------------------------------------------------
    // CommandServerHandler
    // ---------------------------------------------------------

    override fun serviceStop() {
        stopSelf()
    }

    override fun serviceReload() {

        val cfg =
            currentConfig
                ?: return

        commandServer?.startOrReloadService(
            cfg,
            OverrideOptions()
        )
    }

    override fun getSystemProxyStatus():
            SystemProxyStatus {

        return SystemProxyStatus().also {

            it.available = false
            it.enabled = false
        }
    }

    override fun setSystemProxyEnabled(
        enabled: Boolean
    ) = Unit

    override fun triggerNativeCrash() {
        throw UnsupportedOperationException(
            "Disabled"
        )
    }

    override fun writeDebugMessage(
        message: String?
    ) {

        Log.d(
            "AliVPN",
            message ?: ""
        )
    }

    override fun connectSSHAgent(): Int =
        -1

    override fun sendNotification(
        notification: Notification
    ) = Unit

    override fun cancelNotification(
        identifier: String,
        typeID: Int
    ) = Unit

    // ---------------------------------------------------------
    // PlatformInterface
    // ---------------------------------------------------------

    override fun usePlatformAutoDetectInterfaceControl():
            Boolean = true

    override fun autoDetectInterfaceControl(
        fd: Int
    ) {

        if (!protect(fd)) {

            Log.w(
                "AliVPN",
                "protect($fd) failed"
            )
        }
    }

    override fun openTun(
        options: TunOptions
    ): Int {

        if (VpnService.prepare(this) != null) {
            error("VPN permission is missing")
        }

        val builder =
            Builder()
                .setSession("AliVPN")
                .setMtu(options.mtu)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        // -----------------------------------------------------
        // TUN addresses
        // -----------------------------------------------------

        val v4 =
            options.inet4Address

        while (v4.hasNext()) {

            val prefix =
                v4.next()

            builder.addAddress(
                prefix.address(),
                prefix.prefix()
            )
        }

        val v6 =
            options.inet6Address

        while (v6.hasNext()) {

            val prefix =
                v6.next()

            builder.addAddress(
                prefix.address(),
                prefix.prefix()
            )
        }

        // -----------------------------------------------------
        // Routing
        // -----------------------------------------------------

        if (options.autoRoute) {

            val dns =
                options.dnsServerAddress

            while (dns.hasNext()) {

                builder.addDnsServer(
                    dns.next()
                )
            }

            // Android 13+ supports route exclusion.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {

                val r4 =
                    options.inet4RouteAddress

                while (r4.hasNext()) {

                    val route =
                        r4.next()

                    builder.addRoute(
                        route.address(),
                        route.prefix()
                    )
                }

                val r6 =
                    options.inet6RouteAddress

                while (r6.hasNext()) {

                    val route =
                        r6.next()

                    builder.addRoute(
                        route.address(),
                        route.prefix()
                    )
                }

                val x4 =
                    options.inet4RouteExcludeAddress

                while (x4.hasNext()) {

                    val route =
                        x4.next()

                    builder.excludeRoute(
                        IpPrefix(
                            route.address(),
                            route.prefix()
                        )
                    )
                }

                val x6 =
                    options.inet6RouteExcludeAddress

                while (x6.hasNext()) {

                    val route =
                        x6.next()

                    builder.excludeRoute(
                        IpPrefix(
                            route.address(),
                            route.prefix()
                        )
                    )
                }

            } else {

                // Older Android versions use route ranges.

                val r4 =
                    options.inet4RouteRange

                while (r4.hasNext()) {

                    val prefix =
                        r4.next()

                    builder.addRoute(
                        prefix.address(),
                        prefix.prefix()
                    )
                }

                val r6 =
                    options.inet6RouteRange

                while (r6.hasNext()) {

                    val prefix =
                        r6.next()

                    builder.addRoute(
                        prefix.address(),
                        prefix.prefix()
                    )
                }
            }
        }

        // -----------------------------------------------------
        // Included applications
        // -----------------------------------------------------

        val included =
            options.includePackage

        while (included.hasNext()) {

            runCatching {

                builder.addAllowedApplication(
                    included.next()
                )
            }
        }

        // -----------------------------------------------------
        // Excluded applications
        // -----------------------------------------------------

        val excluded =
            options.excludePackage

        while (excluded.hasNext()) {

            runCatching {

                builder.addDisallowedApplication(
                    excluded.next()
                )
            }
        }

        // -----------------------------------------------------
        // HTTP proxy
        // -----------------------------------------------------

        val proxyServer =
            options.httpProxyServer

        if (
            options.isHTTPProxyEnabled &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        ) {

            builder.setHttpProxy(
                ProxyInfo.buildDirectProxy(
                    proxyServer,
                    options.httpProxyServerPort
                )
            )
        }

        // -----------------------------------------------------
        // Establish TUN
        // -----------------------------------------------------

        tunnelFd?.close()

        tunnelFd =
            builder.establish()
                ?: error(
                    "Android refused to establish the VPN interface"
                )

        return tunnelFd!!.fd
    }

    /*
     * Android < 10 doesn't have
     * ConnectivityManager.getConnectionOwnerUid().
     *
     * sing-box can use procfs on those versions.
     */
    override fun useProcFS(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int
    ): ConnectionOwner {

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {

            error(
                "Connection owner API requires Android 10+"
            )
        }

        val uid =
            getSystemService(
                android.net.ConnectivityManager::class.java
            ).getConnectionOwnerUid(
                ipProtocol,
                java.net.InetSocketAddress(
                    sourceAddress,
                    sourcePort
                ),
                java.net.InetSocketAddress(
                    destinationAddress,
                    destinationPort
                )
            )

        if (uid < 0) {
            error(
                "Connection owner not found"
            )
        }

        return ConnectionOwner().also {

            it.userId = uid

            val packages =
                packageManager.getPackagesForUid(uid)

            it.userName =
                packages?.firstOrNull()
                    ?: ""

            it.setAndroidPackageNames(
                StringArray(
                    packages
                        ?.asList()
                        ?.iterator()
                        ?: emptyList<String>().iterator()
                )
            )
        }
    }

    // ---------------------------------------------------------
    // Network interfaces
    // ---------------------------------------------------------

    override fun startDefaultInterfaceMonitor(
        listener: InterfaceUpdateListener
    ) = Unit

    override fun closeDefaultInterfaceMonitor(
        listener: InterfaceUpdateListener
    ) = Unit

    override fun getInterfaces():
            NetworkInterfaceIterator {

        val cm =
            getSystemService(
                android.net.ConnectivityManager::class.java
            )

        val javaInterfaces =
            java.net.NetworkInterface
                .getNetworkInterfaces()
                ?.toList()
                .orEmpty()

        val result =
            ArrayList<
                io.nekohasekai.libbox.NetworkInterface
            >()

        for (network in cm.allNetworks) {

            val lp =
                cm.getLinkProperties(
                    network
                )
                    ?: continue

            val caps =
                cm.getNetworkCapabilities(
                    network
                )
                    ?: continue

            val name =
                lp.interfaceName
                    ?: continue

            val ni =
                javaInterfaces.firstOrNull {
                    it.name == name
                }
                    ?: continue

            val box =
                io.nekohasekai.libbox.NetworkInterface()
                    .also {

                        it.name =
                            name

                        it.index =
                            ni.index

                        it.mtu =
                            runCatching {
                                ni.mtu
                            }.getOrDefault(1500)

                        it.dnsServer =
                            StringArray(
                                lp.dnsServers
                                    .mapNotNull {
                                        address ->
                                        address.hostAddress
                                    }
                                    .iterator()
                            )

                        it.gateway =
                            StringArray(
                                lp.routes
                                    .mapNotNull { route ->

                                        route.gateway
                                            ?.takeIf {
                                                gateway ->
                                                !gateway
                                                    .isAnyLocalAddress
                                            }
                                            ?.hostAddress
                                    }
                                    .iterator()
                            )

                        it.addresses =
                            StringArray(
                                ni.interfaceAddresses
                                    .map { address ->

                                        val host =
                                            address.address
                                                .hostAddress

                                        "$host/${address.networkPrefixLength}"
                                    }
                                    .iterator()
                            )

                        it.type =
                            when {

                                caps.hasTransport(
                                    android.net.NetworkCapabilities
                                        .TRANSPORT_WIFI
                                ) ->
                                    Libbox.InterfaceTypeWIFI

                                caps.hasTransport(
                                    android.net.NetworkCapabilities
                                        .TRANSPORT_CELLULAR
                                ) ->
                                    Libbox.InterfaceTypeCellular

                                caps.hasTransport(
                                    android.net.NetworkCapabilities
                                        .TRANSPORT_ETHERNET
                                ) ->
                                    Libbox.InterfaceTypeEthernet

                                else ->
                                    Libbox.InterfaceTypeOther
                            }

                        it.metered =
                            !caps.hasCapability(
                                android.net.NetworkCapabilities
                                    .NET_CAPABILITY_NOT_METERED
                            )

                        it.flags = 0
                    }

            result += box
        }

        val iterator =
            result.iterator()

        return object :
            NetworkInterfaceIterator {

            override fun hasNext():
                    Boolean =
                iterator.hasNext()

            override fun next():
                    io.nekohasekai.libbox.NetworkInterface =
                iterator.next()
        }
    }

    override fun underNetworkExtension():
            Boolean = false

    override fun includeAllNetworks():
            Boolean = false

    override fun clearDNSCache() =
        Unit

    override fun readWIFIState():
            WIFIState? = null

    override fun localDNSTransport():
            LocalDNSTransport? = null

    override fun startNeighborMonitor(
        listener: NeighborUpdateListener?
    ) = Unit

    override fun closeNeighborMonitor(
        listener: NeighborUpdateListener?
    ) = Unit

    // ---------------------------------------------------------
    // Unsupported platform functionality
    // ---------------------------------------------------------

    override fun usePlatformShell():
            Boolean = false

    override fun checkPlatformShell() =
        error(
            "Shell access is not available"
        )

    override fun openShellSession(
        user: PlatformUser?,
        command: String?,
        environ: StringIterator?,
        term: String?,
        rows: Int,
        cols: Int
    ): ShellSession =
        error(
            "Shell access is not available"
        )

    override fun readSystemSSHHostKey():
            String =
        error(
            "Not supported"
        )

    override fun lookupSFTPServer():
            String =
        error(
            "Not supported"
        )

    override fun tailscaleHostname():
            String =
        "${Build.MANUFACTURER} ${Build.MODEL}"

    override fun usePlatformBridge():
            Boolean = false

    override fun createBridge(
        options: BridgeOptions?
    ): BridgeSession =
        error(
            "Bridge is not available"
        )

    override fun usePlatformAutoRedirect():
            Boolean = false

    override fun createAutoRedirect(
        options: ByteArray?,
        handler: AutoRedirectHandler?
    ): AutoRedirectSession =
        error(
            "Auto redirect is not available"
        )

    override fun lookupUser(
        username: String?
    ): PlatformUser {

        return PlatformUser().also {

            it.username =
                username ?: packageName

            it.uid =
                android.os.Process.myUid()

            it.gid =
                android.os.Process.myUid()

            it.homeDir =
                filesDir.absolutePath
        }
    }

    override fun registerMyInterface(
        name: String?
    ) = Unit

    class StringArray(
        values: Iterator<String>
    ) : StringIterator {

        private val data =
            values.asSequence().toList()

        private var index =
            0

        override fun len():
                Int =
            data.size

        override fun hasNext():
                Boolean =
            index < data.size

        override fun next():
                String =
            data[index++]
    }
}
