package com.alivpn.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.IpPrefix
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import io.nekohasekai.libbox.BridgeOptions
import io.nekohasekai.libbox.BridgeSession
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.NeighborUpdateListener
import io.nekohasekai.libbox.NetworkInterface
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.PlatformUser
import io.nekohasekai.libbox.ShellSession
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import java.net.InetSocketAddress

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

    // ---------------------------------------------------------
    // Android service
    // ---------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

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

        if (commandServer != null) {
            return START_NOT_STICKY
        }

        currentConfig = config

        Thread {
            try {
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

                updateNotification(
                    "AliVPN",
                    "Проверяем соединение…"
                )

                broadcastState(
                    STATE_CONNECTING,
                    null
                )

            } catch (e: Throwable) {

                Log.e(
                    "AliVPN",
                    "Core start failed",
                    e
                )

                broadcastState(
                    STATE_ERROR,
                    e.message ?: "Не удалось запустить VPN"
                )

                closeCore()
                stopSelf()
            }
        }.start()

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        closeCore()

        broadcastState(
            STATE_DISCONNECTED,
            null
        )

        super.onDestroy()
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

    // ---------------------------------------------------------
    // Notifications
    // ---------------------------------------------------------

    private fun createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val manager =
                getSystemService(
                    NotificationManager::class.java
                )

            manager.createNotificationChannel(
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

        startForeground(
            NOTIFICATION_ID,
            buildNotification(
                title,
                text
            )
        )
    }

    private fun updateNotification(
        title: String,
        text: String
    ) {

        val manager =
            getSystemService(
                NotificationManager::class.java
            )

        manager.notify(
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
    // CommandServerHandler — libbox 1.14.0
    // ---------------------------------------------------------

    override fun serviceStop(): Unit {
        stopSelf()
    }

    override fun serviceReload(): Unit {

        val config =
            currentConfig ?: return

        commandServer?.startOrReloadService(
            config,
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
    ): Unit {
        // System proxy is not used.
    }

    override fun triggerNativeCrash(): Unit {
        throw UnsupportedOperationException(
            "Disabled"
        )
    }

    override fun writeDebugMessage(
        message: String
    ): Unit {

        Log.d(
            "AliVPN",
            message
        )
    }

    override fun connectSSHAgent(): Int {
        return -1
    }

    // ---------------------------------------------------------
    // PlatformInterface — libbox 1.14.0
    // ---------------------------------------------------------

    override fun localDNSTransport():
            LocalDNSTransport? {
        return null
    }

    override fun usePlatformAutoDetectInterfaceControl():
            Boolean {
        return true
    }

    override fun autoDetectInterfaceControl(
        fd: Int
    ): Unit {

        if (!protect(fd)) {
            Log.w(
                "AliVPN",
                "protect($fd) failed"
            )
        }
    }

    // ---------------------------------------------------------
    // TUN
    // ---------------------------------------------------------

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
        // TUN IPv4 addresses
        // -----------------------------------------------------

        val ipv4 =
            options.inet4Address

        while (ipv4.hasNext()) {

            val prefix =
                ipv4.next()

            builder.addAddress(
                prefix.address(),
                prefix.prefix()
            )
        }

        // -----------------------------------------------------
        // TUN IPv6 addresses
        // -----------------------------------------------------

        val ipv6 =
            options.inet6Address

        while (ipv6.hasNext()) {

            val prefix =
                ipv6.next()

            builder.addAddress(
                prefix.address(),
                prefix.prefix()
            )
        }

        // -----------------------------------------------------
        // DNS + routes
        // -----------------------------------------------------

        if (options.autoRoute) {

            val dns =
                options.dnsServerAddress

            while (dns.hasNext()) {

                builder.addDnsServer(
                    dns.next()
                )
            }

            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.TIRAMISU
            ) {

                // IPv4 routes

                val routes4 =
                    options.inet4RouteAddress

                while (routes4.hasNext()) {

                    val route =
                        routes4.next()

                    builder.addRoute(
                        route.address(),
                        route.prefix()
                    )
                }

                // IPv6 routes

                val routes6 =
                    options.inet6RouteAddress

                while (routes6.hasNext()) {

                    val route =
                        routes6.next()

                    builder.addRoute(
                        route.address(),
                        route.prefix()
                    )
                }

                // IPv4 excluded routes

                val excluded4 =
                    options.inet4RouteExcludeAddress

                while (excluded4.hasNext()) {

                    val route =
                        excluded4.next()

                    builder.excludeRoute(
                        IpPrefix(
                            route.address(),
                            route.prefix()
                        )
                    )
                }

                // IPv6 excluded routes

                val excluded6 =
                    options.inet6RouteExcludeAddress

                while (excluded6.hasNext()) {

                    val route =
                        excluded6.next()

                    builder.excludeRoute(
                        IpPrefix(
                            route.address(),
                            route.prefix()
                        )
                    )
                }

            } else {

                // Android < 13

                val routes4 =
                    options.inet4RouteRange

                while (routes4.hasNext()) {

                    val route =
                        routes4.next()

                    builder.addRoute(
                        route.address(),
                        route.prefix()
                    )
                }

                val routes6 =
                    options.inet6RouteRange

                while (routes6.hasNext()) {

                    val route =
                        routes6.next()

                    builder.addRoute(
                        route.address(),
                        route.prefix()
                    )
                }
            }
        }

        // -----------------------------------------------------
        // Allowed applications
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
        // Disallowed applications
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

        if (
            options.isHTTPProxyEnabled &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        ) {

            builder.setHttpProxy(
                ProxyInfo.buildDirectProxy(
                    options.httpProxyServer,
                    options.httpProxyServerPort
                )
            )
        }

        // -----------------------------------------------------
        // Establish Android TUN
        // -----------------------------------------------------

        tunnelFd?.close()

        tunnelFd =
            builder.establish()
                ?: error(
                    "Android refused to establish VPN interface"
                )

        return tunnelFd!!.fd
    }

    // ---------------------------------------------------------
    // Connection owner
    // ---------------------------------------------------------

    override fun useProcFS(): Boolean {

        return Build.VERSION.SDK_INT <
                Build.VERSION_CODES.Q
    }

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int
    ): ConnectionOwner {

        if (
            Build.VERSION.SDK_INT <
            Build.VERSION_CODES.Q
        ) {

            error(
                "Connection owner API requires Android 10+"
            )
        }

        val connectivityManager =
            getSystemService(
                android.net.ConnectivityManager::class.java
            )

        val uid =
            connectivityManager.getConnectionOwnerUid(
                ipProtocol,
                InetSocketAddress(
                    sourceAddress,
                    sourcePort
                ),
                InetSocketAddress(
                    destinationAddress,
                    destinationPort
                )
            )

        if (uid < 0) {
            error(
                "Connection owner not found"
            )
        }

        val packages =
            packageManager.getPackagesForUid(uid)

        return ConnectionOwner().also {

            it.userId = uid

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
    // Interface monitor
    // ---------------------------------------------------------

    override fun startDefaultInterfaceMonitor(
        listener: InterfaceUpdateListener
    ): Unit {
        // Android VpnService handles the active network.
    }

    override fun closeDefaultInterfaceMonitor(
        listener: InterfaceUpdateListener
    ): Unit {
        // Nothing to close.
    }

    // ---------------------------------------------------------
    // Network interfaces
    // ---------------------------------------------------------

    override fun getInterfaces():
            NetworkInterfaceIterator {

        val connectivityManager =
            getSystemService(
                android.net.ConnectivityManager::class.java
            )

        val javaInterfaces =
            java.net.NetworkInterface
                .getNetworkInterfaces()
                ?.toList()
                .orEmpty()

        val result =
            ArrayList<NetworkInterface>()

        for (
            network in
            connectivityManager.allNetworks
        ) {

            val linkProperties =
                connectivityManager
                    .getLinkProperties(network)
                    ?: continue

            val capabilities =
                connectivityManager
                    .getNetworkCapabilities(network)
                    ?: continue

            val name =
                linkProperties.interfaceName
                    ?: continue

            val javaInterface =
                javaInterfaces.firstOrNull {
                    it.name == name
                }
                    ?: continue

            val networkInterface =
                NetworkInterface().also {

                    it.name = name

                    it.index =
                        javaInterface.index

                    it.mtu =
                        runCatching {
                            javaInterface.mtu
                        }.getOrDefault(1500)

                    it.dnsServer =
                        StringArray(
                            linkProperties
                                .dnsServers
                                .mapNotNull {
                                    address ->
                                    address.hostAddress
                                }
                                .iterator()
                        )

                    it.gateway =
                        StringArray(
                            linkProperties
                                .routes
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
                            javaInterface
                                .interfaceAddresses
                                .map { address ->

                                    val host =
                                        address
                                            .address
                                            .hostAddress

                                    "$host/${address.networkPrefixLength}"
                                }
                                .iterator()
                        )

                    it.type =
                        when {

                            capabilities.hasTransport(
                                android.net.NetworkCapabilities
                                    .TRANSPORT_WIFI
                            ) ->
                                Libbox.InterfaceTypeWIFI

                            capabilities.hasTransport(
                                android.net.NetworkCapabilities
                                    .TRANSPORT_CELLULAR
                            ) ->
                                Libbox.InterfaceTypeCellular

                            capabilities.hasTransport(
                                android.net.NetworkCapabilities
                                    .TRANSPORT_ETHERNET
                            ) ->
                                Libbox.InterfaceTypeEthernet

                            else ->
                                Libbox.InterfaceTypeOther
                        }

                    it.metered =
                        !capabilities.hasCapability(
                            android.net.NetworkCapabilities
                                .NET_CAPABILITY_NOT_METERED
                        )

                    it.flags = 0
                }

            result += networkInterface
        }

        val iterator =
            result.iterator()

        return object : NetworkInterfaceIterator {

            override fun hasNext():
                    Boolean {
                return iterator.hasNext()
            }

            override fun next():
                    NetworkInterface {
                return iterator.next()
            }
        }
    }

    // ---------------------------------------------------------
    // Other platform functions
    // ---------------------------------------------------------

    override fun underNetworkExtension():
            Boolean {
        return false
    }

    override fun includeAllNetworks():
            Boolean {
        return false
    }

    override fun readWIFIState():
            WIFIState? {
        return null
    }

    override fun clearDNSCache(): Unit {
        // Nothing to clear.
    }

    override fun startNeighborMonitor(
        listener: NeighborUpdateListener
    ): Unit {
        // Not required for AliVPN.
    }

    override fun closeNeighborMonitor(
        listener: NeighborUpdateListener
    ): Unit {
        // Not required for AliVPN.
    }

    override fun registerMyInterface(
        name: String
    ): Unit {
        // Nothing to register.
    }

    // ---------------------------------------------------------
    // Unsupported shell functions
    // ---------------------------------------------------------

    override fun usePlatformShell():
            Boolean {
        return false
    }

    override fun checkPlatformShell(): Unit {
        error(
            "Shell access is not available"
        )
    }

    override fun openShellSession(
        user: PlatformUser,
        command: String,
        environ: StringIterator,
        term: String,
        rows: Int,
        cols: Int
    ): ShellSession {

        error(
            "Shell access is not available"
        )
    }

    override fun lookupUser(
        username: String
    ): PlatformUser {

        return PlatformUser().also {

            it.username =
                username

            it.uid =
                android.os.Process.myUid()

            it.gid =
                android.os.Process.myUid()

            it.homeDir =
                filesDir.absolutePath

            it.shell =
                "/system/bin/sh"
        }
    }

    override fun lookupSFTPServer():
            String {
        error(
            "SFTP server is not available"
        )
    }

    override fun readSystemSSHHostKey():
            String {
        error(
            "SSH host key is not available"
        )
    }

    override fun tailscaleHostname():
            String {

        return "${Build.MANUFACTURER} ${Build.MODEL}"
    }

    // ---------------------------------------------------------
    // Bridge
    // ---------------------------------------------------------

    override fun usePlatformBridge():
            Boolean {
        return false
    }

    override fun createBridge(
        options: BridgeOptions
    ): BridgeSession {

        error(
            "Bridge is not available"
        )
    }

    // ---------------------------------------------------------
    // String iterator
    // ---------------------------------------------------------

    private class StringArray(
        values: Iterator<String>
    ) : StringIterator {

        private val data =
            values.asSequence().toList()

        private var index = 0

        override fun len(): Int {
            return data.size
        }

        override fun hasNext(): Boolean {
            return index < data.size
        }

        override fun next(): String {
            return data[index++]
        }
    }
}
