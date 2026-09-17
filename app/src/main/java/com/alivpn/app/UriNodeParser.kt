package com.alivpn.app

import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.nio.charset.StandardCharsets

data class ProxyNode(
    val name: String,
    val outbound: JSONObject
)

object UriNodeParser {

    fun parse(line: String): ProxyNode? {
        val s = line.trim()
        if (s.isEmpty() || s.startsWith("#")) return null

        return when {
            s.startsWith("vless://", true) -> parseVless(s)
            s.startsWith("trojan://", true) -> parseTrojan(s)
            s.startsWith("ss://", true) -> parseShadowsocks(s)
            s.startsWith("vmess://", true) -> parseVmess(s)
            else -> null
        }
    }

    private fun parseVless(raw: String): ProxyNode? {
        val uri = Uri.parse(raw)
        val host = uri.host ?: return null
        val port = uri.port.takeIf { it > 0 } ?: return null
        val uuid = uri.userInfo()?.takeIf { it.isNotBlank() } ?: return null
        val q = Query(uri)
        val security = q.get("security").lowercase()
        val transportType = q.get("type", "tcp").lowercase()

        val out = JSONObject()
            .put("type", "vless")
            .put("tag", "proxy")
            .put("server", host)
            .put("server_port", port)
            .put("uuid", uuid)

        q.getOrNull("flow")?.takeIf { it.isNotBlank() }?.let { out.put("flow", it) }

        addTls(out, q, security)

        when (transportType) {
            "ws" -> addWs(out, q)
            "grpc" -> addGrpc(out, q)
            "httpupgrade" -> addHttpUpgrade(out, q)
            "http" -> addHttp(out, q)
            "tcp", "raw", "" -> {
                if (q.get("headerType").equals("http", true)) addHttp(out, q)
            }
            else -> return null
        }

        return ProxyNode(
            displayName(uri.fragment?.takeIf { it.isNotBlank() } ?: "$host:$port"),
            out
        )
    }

    private fun parseTrojan(raw: String): ProxyNode? {
        val uri = Uri.parse(raw)
        val host = uri.host ?: return null
        val port = uri.port.takeIf { it > 0 } ?: return null
        val password = uri.userInfo()?.takeIf { it.isNotBlank() } ?: return null
        val q = Query(uri)
        val security = q.get("security").lowercase()
        val transportType = q.get("type", "tcp").lowercase()

        val out = JSONObject()
            .put("type", "trojan")
            .put("tag", "proxy")
            .put("server", host)
            .put("server_port", port)
            .put("password", password)

        addTls(out, q, security)

        when (transportType) {
            "ws" -> addWs(out, q)
            "grpc" -> addGrpc(out, q)
            "httpupgrade" -> addHttpUpgrade(out, q)
            "http" -> addHttp(out, q)
            "tcp", "raw", "" -> {
                if (q.get("headerType").equals("http", true)) addHttp(out, q)
            }
            else -> return null
        }

        return ProxyNode(
            displayName(uri.fragment?.takeIf { it.isNotBlank() } ?: "$host:$port"),
            out
        )
    }

    private fun parseShadowsocks(raw: String): ProxyNode? {
        val uri = Uri.parse(raw)
        val host = uri.host ?: return null
        val port = uri.port.takeIf { it > 0 } ?: return null

        val userInfo = uri.userInfo()?.trim() ?: return null
        val decoded = runCatching {
            String(
                Base64.decode(normalizeBase64(userInfo), Base64.DEFAULT or Base64.NO_WRAP),
                StandardCharsets.UTF_8
            )
        }.getOrNull() ?: return null

        val idx = decoded.indexOf(':')
        if (idx <= 0) return null

        val method = decoded.substring(0, idx)
        val password = decoded.substring(idx + 1)

        val out = JSONObject()
            .put("type", "shadowsocks")
            .put("tag", "proxy")
            .put("server", host)
            .put("server_port", port)
            .put("method", method)
            .put("password", password)

        return ProxyNode(
            displayName(uri.fragment?.takeIf { it.isNotBlank() } ?: "$host:$port"),
            out
        )
    }

