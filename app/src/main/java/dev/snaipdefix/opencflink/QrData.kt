package dev.snaipdefix.opencflink

import android.net.Uri

/**
 * Mirrors net.easyconn.carman.common.base.QrResult.parseResult — the bike's QR is a URL
 * whose query string carries the connection params:
 *
 *   http://www.carbit.com.cn/...?modelid=...&sn=...&action=9
 *     &ssid=CFMOTO-xxxxxx&pwd=xxxxxx&auth=wpa2-psk
 *     &mac=xx:xx:xx:xx:xx:xx&name=CFMOTO-xxxxxx
 *
 * `action` is a bitmask: bit0=basic AP, bit1=AP+internet, bit3=Wi-Fi P2P, bit6=BT.
 */
data class QrData(
    val ssid: String,
    val pwd: String,
    val auth: String?,
    val mac: String?,
    val name: String?,
    val action: Int,
    val modelId: String?,
    val sn: String?,
    val channel: String?,
) {
    val supportsAp: Boolean get() = (action and 1) != 0 || (action and 2) != 0
    val supportsP2p: Boolean get() = (action and 8) != 0

    companion object {
        fun parse(raw: String): QrData? = try {
            val uri = Uri.parse(raw)
            val ssid = uri.getQueryParameter("ssid") ?: return null
            val pwd = uri.getQueryParameter("pwd") ?: return null
            QrData(
                ssid = ssid,
                pwd = pwd,
                auth = uri.getQueryParameter("auth"),
                mac = uri.getQueryParameter("mac"),
                name = uri.getQueryParameter("name"),
                action = uri.getQueryParameter("action")?.toIntOrNull() ?: 0,
                modelId = uri.getQueryParameter("modelid"),
                sn = uri.getQueryParameter("sn"),
                channel = uri.getQueryParameter("channel"),
            )
        } catch (_: Exception) {
            null
        }
    }
}
