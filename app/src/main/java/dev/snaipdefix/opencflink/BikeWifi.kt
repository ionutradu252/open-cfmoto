package dev.snaipdefix.opencflink

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiNetworkSpecifier

/**
 * Joins the bike's hotspot using WifiNetworkSpecifier (no system Wi-Fi config required).
 *
 * The system shows a dialog asking the user to accept the network. After acceptance, the
 * resulting Network object is process-bound so our TCP sockets and mDNS lookups go through
 * the bike's interface (which has no internet — that's fine).
 *
 * QR for this bike:
 *   ssid = CFMOTO-f46457
 *   pwd  = 59a9cddc94
 *   auth = wpa2-psk
 */
object BikeWifi {
    /**
     * How long to wait for the bike's AP before declaring the attempt failed.
     *
     * MUST be passed to requestNetwork: without a timeout the request waits FOREVER and never
     * reports anything. That's what stranded [BikeReconnector] after a single retry — the bike was
     * off, the re-request sat silently pending, and neither onAvailable, onLost nor onUnavailable
     * ever fired, so nothing counted the attempt and nothing ever gave up. The encoder and wake lock
     * then ran for as long as the phone was left alone (7+ min observed, 2026-07-16 logs).
     */
    private const val JOIN_TIMEOUT_MS = 12_000

    private var callback: ConnectivityManager.NetworkCallback? = null
    var currentNetwork: Network? = null
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
             * Log the radio band once per join.
             *
             * 2.4 GHz is shared with Bluetooth, and the phone time-slices between them — so if this
             * link is on 2.4 GHz, our video traffic is competing with the A2DP feeding your helmet.
             * That's the difference between "audio stutters at speed" being explainable or not, and
             * it costs one line to know instead of guess.
             */
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (loggedLink) return
                val info = caps.transportInfo as? WifiInfo ?: return
                loggedLink = true
                val mhz = info.frequency
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
        log("requesting Wi-Fi join: $ssid …")
        // Timeout is mandatory here — see [JOIN_TIMEOUT_MS].
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