    private fun parseVmess(raw: String): ProxyNode? {
        val uri = Uri.parse(raw)
        val encoded = uri.schemeSpecificPart.substringAfter("//").substringBefore("#")
        val jsonText = runCatching {
            String(
                Base64.decode(normalizeBase64(encoded), Base64.DEFAULT or Base64.NO_WRAP),
                StandardCharsets.UTF_8
            )
        }.getOrNull() ?: return null

        val j = runCatching { JSONObject(jsonText) }.getOrNull() ?: return null
        val host = j.optString("add").takeIf { it.isNotBlank() } ?: return null
        val port = j.optInt("port").takeIf { it > 0 } ?: return null
        val uuid = j.optString("id").takeIf { it.isNotBlank() } ?: return null

        val out = JSONObject()
            .put("type", "vmess")
            .put("tag", "proxy")
            .put("server", host)
            .put("server_port", port)
            .put("uuid", uuid)
            .put("alter_id", 0)

        val security = j.optString("tls").lowercase()
        if (security == "tls") {
            val tls = JSONObject()
                .put("enabled", true)
                .put("server_name", j.optString("sni", j.optString("host", host)))
            if (j.optString("fp").isNotBlank()) {
                tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", j.optString("fp")))
            }
            out.put("tls", tls)
        }

        when (j.optString("net", "tcp").lowercase()) {
            "ws" -> {
                out.put(
                    "transport",
                    JSONObject()
                        .put("type", "ws")
                        .put("path", j.optString("path", "/"))
                        .put(
                            "headers",
                            JSONObject().apply {
                                j.optString("host").takeIf { it.isNotBlank() }?.let { put("Host", it) }
                            }
                        )
                )
            }
            "grpc" -> {
                out.put(
                    "transport",
                    JSONObject()
                        .put("type", "grpc")
                        .put("service_name", j.optString("path", "TunService"))
                )
            }
            "httpupgrade" -> {
                out.put(
                    "transport",
                    JSONObject()
                        .put("type", "httpupgrade")
                        .put("host", j.optString("host", host))
                        .put("path", j.optString("path", "/"))
                )
            }
            "tcp", "" -> Unit
            else -> return null
        }

        return ProxyNode(
            displayName(uri.fragment?.takeIf { it.isNotBlank() } ?: j.optString("ps").ifBlank { "$host:$port" }),
            out
        )
    }

    private fun addTls(out: JSONObject, q: Query, security: String) {
        when (security) {
            "tls" -> {
                val tls = JSONObject().put("enabled", true)
                q.getOrNull("sni")?.takeIf { it.isNotBlank() }?.let { tls.put("server_name", it) }
                val insecure = q.boolean("insecure", q.boolean("allowInsecure", false))
                tls.put("insecure", insecure)
                addFingerprint(tls, q)
                q.getOrNull("alpn")?.takeIf { it.isNotBlank() }?.let { tls.put("alpn", JSONObject().run { org.json.JSONArray(it.split(',').map(String::trim)) }) }
                out.put("tls", tls)
            }
            "reality" -> {
                val tls = JSONObject()
                    .put("enabled", true)
                    .put("server_name", q.get("sni"))
                    .put("reality", JSONObject().apply {
                        put("enabled", true)
                        put("public_key", q.get("pbk"))
                        q.getOrNull("sid")?.takeIf { it.isNotBlank() }?.let { put("short_id", it) }
                    })
                addFingerprint(tls, q)
                out.put("tls", tls)
            }
        }
    }

    private fun addFingerprint(tls: JSONObject, q: Query) {
        q.getOrNull("fp")?.takeIf { it.isNotBlank() }?.let {
            tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", it))
        }
    }

    private fun addWs(out: JSONObject, q: Query) {
        val headers = JSONObject()
        q.getOrNull("host")?.takeIf { it.isNotBlank() }?.let { headers.put("Host", it) }
        out.put(
            "transport",
            JSONObject()
                .put("type", "ws")
                .put("path", q.get("path", "/"))
                .put("headers", headers)
        )
    }

    private fun addGrpc(out: JSONObject, q: Query) {
        out.put(
            "transport",
            JSONObject()
                .put("type", "grpc")
                .put("service_name", q.get("serviceName", q.get("service_name", "TunService")))
        )
    }

    private fun addHttpUpgrade(out: JSONObject, q: Query) {
        val headers = JSONObject()
        q.getOrNull("host")?.takeIf { it.isNotBlank() }?.let { headers.put("Host", it) }
        out.put(
            "transport",
            JSONObject()
                .put("type", "httpupgrade")
                .put("host", q.get("host", ""))
                .put("path", q.get("path", "/"))
                .put("headers", headers)
        )
    }

    private fun addHttp(out: JSONObject, q: Query) {
        val hosts = org.json.JSONArray()
        q.getOrNull("host")?.takeIf { it.isNotBlank() }?.let { hosts.put(it) }
        out.put(
            "transport",
            JSONObject()
                .put("type", "http")
                .put("host", hosts)
                .put("path", q.get("path", "/"))
        )
    }

    private fun displayName(name: String): String {
        return name.replace(Regex("[\\p{Cntrl}]"), "").take(80).ifBlank { "AliVPN node" }
    }

    private fun normalizeBase64(value: String): String {
        val compact = value.replace('-', '+').replace('_', '/')
        return compact + "=".repeat((4 - compact.length % 4) % 4)
    }

    private fun Uri.userInfo(): String? = encodedUserInfo?.let {
        Uri.decode(it)
    }

    private class Query(private val uri: Uri) {
        fun get(name: String, default: String = "") = uri.getQueryParameter(name) ?: default
        fun getOrNull(name: String) = uri.getQueryParameter(name)
        fun boolean(name: String, default: Boolean): Boolean {
            val v = getOrNull(name)?.lowercase() ?: return default
            return when (v) {
                "1", "true", "yes", "on" -> true
                "0", "false", "no", "off" -> false
                else -> default
            }
        }
    }
}
