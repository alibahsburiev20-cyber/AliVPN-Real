package com.alivpn.app

import org.json.JSONArray
import org.json.JSONObject

object SingBoxConfig {

    fun build(node: ProxyNode): String {
        val config = JSONObject()
            .put("log", JSONObject().put("level", "warn"))
            .put(
                "dns",
                JSONObject()
                    .put(
                        "servers",
                        JSONArray().put(
                            JSONObject()
                                .put("tag", "remote-dns")
                                .put("type", "https")
                                .put("server", "1.1.1.1")
                                .put("detour", "proxy")
                        )
                    )
                    .put("final", "remote-dns")
                    .put("strategy", "prefer_ipv4")
            )
            .put(
                "inbounds",
                JSONArray().put(
                    JSONObject()
                        .put("type", "tun")
                        .put("tag", "tun-in")
                        .put(
                            "address",
                            JSONArray()
                                .put("172.19.0.1/30")
                                .put("fdfe:dcba:9876::1/126")
                        )
                        .put("mtu", 1400)
                        .put("auto_route", true)
                        .put("stack", "system")
                        .put("strict_route", false)
                        .put("dns_mode", "hijack")
                )
            )
            .put(
                "outbounds",
                JSONArray()
                    .put(node.outbound)
                    .put(JSONObject().put("type", "direct").put("tag", "direct"))
                    .put(JSONObject().put("type", "block").put("tag", "block"))
            )
            .put(
                "route",
                JSONObject()
                    .put("auto_detect_interface", true)
                    .put(
                        "rules",
                        JSONArray().put(
                            JSONObject()
                                .put("protocol", JSONArray().put("dns"))
                                .put("action", "hijack-dns")
                        )
                    )
                    .put("final", "proxy")
            )

        return config.toString()
    }
}
