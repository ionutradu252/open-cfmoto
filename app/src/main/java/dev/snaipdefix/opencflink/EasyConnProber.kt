package dev.snaipdefix.opencflink

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.Network
import android.net.wifi.WifiManager
import java.io.BufferedInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/**
 * EasyConn / Carbit PXC client for CFMoto MotoPlay.
 *
 * Topology (verified in cfmoto-tcp-v5.log): the PHONE is the SERVER.
 *  1. Discover the bike (gateway 192.168.0.1, EasyConn mDNS advertises :10930).
 *  2. Open TCP servers on 10920, 10921, 10922 bound to our bike-network IP.
 *  3. Connect once to bike:10930 and send ECP_PXC_MDNS_RESPOND (cmd 0x70000010, JSON);
 *     bike replies {"status":true} and we close that socket.
 *  4. The bike then connects BACK to our listening ports and drives the PXC handshake
 *     (channel selects, CLIENT_INFO, SN check, heartbeats) — handled by [PxcHandshake].
 */
class EasyConnProber(
    private val context: Context,
    private val log: (String) -> Unit,
) {
    companion object {
        const val PORT_MEDIA_DATA = 10920   // MediaProjectService data
        const val PORT_MEDIA_CTRL = 10921   // MediaProjectService ctrl
        const val PORT_PXC_CTRL   = 10922   // PXCService ctrl (channel selects + CLIENT_INFO)
        const val BIKE_PROBE_PORT = 10930   // bike's EasyConn mDNS/probe endpoint
        const val SPOOFED_PACKAGE = "com.cfmoto.cfmotointernational"
        private val LISTEN_PORTS = intArrayOf(PORT_PXC_CTRL, PORT_MEDIA_CTRL, PORT_MEDIA_DATA)

        /**
         * Input-discovery logging. This dash is non-touch (`supportScreenTouch=false`) but reports
         * `supportHID=true`, so its physical buttons/knob must arrive as some PXC frame we don't yet
         * decode. With this on, every inbound frame that ISN'T routine handshake/heartbeat/video
         * traffic is logged under the `[INPUT?]` tag with full hex — press each bike button once and
         * the distinct frame it produces (cmd/cmdType + payload) becomes mappable. Leave on until the
         * buttons are mapped; it only fires on non-routine frames so normal traffic stays quiet.
         */
        const val DEBUG_LOG_INPUT = true

        /** Control-plane (:10922) cmds that are routine handshake/heartbeat — suppressed from [INPUT?]. */
        private val CTRL_ROUTINE = setOf(
            0x10000, 0x10001, 0x20000, 0x20001, 0x30000, 0x30001, 0x40000, 0x40001,
            0x10010, 0x10011, 0x10690, 0x10691, 0x103e0, 0x103e1, 0x201c0, 0x201c1,
            0x70000000, 0x70000001,
            // CFDL26 notify burst this bike sends every (re)connect — already acked in the profile.
            0x103b0, 0x103b1, 0x10470, 0x10471, 0x10450, 0x10451, 0x104a0, 0x104a1,
            0x10780, 0x10781, 0x103a0, 0x103a1, 0x10020, 0x10021,
        )

        /** Media-plane (:10921/:10920) cmdTypes that are routine video negotiation/touch — suppressed. */
        private val MEDIA_ROUTINE = setOf(16, 17, 32, 48, 49, 64, 65, 96, 97, 112, 113, 114, 115, 128, 129)

        private const val INPUT_HEX_CAP = 256

        /** Phone→bike control-heartbeat interval (well under the bike's ~7-9s socket watchdog). */
        private const val CTRL_HEARTBEAT_MS = 2000L
    }

    private val handshake = PxcHandshake(log)
    private val servers = ArrayList<ServerSocket>()
    private var multicastLock: WifiManager.MulticastLock? = null
    private var heartbeatThread: Thread? = null
    @Volatile private var running = false
    @Volatile private var probed = false
    @Volatile private var video: VideoPipeline? = null
    @Volatile private var ownsVideo = false
    @Volatile private var negW = 800
    @Volatile private var negH = 384
    @Volatile private var framesSent = 0

    /** Live status for the on-screen status row. */
    val bikeConnected: Boolean get() = running && probed
    val framesSentCount: Int get() = framesSent
    @Volatile private var touchMoves = 0

    fun start(network: Network?) {
        if (running) { log("already running"); return }
        probed = false
        framesSent = 0
        dumpEnvironment(network)

        val myIp = pickBikeInterfaceIp(network)
        if (myIp == null) { log("could not resolve our IPv4 on the bike network; aborting"); return }
        val bikeIp = resolveGateway(network)
        if (bikeIp == null) { log("could not resolve bike gateway IP; aborting"); return }
        log("our IP=${myIp.hostAddress}  bike IP=${bikeIp.hostAddress}")

        running = true
        acquireMulticastLock()

        // 1. Listen on all three ports BEFORE probing, so we're ready for the bike's call-back.
        for (port in LISTEN_PORTS) {
            try {
                val ss = ServerSocket(port, 50, myIp)
                servers.add(ss)
                spawnAccept(port, ss)
            } catch (e: Exception) {
                log("bind :$port failed: ${e.message}")
            }
        }
        log("listening on ${myIp.hostAddress} ports ${LISTEN_PORTS.toList()}")

        // 2. Send the probe (gives the bike our IP → it connects back).
        thread(name = "ec-probe", isDaemon = true) {
            sendMdnsRespond(bikeIp, myIp, network)
        }
        startHeartbeatLog()
    }

    fun stop() {
        running = false
        probed = false
        // Only stop the pipeline if we created it; the shared Android Auto pipeline is owned
        // by AndroidAutoService and must outlive a bike disconnect.
        if (ownsVideo) video?.stop()
        video = null; ownsVideo = false
        heartbeatThread?.interrupt(); heartbeatThread = null
        for (s in servers) try { s.close() } catch (_: IOException) {}
        servers.clear()
        multicastLock?.let { try { if (it.isHeld) it.release() } catch (_: Exception) {} }
        multicastLock = null
        log("stopped")
    }

    /** Step 3: phone→bike probe. cmd 0x70000010 + JSON; expect 0x70000011 {"status":true}. */
    private fun sendMdnsRespond(bikeIp: Inet4Address, myIp: Inet4Address, network: Network?) {
        var attempt = 0
        while (running && attempt < 5 && !probed) {
            attempt++
            try {
                log("[PROBE] connect #$attempt -> ${bikeIp.hostAddress}:$BIKE_PROBE_PORT")
                val sock = Socket()
                try { sock.bind(InetSocketAddress(myIp, 0)) } catch (_: Exception) {}
                network?.let { try { it.bindSocket(sock) } catch (_: Exception) {} }
                sock.connect(InetSocketAddress(bikeIp, BIKE_PROBE_PORT), 3000)
                sock.soTimeout = 5000

                val json = JSONProbe()
                log("[PROBE] -> MDNS_RESPOND (0x70000010) $json")
                PxcFrame(PxcFrame.CMD_MDNS_RESPOND, json.toByteArray(Charsets.UTF_8))
                    .write(sock.getOutputStream())

                val resp = PxcFrame.read(sock.getInputStream())
                if (resp == null) {
                    log("[PROBE] bike closed before responding")
                } else {
                    val body = String(resp.payload, Charsets.UTF_8)
                    log("[PROBE] <- cmd=0x${resp.cmd.toUInt().toString(16)} $body")
                    if (resp.cmd == PxcFrame.CMD_MDNS_RESPOND_ACK && body.contains("true")) {
                        log("[PROBE] *** accepted — bike should now connect back to our ports ***")
                        probed = true
                    } else {
                        log("[PROBE] !! not accepted: $body")
                    }
                }
                try { sock.close() } catch (_: IOException) {}
                if (probed) return
            } catch (e: Exception) {
                log("[PROBE] failed: ${e.javaClass.simpleName}: ${e.message}")
            }
            try { Thread.sleep(2000) } catch (_: InterruptedException) { return }
        }
    }

    private fun JSONProbe(): String =
        "{\"phoneType\":\"Android\",\"packageName\":\"$SPOOFED_PACKAGE\"}"

    private fun spawnAccept(port: Int, server: ServerSocket) =
        thread(name = "ec-accept-$port", isDaemon = true) {
            while (running) {
                val client = try { server.accept() } catch (e: IOException) {
                    if (running) log("[:$port] accept ended: ${e.message}"); break
                }
                log("[:$port] <<< bike connected from ${client.remoteSocketAddress}")
                thread(name = "ec-conn-$port", isDaemon = true) { readLoop(port, client) }
            }
        }

    private fun readLoop(port: Int, socket: Socket) {
        val tag = ":$port"
        socket.soTimeout = 0
        socket.tcpNoDelay = true
        try {
            val input = BufferedInputStream(socket.getInputStream())
            // Framing is by port (consistent across every run):
            //   10922 = PXC control (16-byte CmdBaseHead); 10921/10920 = media (8-byte ReqBase).
            if (port == PORT_PXC_CTRL) {
                log("[$tag] framing=CmdBaseHead (PXC control)")
                if (DEBUG_LOG_INPUT) log("[$tag] INPUT capture ON — press each bike button once; watch for [INPUT?] lines")
                val hbThread = startCtrlHeartbeat(tag, socket)
                try {
                    while (running) {
                        val frame = try { PxcFrame.read(input) } catch (e: Exception) {
                            log("[$tag] frame error: ${e.message}"); return
                        } ?: run { log("[$tag] bike closed"); return }
                        if (DEBUG_LOG_INPUT && frame.cmd !in CTRL_ROUTINE) {
                            val hex = BleProtocol.bytesToHex(frame.payload.copyOf(minOf(INPUT_HEX_CAP, frame.payload.size)))
                            log("[INPUT? $tag] ctrl 0x${frame.cmd.toUInt().toString(16)} (${PxcFrame.nameOf(frame.cmd)}) " +
                                "len=${frame.payload.size} hex=$hex")
                        }
                        try { handshake.handle(tag, frame, socket) }
                        catch (e: Exception) { log("[$tag] handler error: $e") }
                    }
                } finally {
                    hbThread.interrupt()
                }
            } else {
                log("[$tag] framing=ReqBase (media plane) profile=${handshake.profile.name}")
                mediaLoop(tag, input, socket.getOutputStream())
            }
        } catch (e: IOException) {
            log("[$tag] read error: ${e.message}")
        } finally {
            try { socket.close() } catch (_: IOException) {}
        }
    }

    /**
     * Proactive control-plane heartbeat. Some dashboards (see [BikeProfile.requiresPhoneHeartbeat])
     * never send their own [PxcFrame.CMD_HEARTBEAT], so the control socket goes idle and the bike's
     * socket-timeout watchdog resets the whole session every ~7s. We send CMD_HEARTBEAT every
     * [CTRL_HEARTBEAT_MS] to keep it alive. Writes go through [PxcFrame.write], which synchronizes on
     * the stream, so this is safe alongside the read thread's reply writes. Gated on the profile,
     * which is set from CLIENT_INFO within ~100ms — before the first heartbeat is due.
     */
    private fun startCtrlHeartbeat(tag: String, socket: Socket): Thread =
        thread(name = "ec-ctrl-hb-${socket.port}", isDaemon = true) {
            val out = try { socket.getOutputStream() } catch (e: Exception) { return@thread }
            var sent = 0
            while (running && !socket.isClosed) {
                try { Thread.sleep(CTRL_HEARTBEAT_MS) } catch (e: InterruptedException) { break }
                if (!handshake.profile.requiresPhoneHeartbeat) continue
                try {
                    PxcFrame(PxcFrame.CMD_HEARTBEAT, ByteArray(0)).write(out)
                    if (++sent <= 2) log("[$tag] → phone heartbeat 0x70000000 (keep-alive #$sent)")
                } catch (e: Exception) {
                    log("[$tag] heartbeat stopped: ${e.message}"); break
                }
            }
        }

    // ---- Media plane: Protocol.ReqBase framing (8-byte LE header + body) ----
    // header: cmdType(s16) | cmdLen(s16) | token(i32); reply uses the same header.
    private fun mediaLoop(tag: String, input: java.io.InputStream, out: OutputStream) {
        val header = ByteArray(8)
        while (running) {
            if (!PxcFrame.readFully(input, header, 8)) { log("[$tag] media closed"); return }
            val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val cmdType = b.getShort(0).toInt()
            val cmdLen = b.getShort(2).toInt() and 0xFFFF
            val token = b.getInt(4)
            val body = ByteArray(cmdLen)
            if (cmdLen > 0 && !PxcFrame.readFully(input, body, cmdLen)) { log("[$tag] media body short"); return }
            if (DEBUG_LOG_INPUT && cmdType !in MEDIA_ROUTINE) {
                val hex = BleProtocol.bytesToHex(body.copyOf(minOf(INPUT_HEX_CAP, body.size)))
                log("[INPUT? $tag] media cmdType=$cmdType token=$token len=$cmdLen hex=$hex")
            }
            handleMediaReq(tag, cmdType, token, body, out)
        }
    }

    /** Frame reply on the data socket is written RAW (not ReqBase): [size i32 LE][access unit].
     *  Inferred from the partial-decompiled MediaProjectServerDataExecuteThread.reply*Data(). */
    private fun sendFrameRaw(out: OutputStream, frame: ByteArray) {
        val sz = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0, frame.size).array()
        synchronized(out) {
            out.write(sz)
            out.write(frame)
            out.flush()
        }
    }

    private fun sendReqBase(out: OutputStream, cmdType: Int, body: ByteArray?) {
        val len = body?.size ?: 0
        val h = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        h.putShort(0, cmdType.toShort())
        h.putShort(2, len.toShort())
        h.putInt(4, 0)
        synchronized(out) {
            out.write(h.array())
            if (body != null && body.isNotEmpty()) out.write(body)
            out.flush()
        }
    }

    private fun handleMediaReq(tag: String, cmdType: Int, token: Int, body: ByteArray, out: OutputStream) {
        when (cmdType) {
            16 -> { // REQ_RV_CONFIG_CAPTURE
                val cfg = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
                val w = if (body.size >= 2) cfg.getShort(0).toInt() and 0xFFFF else 0
                val h = if (body.size >= 4) cfg.getShort(2).toInt() and 0xFFFF else 0
                val fps = if (body.size >= 8) cfg.getInt(4) else 0
                val wantEncoder = if (body.size >= 12) cfg.getInt(8) else 2
                val supportExtend = if (body.size >= 30) body[29] else 0
                log("[$tag] REQ_CONFIG_CAPTURE w=$w h=$h fps=$fps wantEncoder=$wantEncoder ext=$supportExtend len=${body.size}")
                val (rw, rh) = handshake.profile.roundCaptureDimensions(w, h)
                negW = rw
                negH = rh
                // If Android Auto is the source (shared pipeline), size its encoder + letterbox
                // compositor to this bike canvas now — before the bike starts pulling frames (112/114).
                AaVideoBridge.pipeline?.configureBikeCanvas(negW, negH)
                // RLY_RV_CONFIG_CAPTURE (17): encoder(i32) | width&~15(s16) | height&~15(s16) | ext(byte)
                val rly = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN)
                rly.putInt(0, if (wantEncoder == 0) 2 else wantEncoder)
                rly.putShort(4, negW.toShort())
                rly.putShort(6, negH.toShort())
                rly.put(8, supportExtend)
                log("[$tag] → RLY_CONFIG_CAPTURE(17) encoder=${if (wantEncoder==0) 2 else wantEncoder} w=$negW h=$negH")
                sendReqBase(out, 17, rly.array())
            }
            48 -> { // REQ_GET_VERSION → 49 (two LE ints: version, subVersion=1) per ctrl thread
                log("[$tag] REQ_GET_VERSION → RLY 49")
                val v = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                v.putInt(0, 3); v.putInt(4, 1)
                sendReqBase(out, 49, v.array())
            }
            64 -> { // REQ_HEARTBEAT → 65
                sendReqBase(out, 65, null)
            }
            96 -> { // REQ_CONFIGCAPTUREREXTEND → 97 (JSON). Send state OK.
                log("[$tag] REQ_CONFIGCAPTUREREXTEND len=${body.size} ${String(body, Charsets.UTF_8).take(120)} → RLY 97")
                sendReqBase(out, 97, "{\"state\":0}".toByteArray(Charsets.UTF_8))
            }
            128 -> { // REQ_START_H264 → 129 (then bike expects frames on data socket)
                log("[$tag] *** REQ_START_H264 *** len=${body.size} → RLY 129 (no encoder yet — video stage TODO)")
                sendReqBase(out, 129, null)
            }
            112 -> { // REQ_RV_DATA_START → start encoder, then RLY_RV_DATA_START(113)
                if (video == null) {
                    val shared = AaVideoBridge.pipeline
                    if (shared != null) {
                        // Android Auto is running: pull encoded frames from its (already started)
                        // pipeline instead of creating our own Presentation/mirror source.
                        video = shared
                        ownsVideo = false
                        log("[$tag] REQ_RV_DATA_START(112): using shared Android Auto video pipeline")
                    } else {
                        log("[$tag] REQ_RV_DATA_START(112): starting video ${negW}x${negH}")
                        video = VideoPipeline(context, negW, negH, log).also { it.start() }
                        ownsVideo = true
                    }
                }
                // Ensure the first frame the bike pulls is a keyframe (SPS+PPS+IDR). Critical for the
                // Android Auto path, whose encoder has been running since REQ_CONFIG_CAPTURE — its
                // initial IDR is already gone from the queue, so without this the dash starts mid-GOP
                // on a P-frame and stays black. See VideoPipeline.onBikeDataStart().
                video?.onBikeDataStart()
                log("[$tag] → RLY 113")
                sendReqBase(out, 113, null)
            }
            114 -> { // REQ_RV_DATA_NEXT — bike pulling a frame (data socket); send one access unit raw
                val frame = video?.pollFrame(1500)
                if (frame == null) {
                    log("[$tag] REQ_RV_DATA_NEXT(114): no frame ready")
                } else {
                    sendFrameRaw(out, frame)
                    framesSent++
                    if (framesSent <= 5 || framesSent % 60 == 0)
                        log("[$tag] sent frame #$framesSent (${frame.size}b)")
                }
            }
            32 -> handleTouch(tag, body)
            else -> {
                val preview = BleProtocol.bytesToHex(body.copyOf(minOf(32, body.size)))
                log("[$tag] media cmdType=$cmdType len=${body.size} $preview")
            }
        }
    }

    /**
     * Dash touchscreen event (PXC media cmdType 32, 18-byte body, little-endian):
     *   action u16 @0 (2=DOWN, 3=MOVE, 1=UP) | x u16 @2 | y u32 @4 | timestamp u32 @8 | …
     * Coordinates are in the bike canvas we negotiated ([negW]x[negH]). Forward to the Android Auto
     * session via [AaVideoBridge.touchSink], which letterbox-maps them into AA video space and sends
     * them over the AAP INPUT channel. Actions are normalised to AaInput's 0=DOWN/1=UP/2=MOVE.
     */
    private fun handleTouch(tag: String, body: ByteArray) {
        if (body.size < 8) { log("[$tag] touch frame too short (${body.size}b)"); return }
        val b = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
        val rawAction = b.getShort(0).toInt() and 0xFFFF
        val x = b.getShort(2).toInt() and 0xFFFF
        val y = b.getInt(4)
        val action = when (rawAction) {
            2 -> 0   // DOWN
            1 -> 1   // UP
            3 -> 2   // MOVE
            else -> { log("[$tag] touch: unknown action=$rawAction x=$x y=$y"); return }
        }
        // Log DOWN/UP (and the first MOVE) so the coordinate mapping is verifiable without move spam.
        if (action != 2 || (touchMoves++ % 30) == 0) {
            log("[$tag] TOUCH ${if (action==0) "DOWN" else if (action==1) "UP" else "MOVE"} bike=($x,$y) canvas=${negW}x$negH")
        }
        val sink = AaVideoBridge.touchSink
        if (sink == null) {
            if (action != 2) log("[$tag] touch dropped — no AA session")
        } else {
            sink(action, x, y)
        }
    }

    private fun resolveGateway(network: Network?): Inet4Address? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val target = network ?: cm.activeNetwork ?: return null
        val lp = cm.getLinkProperties(target) ?: return null
        for (r in lp.routes) {
            if (r.isDefaultRoute) {
                val gw = r.gateway
                if (gw is Inet4Address && !gw.isAnyLocalAddress) return gw
            }
        }
        lp.dnsServers.filterIsInstance<Inet4Address>().firstOrNull()?.let { return it }

        // Fallback for Wi-Fi Direct / AP networks that expose no default route and no DNS server
        // (e.g. SSID "DIRECT-go-CFMOTO-…", phone on 192.168.49.x): assume the bike is host .1 of
        // our own IPv4 subnet — the standard group-owner address for an Android P2P group.
        val la = lp.linkAddresses.firstOrNull {
            val a = it.address
            a is Inet4Address && !a.isLoopbackAddress && !a.isAnyLocalAddress
        }
        if (la != null) {
            val b = la.address.address
            val prefix = la.prefixLength.coerceIn(1, 32)
            val ip = ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or
                ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)
            val mask = -1 shl (32 - prefix)
            val gwInt = (ip and mask) or 1
            val gw = InetAddress.getByAddress(
                byteArrayOf(
                    (gwInt ushr 24).toByte(), (gwInt ushr 16).toByte(),
                    (gwInt ushr 8).toByte(), gwInt.toByte(),
                )
            ) as Inet4Address
            if (gw != la.address) {
                log("[gateway] no default route/DNS; assuming ${gw.hostAddress} (from ${la.address.hostAddress}/$prefix)")
                return gw
            }
        }
        return null
    }

    private fun pickBikeInterfaceIp(network: Network?): Inet4Address? {
        if (network != null) {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.getLinkProperties(network)?.linkAddresses
                ?.map(LinkAddress::getAddress)
                ?.filterIsInstance<Inet4Address>()
                ?.firstOrNull { !it.isLoopbackAddress }
                ?.let { return it }
        }
        for (nic in NetworkInterface.getNetworkInterfaces()) {
            if (!nic.isUp || nic.isLoopback) continue
            for (addr in nic.inetAddresses) {
                if (addr is Inet4Address && !addr.isLoopbackAddress) return addr
            }
        }
        return null
    }

    private fun acquireMulticastLock() {
        val wm = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wm.createMulticastLock("opencflink").apply {
            setReferenceCounted(false); acquire()
        }
    }

    private fun startHeartbeatLog() {
        heartbeatThread = thread(name = "ec-hb", isDaemon = true) {
            var i = 0
            while (running) {
                try { Thread.sleep(5000) } catch (_: InterruptedException) { break }
                i++
                log("hb#$i probed=$probed video=${video != null} framesSent=$framesSent openServers=${servers.count { !it.isClosed }}")
            }
        }
    }

    private fun dumpEnvironment(network: Network?) {
        log("---- environment ----")
        log("Build: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} / Android ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (network != null) {
            val lp = cm.getLinkProperties(network)
            log("linkProps iface=${lp?.interfaceName} addrs=${lp?.linkAddresses} routes=${lp?.routes}")
        }
        log("---------------------")
    }
}
