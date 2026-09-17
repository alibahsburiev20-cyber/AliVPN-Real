package com.alivpn.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

object PublicConfigRepository {

    /*
     * This is AliVPN's generated list.
     *
     * GitHub Actions aggregates sources into:
     *
     * output/all.txt
     */
    private const val SOURCE =
        "https://raw.githubusercontent.com/" +
            "alibahsburiev20-cyber/AliVPN-Real/" +
            "main/output/all.txt"

    private const val IP_CHECK_URL =
        "https://api.ipify.org"

    suspend fun load():
            List<ProxyNode> =
        withContext(Dispatchers.IO) {

            val connection =
                (URL(SOURCE).openConnection()
                    as HttpURLConnection).apply {

                    connectTimeout =
                        12000

                    readTimeout =
                        20000

                    requestMethod =
                        "GET"

                    setRequestProperty(
                        "User-Agent",
                        "AliVPN/0.2"
                    )

                    setRequestProperty(
                        "Accept",
                        "text/plain"
                    )

                    instanceFollowRedirects =
                        true
                }

            try {

                val responseCode =
                    connection.responseCode

                if (
                    responseCode !in 200..299
                ) {

                    error(
                        "Не удалось получить список VPN: HTTP $responseCode"
                    )
                }

                val text =
                    connection
                        .inputStream
                        .bufferedReader()
                        .use {
                            it.readText()
                        }

                text
                    .lineSequence()
                    .map {
                        it.trim()
                    }
                    .filter {
                        it.isNotBlank()
                    }
                    .filterNot {
                        it.startsWith("#")
                    }
                    .mapNotNull {
                        runCatching {
                            UriNodeParser.parse(it)
                        }.getOrNull()
                    }
                    .distinctBy {
                        it.outbound.toString()
                    }
                    .toList()

            } finally {

                connection.disconnect()
            }
        }

    /*
     * Returns the current public IP.
     *
     * IMPORTANT:
     * This method itself does not magically force traffic
     * through the VPN. The Android process must already be
     * routed by VpnService.
     */
    fun publicIp():
            String? {

        return runCatching {

            val connection =
                (URL(IP_CHECK_URL)
                    .openConnection()
                    as HttpURLConnection).apply {

                    connectTimeout =
                        7000

                    readTimeout =
                        7000

                    requestMethod =
                        "GET"

                    setRequestProperty(
                        "User-Agent",
                        "AliVPN/0.2"
                    )

                    setRequestProperty(
                        "Accept",
                        "text/plain"
                    )

                    instanceFollowRedirects =
                        true
                }

            try {

                if (
                    connection.responseCode
                    !in 200..299
                ) {
                    return@runCatching null
                }

                connection
                    .inputStream
                    .bufferedReader()
                    .use {
                        it.readLine()
                            ?.trim()
                    }

            } finally {

                connection.disconnect()
            }

        }.getOrNull()
            ?.takeIf {
                it.isNotBlank()
            }
    }
}
