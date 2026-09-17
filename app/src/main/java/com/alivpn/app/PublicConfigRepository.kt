package com.alivpn.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

object PublicConfigRepository {
    private const val SOURCE =
        "https://raw.githubusercontent.com/aviamastersgh/vpn-free-russia/main/verified_configs.txt"

    suspend fun load(): List<ProxyNode> = withContext(Dispatchers.IO) {
        val connection = (URL(SOURCE).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12000
            readTimeout = 20000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "AliVPN/0.2")
        }

        try {
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            text.lineSequence()
                .mapNotNull { UriNodeParser.parse(it) }
                .distinctBy { it.outbound.toString() }
                .toList()
        } finally {
            connection.disconnect()
        }
    }

    fun publicIp(): String? {
        return runCatching {
            val c = (URL("https://api.ipify.org").openConnection() as HttpURLConnection).apply {
                connectTimeout = 7000
                readTimeout = 7000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "AliVPN/0.2")
            }
            try {
                c.inputStream.bufferedReader().use { it.readLine()?.trim() }
            } finally {
                c.disconnect()
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }
}
