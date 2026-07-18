package dev.snaipdefix.opencflink

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiNetworkSpecifier

/**
 * joins the bike's hotspot with WifiNetworkSpecifier, no system wifi config needed.
 *
 * android shows a dialog to accept the network. after that the Network is process-bound so our tcp
 * sockets and mdns go out over the bike's interface (which has no internet, that's fine).
 */
object BikeWifi {
    /**
     * how long to wait for the bike's ap before calling the attempt failed.
     *
     * this MUST be passed to requestNetwork. without a timeout the request waits forever and never
     * reports anything, which is what stranded BikeReconnector after one retry: bike off, re-request
     * sat pending, and none of onAvailable/onLost/onUnavailable ever fired, so nothing counted the
     * attempt and nothing gave up. encoder and wake lock then ran until the phone was noticed (7+
     * min in the 2026-07-16 logs).
     */
    private const val JOIN_TIMEOUT_MS = 12_000

    private var callback: ConnectivityManager.NetworkCallback? = null
    var currentNetwork: Network? = null
        private set

    /** ssid of the bike we're on, the key LearnedPanels files its screen size under */
    @Volatile
    var currentSsid: String? = null
        private set

    fun join(
        context: Context,
        ssid: String,
        psk: String,
        onAvailable: (Network) -> Unit,
        onLost: () -> Unit,
        onUnavailable: () -> Unit,
        log: (String) -> Unit,
    ) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        callback?.let {            try { cm.unregisterNetworkCallback(it) } catch (_: Exception) {}
        }

        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(psk)
            .build()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        var loggedLink = false
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                currentNetwork = network
                cm.bindProcessToNetwork(network)
                log("Wi-Fi joined: $ssid (network=$network, bound)")
                onAvailable(network)
            }

            /**
             * log the radio band once per join. 2.4ghz is shared with bluetooth and the phone time
             * slices between them, so on 2.4 our video is competing with the a2dp feeding the
             * helmet. one line to know instead of guess when someone reports audio stutter.
             */
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (loggedLink) return
                val info = caps.transportInfo as? WifiInfo ?: return
                loggedLink = true
                val mhz = info.frequency
                AppStatus.wifiMhz = mhz
                val band = if (mhz in 2400..2500) "2.4GHz — SHARED WITH BLUETOOTH" else "${mhz / 1000}GHz"
                log("[wifi] link: ${mhz}MHz ($band), rssi=${info.rssi}dBm, tx=${info.txLinkSpeedMbps}Mbps, rx=${info.rxLinkSpeedMbps}Mbps")
            }

            override fun onLost(network: Network) {
                log("Wi-Fi lost: $network")
                currentNetwork = null
                onLost()
            }

            override fun onUnavailable() {
                log("Wi-Fi join unavailable after ${JOIN_TIMEOUT_MS / 1000}s (bike off, out of range, or declined)")
                currentNetwork = null
                onUnavailable()
            }
        }
        callback = cb
        currentSsid = ssid
        log("requesting Wi-Fi join: $ssid …")
        // the timeout is mandatory, see JOIN_TIMEOUT_MS
        cm.requestNetwork(request, cb, JOIN_TIMEOUT_MS)
    }

    fun leave(context: Context, log: (String) -> Unit) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        callback?.let {
            try { cm.unregisterNetworkCallback(it) } catch (_: Exception) {}
        }
        callback = null
        cm.bindProcessToNetwork(null)
        currentNetwork = null
        log("Wi-Fi released")
    }
}
