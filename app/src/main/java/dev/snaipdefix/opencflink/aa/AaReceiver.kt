// uses AGPLv3 code ported from headunit-revived.
// runs the loopback "self mode" android auto receiver:
// 1. listen on tcp 127.0.0.1:5288 (+ nsd _aawireless._tcp)
// 2. launch google's WirelessStartupActivity pointed at 127.0.0.1:5288 (no vpn)
// 3. accept the socket, do the aap version+ssl handshake, point the decoder at the encoder
// surface, start the read loop. AA video then flows into the encoder.
package dev.snaipdefix.opencflink.aa

import android.content.Context
import dev.snaipdefix.opencflink.AaVideoBridge
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.view.Surface
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class AaReceiver(
    private val context: Context,
    private val encoderSurface: Surface,
    private val log: (String) -> Unit,
) {
    companion object {
        const val PORT = 5288
    }

    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null

    @Volatile private var transport: AapTransport? = null
    @Volatile private var connection: SocketAccessoryConnection? = null
    @Volatile private var steadyVideoFired = false
    private val videoDecoder = VideoDecoder().apply {
        fallbackWidth = ServiceDiscoveryResponse.AA_WIDTH
        fallbackHeight = ServiceDiscoveryResponse.AA_HEIGHT
        onFpsChanged = { fps ->
            log("[AA] decode fps=$fps")
            dev.snaipdefix.opencflink.AppStatus.aaFps = fps
            if (!steadyVideoFired && fps >= 25) {
                steadyVideoFired = true
                log("[AA] steady video reached (fps=$fps) — signalling ready for bike hand-off")
                try { AaVideoBridge.onSteadyVideo?.invoke() } catch (_: Exception) {}
            }
        }
    }

    /** wire up conscrypt + logging before anything touches ssl */
    fun start() {
        if (running) { log("[AA] already running"); return }
        running = true
        AaLog.sink = log
        ConscryptInitializer.initialize()

        try {
            serverSocket = ServerSocket(PORT).apply { reuseAddress = true }
            log("[AA] WirelessServer listening on :$PORT")
        } catch (e: Exception) {
            log("[AA] failed to bind :$PORT — ${e.message}")
            running = false
            return
        }

        registerNsd()

        acceptThread = thread(name = "aa-accept", isDaemon = true) { acceptLoop() }
        // MainActivity triggers self mode from the foreground (AaSelfMode.trigger) because of the
        // background activity launch rules
    }

    fun stop() {
        running = false
        try { transport?.microphone?.stop("receiver stop") } catch (_: Exception) {}
        try { transport?.nightSender?.stop() } catch (_: Exception) {}
        dev.snaipdefix.opencflink.AppStatus.aaConnected = false
        AaVideoBridge.touchSink = null
        AaVideoBridge.keySink = null
        AaVideoBridge.scrollSink = null
        try { transport?.quit() } catch (_: Exception) {}
        transport = null
        try { connection?.disconnect() } catch (_: Exception) {}
        connection = null
        try { videoDecoder.stop("AaReceiver.stop") } catch (_: Exception) {}
        unregisterNsd()
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        acceptThread?.interrupt(); acceptThread = null
        AaLog.sink = null
        log("[AA] receiver stopped")
    }

    private fun acceptLoop() {
        val ss = serverSocket ?: return
        while (running) {
            val client = try {
                ss.accept()
            } catch (e: Exception) {
                if (running) log("[AA] accept ended: ${e.message}")
                break
            }
            log("[AA] <<< Android Auto connected from ${client.inetAddress?.hostAddress}")
            if (transport != null) {
                log("[AA] already have a session — dropping extra connection")
                try { client.close() } catch (_: Exception) {}
                continue
            }
            thread(name = "aa-session", isDaemon = true) { handleConnection(client) }
        }
    }

    private fun handleConnection(client: Socket) {
        val conn = SocketAccessoryConnection(client)
        connection = conn
        val t = AapTransport(videoDecoder, context)
        t.onQuit = { clean ->
            log("[AA] transport quit (clean=$clean, userExit=${t.wasUserExit})")
            try { t.microphone?.stop("transport quit") } catch (_: Exception) {}
            t.microphone = null
            try { t.nightSender?.stop() } catch (_: Exception) {}
            t.nightSender = null
            dev.snaipdefix.opencflink.AppStatus.aaConnected = false
            AaVideoBridge.touchSink = null
            AaVideoBridge.keySink = null
            AaVideoBridge.scrollSink = null
            transport = null
            try { conn.disconnect() } catch (_: Exception) {}
            connection = null
            // keep listening, AA (or the user) can reconnect
        }
        transport = t

        // present the phone's mic as the head unit's, so the assistant works. AA drives it with
        // MICROPHONE_REQUEST, see AapControl.
        t.microphone = AaMicrophone(context, t, log)
        // tell AA when it gets dark so the dash isn't a white map at night
        t.nightSender = NightModeSender(context, t, log).also { it.start() }

        val input = AaInput(t, log)
        var loggedTouchMap = false
        AaVideoBridge.touchSink = { action, actionIndex, pointers ->
            // map every pointer through the same viewport the picture uses, so a tap lands where
            // the rider sees it. a pointer in a black bar maps to null and is dropped, but only
            // that one, the rest of the gesture still goes.
            val mapped = pointers.mapNotNull { (id, cx, cy) ->
                AaVideoBridge.pipeline?.mapBikeTouchToSource(cx, cy)?.let { Triple(id, it.first, it.second) }
            }
            if (mapped.isNotEmpty()) {
                if (!loggedTouchMap || action != AaInput.ACTION_MOVE) {
                    val where = mapped.joinToString { "#${it.first}=(${it.second},${it.third})" }
                    log("[AA] touch action=$action idx=$actionIndex → AA $where")
                    loggedTouchMap = true
                }
                input.sendTouch(action, actionIndex.coerceAtMost(mapped.size - 1), mapped)
            }
        }
        // phone d-pad -> AA, for non-touch dashes. MainActivity calls this.
        AaVideoBridge.keySink = { keycode -> input.sendKey(keycode) }
        // rotary knob, the main way to step focus through AA's lists
        AaVideoBridge.scrollSink = { delta -> input.sendScroll(delta) }

        log("[AA] starting AAP handshake (version + SSL)…")
        if (!t.startHandshake(conn)) {
            log("[AA] handshake FAILED")
            transport = null
            try { conn.disconnect() } catch (_: Exception) {}
            connection = null
            return
        }
        dev.snaipdefix.opencflink.AppStatus.aaConnected = true
        log("[AA] handshake OK — pointing decoder at encoder surface and starting read loop")
        videoDecoder.setSurface(encoderSurface)
        t.startReading()
        log("[AA] read loop started — expecting ServiceDiscovery then video")
    }

    private fun registerNsd() {
        try {
            nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
            if (nsdManager == null) { log("[AA] NSD unavailable"); return }
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = "AAWireless"
                serviceType = "_aawireless._tcp"
                port = PORT
            }
            registrationListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) = log("[AA] NSD registered: ${info.serviceName}")
                override fun onRegistrationFailed(info: NsdServiceInfo, err: Int) = log("[AA] NSD reg fail: $err")
                override fun onServiceUnregistered(info: NsdServiceInfo) = log("[AA] NSD unregistered")
                override fun onUnregistrationFailed(info: NsdServiceInfo, err: Int) = log("[AA] NSD unreg fail: $err")
            }
            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            log("[AA] NSD register error: ${e.message}")
        }
    }

    private fun unregisterNsd() {
        try { registrationListener?.let { nsdManager?.unregisterService(it) } } catch (_: Exception) {}
        registrationListener = null
    }
}
