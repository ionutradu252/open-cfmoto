package dev.snaipdefix.opencflink

import org.json.JSONObject
import java.net.Socket
import java.util.UUID

/**
 * Server-side PXC control dispatcher. The phone is the SERVER: after we send the
 * ECP_PXC_MDNS_RESPOND probe to the bike, the bike connects back to our listening
 * ports and drives the handshake. We reply per cmd.
 *
 * Verified against cfmoto-tcp-v5.log (official app, standard Car PXC, JSON CLIENT_INFO):
 *   bike 0x10000 (CAR_CTRL select)  -> we 0x10001
 *   bike 0x20000 (CAR_DATA select)  -> we 0x20001
 *   bike 0x10010 CLIENT_INFO (JSON) -> we 0x10011 (our info + RSA pubkey + signed HUID)
 *   bike 0x10690 {usbSpeed,wifiSpeed} -> we 0x10691
 *   bike 0x103e0 {client_set,sn}    -> we 0x103e1, then 0x201c0 CHECK_SN_RESULT {isOk:true}
 *   bike 0x70000000 heartbeat        -> we 0x70000001
 */
class PxcHandshake(
    private val log: (String) -> Unit,
) {
    private val phoneUuid: String = UUID.randomUUID().toString()
    @Volatile var carHuid: String? = null
        private set
    @Volatile var lastClientInfo: JSONObject? = null
        private set
    /** Dashboard-variant strategy, selected from the bike's CLIENT_INFO. Read by the media plane too
     *  (via EasyConnProber.handshake.profile). Written once on the control thread, before any media
     *  socket exists. */
    @Volatile var profile: BikeProfile = BikeProfiles.legacy
        private set

    /** Dispatch one inbound frame on a given socket (ctrl or media). */
    fun handle(tag: String, frame: PxcFrame, socket: Socket) {
        val out = socket.getOutputStream()
        when (frame.cmd) {
            PxcFrame.CMD_CHANNEL_CAR_CTRL -> {
                log("[$tag] bike selected CAR_CTRL (0x10000) → ack 0x10001")
                PxcFrame(PxcFrame.CMD_CHANNEL_CAR_CTRL + 1, ByteArray(0)).write(out)
            }
            PxcFrame.CMD_CHANNEL_CAR_DATA -> {
                log("[$tag] bike selected CAR_DATA (0x20000) → ack 0x20001")
                PxcFrame(PxcFrame.CMD_CHANNEL_CAR_DATA + 1, ByteArray(0)).write(out)
            }
            PxcFrame.CMD_CLIENT_INFO -> onClientInfo(tag, frame, out)
            PxcFrame.CMD_QUERY_SPEED -> {
                log("[$tag] QUERY_SPEED ${frame.payload.asText()} → reply 0x10691")
                PxcFrame(PxcFrame.CMD_QUERY_SPEED_RLY, ByteArray(0)).write(out)
            }
            PxcFrame.CMD_CHECK_SN -> onCheckSn(tag, frame, out)
            PxcFrame.CMD_HEARTBEAT -> {
                PxcFrame(PxcFrame.CMD_HEARTBEAT_ACK, ByteArray(0)).write(out)
            }
            PxcFrame.CMD_HEARTBEAT_ACK,
            PxcFrame.CMD_CHECK_SN_RESULT + 1 -> {
                // acks from the bike — nothing to do
            }
            else -> {
                if (!profile.handleUnknownControl(tag, frame, out, log)) {
                    log("[$tag] cmd=0x${frame.cmd.toUInt().toString(16)} (${PxcFrame.nameOf(frame.cmd)}) " +
                        "len=${frame.payload.size} ${frame.payload.asText()}")
                }
            }
        }
    }

    private fun onClientInfo(tag: String, frame: PxcFrame, out: java.io.OutputStream) {
        val text = frame.payload.asText()
        log("[$tag] *** CLIENT_INFO from bike *** $text")
        val json = try { JSONObject(text) } catch (e: Exception) {
            log("[$tag] CLIENT_INFO parse failed: $e"); return
        }
        lastClientInfo = json
        carHuid = json.optString("HUID").ifEmpty { json.optString("huid") }.ifEmpty { null }
        log("[$tag] carHuid=$carHuid HUName=${json.optString("HUName")} channel=${json.optString("channel")}")

        profile = BikeProfiles.select(json, log)
        val early = BikeProfileHolder.active
        if (early !== profile) {
            log("[$tag] profile refined from QR guess '${early.name}' → CLIENT_INFO '${profile.name}' " +
                "(AA already started at the QR-guess resolution)")
        }
        BikeProfileHolder.active = profile  // authoritative; QR modelId was only the early hint
        log("[$tag] *** BikeProfile selected: ${profile.name} ***")

        val reply = profile.buildClientInfoReply(json, carHuid, phoneUuid)
        log("[$tag] → CLIENT_INFO reply ${reply.toString().take(180)}…")
        PxcFrame(PxcFrame.CMD_CLIENT_INFO_RLY, reply.toString().toByteArray(Charsets.UTF_8)).write(out)
    }

    private fun onCheckSn(tag: String, frame: PxcFrame, out: java.io.OutputStream) {
        val text = frame.payload.asText()
        log("[$tag] CHECK_SN from bike: $text")
        val sn = try { JSONObject(text).optString("sn") } catch (e: Exception) { "" }
        // ack the request frame
        PxcFrame(PxcFrame.CMD_CHECK_SN_ACK, ByteArray(0)).write(out)
        // send the result
        val result = JSONObject().apply {
            put("isOk", true)
            put("errCode", 0)
            put("errMsg", "")
            put("id", sn)
            put("client_set", "easy_conn")
        }
        log("[$tag] → CHECK_SN_RESULT ${result}")
        PxcFrame(PxcFrame.CMD_CHECK_SN_RESULT, result.toString().toByteArray(Charsets.UTF_8)).write(out)
    }
}

private fun ByteArray.asText(): String =
    if (isEmpty()) "" else try { String(this, Charsets.UTF_8) } catch (e: Exception) { "<${size}b>" }
