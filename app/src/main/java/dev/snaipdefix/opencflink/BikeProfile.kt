package dev.snaipdefix.opencflink

import android.os.Build
import org.json.JSONObject
import java.io.OutputStream

/**
 * per-dash settings. the pxc protocol is mostly the same across dashboards but newer head units add
 * steps and fields the old ones don't, so instead of branching on version numbers all over the
 * handshake, each dash gets a profile picked from its CLIENT_INFO and asked wherever behaviour
 * differs.
 *
 * picked in PxcHandshake.onClientInfo; the media plane gets it via handshake.profile. the bike only
 * opens the media ports after the control handshake so the profile is always ready in time.
 */
enum class AaResolution(val w: Int, val h: Int) {
    LANDSCAPE_800x480(800, 480),
    LANDSCAPE_1280x720(1280, 720),
    PORTRAIT_720x1280(720, 1280),
    PORTRAIT_1080x1920(1080, 1920),
}

/** the Android Auto video config a dash should request: orientation/size + panel density. */
data class AaVideoSpec(val resolution: AaResolution, val dpi: Int) {
    val width: Int get() = resolution.w
    val height: Int get() = resolution.h
}

interface BikeProfile {
    /** shows up in logs so a capture says which bike it was */
    val name: String

    /** how strongly this profile claims a CLIENT_INFO. highest wins, 0 = no claim. */
    fun score(info: JSONObject): Int

    /**
     * rough match from the qr modelId, the only id we have before connecting. used to pick aaVideo
     * up front, since AA's resolution has to be chosen before AA starts and that's before the
     * CLIENT_INFO handshake. scoring refines it after.
     */
    fun matchesModelId(modelId: String): Boolean = false

    /** the AA video config this dash should ask for */
    val aaVideo: AaVideoSpec

    // ---- capability flags ----
    /** bike says enableSockServerAuth, probably needs an auth exchange before media */
    val requiresSockServerAuth: Boolean
    val supportsScreenTouch: Boolean
    /** what we put in our own CLIENT_INFO reply's supportFunction */
    val advertisedSupportFunction: Int

    /** build the phone's CLIENT_INFO reply (cmd 0x10011) */
    fun buildClientInfoReply(info: JSONObject, huid: String?, phoneUuid: String): JSONObject

    /**
     * handle a control cmd PxcHandshake doesn't know. return true if handled, false for the old
     * "log it, don't reply" behaviour.
     */
    fun handleUnknownControl(
        tag: String, frame: PxcFrame, out: OutputStream, log: (String) -> Unit,
    ): Boolean

    /** round the requested capture size to something the encoder will take */
    fun roundCaptureDimensions(w: Int, h: Int): Pair<Int, Int> = (w and 0xFFF0) to (h and 0xFFF0)

    /** force the h264 encoder to baseline @ 3.1 */
    val forceBaseline: Boolean get() = true

    /**
     * some dashes (the CFDL16 on 66660742) never send their own heartbeat, so the control socket
     * sits idle and the bike's watchdog kills the session every ~7s. when true we send
     * CMD_HEARTBEAT ourselves to keep it alive.
     */
    val requiresPhoneHeartbeat: Boolean get() = false

    /**
     * how to fit AA into the bike canvas when the aspect doesn't match:
     *   false = letterbox, black bars, nothing cropped
     *   true  = fill, covers the panel, crops the overflow
     */
    val fillCanvas: Boolean get() = false

    /**
     * the dash's real panel size when we know it (e.g. 800x400). AA only renders at fixed sizes
     * (800x480, 1280x720...) so a 2:1 panel can never match exactly. declaring the difference as
     * margins makes AA keep its ui inside panelSize and leave the rest empty, and the compositor's
     * fill crops exactly that empty band, perfect fit, nothing real lost, no bars.
     * null = unknown, declare no margins (fill would crop real content).
     */
    val panelSize: Pair<Int, Int>? get() = null

    /** media plane GET_VERSION reply */
    fun versionReply(): Pair<Int, Int> = 3 to 1

    /**
     * true when we know exactly which buttons this dash sends, so the ui can hide the rest.
     * false = show every gesture with its generic name, because we're guessing.
     */
    val knowsButtons: Boolean get() = false

    /**
     * what this gesture is on THIS bike's handlebars, e.g. "▲ press". null if the dash can't send
     * it at all.
     *
     * the same avrcp event comes from different buttons on different bikes, so the ui can't name
     * these generically without lying to somebody. only meaningful when [knowsButtons].
     */
    fun buttonLabel(gesture: ButtonGesture): String? = null
}

/**
 * a profile with its geometry swapped for what the dash actually asked for last time. everything
 * else (handshake, acks, capability flags) is delegated to the normal profile, the panel size only
 * tells us about the screen. see LearnedPanels.
 */
private class LearnedGeometryProfile(
    private val base: BikeProfile,
    private val panel: Pair<Int, Int>,
    resolution: AaResolution,
) : BikeProfile by base {
    override val name = "${base.name} + measured ${panel.first}x${panel.second}"
    override val aaVideo = AaVideoSpec(resolution, dpi = base.aaVideo.dpi)
    override val panelSize = panel
    /** the point: crop exactly the empty margin band, so nothing real is lost */
    override val fillCanvas = true
}

/** registry + selection. never returns null, falls back to legacy. */
object BikeProfiles {
    val legacy: BikeProfile = LegacyCfdl16Profile
    private val all: List<BikeProfile> = listOf(
        Cfdl16MotoPlayLandscapeProfile, Cfdl26NkTouchProfile, Cfdl26PortraitProfile,
        Cfdl26LandscapeProfile, LegacyCfdl16Profile,
    )

    /** the real selection, from CLIENT_INFO during the handshake */
    fun select(info: JSONObject, log: (String) -> Unit): BikeProfile {
        val scored = all.map { it to it.score(info) }
        log("[profile] scores=" + scored.joinToString { "${it.first.name}=${it.second}" })
        return scored.filter { it.second > 0 }.maxByOrNull { it.second }?.first ?: legacy
    }

    /**
     * early selection from the qr, before we connect. pass a context to let a previously measured
     * panel override the guess (see LearnedPanels).
     */
    fun selectByQr(qr: QrData?, context: android.content.Context? = null, log: (String) -> Unit = {}): BikeProfile {
        val base = selectByQrGuess(qr)
        if (context == null || qr == null) return base

        // if this bike has told us its screen before, believe it over the model id
        val panel = LearnedPanels.get(context, qr.ssid) ?: return base
        if (panel == base.panelSize) return base          // guess already agrees; nothing to say
        val res = LearnedPanels.bestFit(panel.first, panel.second)
        if (res == null) {
            log("[panel] measured ${panel.first}x${panel.second} doesn't fit any AA resolution — keeping ${base.name}")
            return base
        }
        val fitted = LearnedGeometryProfile(base, panel, res)
        log("[panel] using this bike's measured screen ${panel.first}x${panel.second} → AA ${res.w}x${res.h}" +
            " (margins ${res.w - panel.first}x${res.h - panel.second})")
        return fitted
    }

    /** the model id guess, no learned override */
    private fun selectByQrGuess(qr: QrData?): BikeProfile {
        if (qr == null) return legacy
        val matches = all.filter { it.matchesModelId(qr.modelId ?: "") }
        if (matches.isEmpty()) return legacy
        if (matches.size == 1) return matches[0]

        // several CFDL26 bikes share modelId 37426 and the qr has nothing else to tell them apart,
        // so this split picks the AA resolution for all of them, and it has to happen here, since
        // AA's video size is fixed when AA connects, before CLIENT_INFO (never mind
        // REQ_CONFIG_CAPTURE) shows up.
        // wifi direct (DIRECT-... ssid) -> 1000 MT-X, tall portrait panel
        // ap mode -> 800NK. its 720x712 panel is measured from a real log; the 800MT geometry
        // never was, so when we can't tell them apart go with the one we have evidence for. after
        // one connect LearnedPanels settles it from the dash itself and this stops mattering.
        val isP2p = qr.supportsP2p || qr.ssid.startsWith("DIRECT-")
        return if (isP2p) Cfdl26PortraitProfile else Cfdl26NkTouchProfile
    }

    /** old helper, kept for tests */
    fun selectByModelId(modelId: String?): BikeProfile =
        modelId?.let { id -> all.firstOrNull { it.matchesModelId(id) } } ?: legacy
}

/**
 * the active profile. set early from the qr so ServiceDiscoveryResponse can ask for the right
 * resolution before AA starts, then confirmed from CLIENT_INFO. read from both the activity and the
 * service, so it's a process global like ProjectionHolder / AaVideoBridge.
 */
object BikeProfileHolder {
    @Volatile var active: BikeProfile = BikeProfiles.legacy

    /**
     * the panel size we actually told AA about, recorded when ServiceDiscoveryResponse is built.
     *
     * this is not always [active]'s panel. AA connects on the qr guess, then CLIENT_INFO arrives and
     * we refine the profile, but service discovery has already gone out, so AA is still laying out to
     * the old number. logging active.panelSize after that point claims a margin we never sent, which
     * is exactly the thing you'd check first when taps miss.
     */
    @Volatile var declaredPanel: Pair<Int, Int>? = null
}

/** shared base CLIENT_INFO reply. key order matches the original so legacy output is identical. */
private fun basePhoneClientInfo(huid: String?, phoneUuid: String, supportFunction: Int): JSONObject =
    JSONObject().apply {
        put("pxcVersion", "1.0.2")
        put("phoneUUID", phoneUuid)
        put("phoneBrand", Build.BRAND)
        put("phoneModel", Build.MODEL)
        put("phoneOsVersion", Build.VERSION.SDK_INT.toString())
        put("phoneOs", "Android")
        put("package", EasyConnProber.SPOOFED_PACKAGE)
        put("versionCode", 126)
        put("token", 0)
        put("pubkey", RsaKeys.publicKeyBase64)
        put("encryptedHUID", huid?.let { RsaKeys.signHuid(it) } ?: "")
        put("bluetoothName", "OpenCFLink")
        put("supportH264IFrame", true)
        put("supportFunction", supportFunction)
        put("appVersionFingerPrint", "opencflink-poc")
    }

/**
 * bike A, the CFDL16 head unit (sdk 0.9.29.1) this was reverse engineered against and confirmed
 * working. reproduces the original behaviour exactly and is the safe default for anything we don't
 * recognise.
 */
object LegacyCfdl16Profile : BikeProfile {
    override val name = "CFDL16 / legacy (BIKE A)"
    override val requiresSockServerAuth = false
    override val supportsScreenTouch = false
    override val advertisedSupportFunction = 0

    /** the 675's 5" panel is landscape, ~800x386 */
    override val aaVideo = AaVideoSpec(AaResolution.LANDSCAPE_800x480, dpi = 160)

    /** known 675 modelId. legacy is the fallback anyway, this is just an early match. */
    override fun matchesModelId(modelId: String): Boolean = modelId.trim() == "37416"

    /** constant floor so legacy wins ties and is always available as a fallback */
    override fun score(info: JSONObject): Int = 1

    override fun buildClientInfoReply(info: JSONObject, huid: String?, phoneUuid: String): JSONObject =
        basePhoneClientInfo(huid, phoneUuid, advertisedSupportFunction)

    override fun handleUnknownControl(
        tag: String, frame: PxcFrame, out: OutputStream, log: (String) -> Unit,
    ): Boolean = false  // the original "log it, no reply" behaviour
}

/** bike B, CFDL26 / MotoPlay on the 1000 MT-X (sdk 1.1.4, cfdashmotoplay, wifi direct). */
object Cfdl26PortraitProfile : BikeProfile {
    override val name = "CFDL26 / MotoPlay Portrait (1000 MT-X)"
    override val requiresSockServerAuth = true
    override val supportsScreenTouch = true
    override val advertisedSupportFunction = 128

    /** the 1000 MT-X's 8" panel is tall portrait (asks for ~800x951). portrait 720x1280 @240dpi. */
    override val aaVideo = AaVideoSpec(AaResolution.PORTRAIT_720x1280, dpi = 240)

    /** known CFDL26 / 1000 MT-X modelId */
    override fun matchesModelId(modelId: String): Boolean = modelId.trim() == "37426"

    override fun score(info: JSONObject): Int {
        var s = 0
        if (info.optString("version_name").startsWith("CFDL26")) s += 4
        val sdk = info.optString("sdkVersion")
        if (sdk.isNotEmpty() && !sdk.startsWith("0.")) s += 2   // 1.1.4 etc., not the 0.9.x legacy unit
        if (info.optBoolean("enableSockServerAuth", false)) s += 2
        if (info.optString("package_name") == "com.cfmoto.cfdashmotoplay") s += 3
        if (info.optInt("supportFunction", 0) == 128) s += 1
        return s
    }

    override fun buildClientInfoReply(info: JSONObject, huid: String?, phoneUuid: String): JSONObject =
        basePhoneClientInfo(huid, phoneUuid, advertisedSupportFunction).apply {
            put("supportScreenTouch", true)
        }

    override fun handleUnknownControl(
        tag: String, frame: PxcFrame, out: OutputStream, log: (String) -> Unit,
    ): Boolean {
        // after CHECK_SN the CFDL26 sends a burst of json frames the CFDL16 never did, 0x10780
        // (log), 0x103a0 (ota ftp creds), 0x10020 (media flags), maybe more, and won't connect to
        // the media ports until each is acked. pxc acks with cmd+1 (empty), so do that for every
        // control frame we don't otherwise handle.
        val body = if (frame.payload.isEmpty()) "" else String(frame.payload, Charsets.UTF_8)
        val hex = if (frame.payload.isEmpty()) "" else
            " hex=" + BleProtocol.bytesToHex(frame.payload.copyOf(minOf(48, frame.payload.size)))
        val tag2 = if (frame.cmd == PxcFrame.CMD_SCREEN_TOUCH) " *** SCREEN_TOUCH ***" else ""
        val ack = frame.cmd + 1
        log("[$tag] CFDL26 ctrl ${frame.cmdHex()} (${PxcFrame.nameOf(frame.cmd)})$tag2 len=${frame.payload.size} " +
            "$body$hex → ack 0x${ack.toUInt().toString(16)} (empty)")
        PxcFrame(ack, ByteArray(0)).write(out)
        return true
    }
}

/** bike C, CFDL26 / MotoPlay on the 800MT (sdk 1.1.2, easyconnect, ap mode). */
object Cfdl26LandscapeProfile : BikeProfile {
    override val name = "CFDL26 / MotoPlay Landscape (800MT)"
    override val requiresSockServerAuth = true
    override val supportsScreenTouch = true
    override val advertisedSupportFunction = 128

    /** the 800MT is landscape. 800x480 is the size proven to stream on this dash. never verified
     * with AA end to end, the geometry here is from docs, not a real bike. */
    override val aaVideo = AaVideoSpec(AaResolution.LANDSCAPE_800x480, dpi = 160)

    override fun matchesModelId(modelId: String): Boolean = modelId.trim() == "37426"

    override fun score(info: JSONObject): Int {
        var s = 0
        if (info.optString("version_name").startsWith("CFDL26")) s += 4
        val sdk = info.optString("sdkVersion")
        if (sdk.isNotEmpty() && !sdk.startsWith("0.")) s += 2
        if (info.optBoolean("enableSockServerAuth", false)) s += 2
        if (info.optString("package_name") == "com.cfmoto.easyconnect") s += 3
        if (info.optInt("supportFunction", 0) == 128) s += 1
        return s
    }

    override fun buildClientInfoReply(info: JSONObject, huid: String?, phoneUuid: String): JSONObject =
        basePhoneClientInfo(huid, phoneUuid, advertisedSupportFunction).apply {
            put("supportScreenTouch", true)
        }

    // baseline@3.1 (the interface default). this used to be false (main/high) which is the prime
    // suspect for the old AA black screen: the dash pulls main/high frames happily and renders
    // nothing, since embedded decoders usually only do baseline. see docs/05-DEBUG-KNOWLEDGE.md.

    override fun handleUnknownControl(
        tag: String, frame: PxcFrame, out: OutputStream, log: (String) -> Unit,
    ): Boolean {
        return Cfdl26PortraitProfile.handleUnknownControl(tag, frame, out, log)
    }
}

/**
 * bike E, CFDL26 on the 800NK. touchscreen, and a nearly square 720x712 panel (sdk 1.1.2,
 * easyconnect, version_name CFDL26.2.3.0.5, ap mode). built from a real log, geometry included.
 *
 * the panel is 720x712, measured, the dash asks for exactly that in REQ_CONFIG_CAPTURE. nothing AA
 * renders is close to 1.01:1, which is why the 800MT profile (landscape 800x480) looked so wrong:
 * letterbox squeezed it into a 720x432 strip with 136px bars, fill blew it up to 1173x704 and threw
 * away 226px off each side.
 *
 * fix is the 450SR's margin trick on its side: ask AA for portrait 720x1280. the width matches the
 * panel exactly so pixels map 1:1 with no scaling, and marginHeight = 1280-712 = 568 keeps AA's ui
 * in a 720x712 band in the middle. the compositor's fill then crops exactly the empty band.
 *
 * the margin is 44% of the canvas (vs 17% on the 450SR), so it's the most likely thing to need
 * changing if AA lays out badly. dpi 160 is a guess copied from the 450SR; this panel is denser so
 * the ui might come out big.
 */
object Cfdl26NkTouchProfile : BikeProfile {
    override val name = "CFDL26 / 800NK (touch, 720x712)"
    override val requiresSockServerAuth = true
    override val supportsScreenTouch = true
    override val advertisedSupportFunction = 128
    /** crop the empty band above/below AA's ui, see above */
    override val fillCanvas = true
    /** measured from the bike's REQ_CONFIG_CAPTURE (w=720 h=712) */
    override val panelSize = 720 to 712

    /** width matches the panel exactly so pixels are 1:1. marginHeight = 1280-712 = 568. */
    override val aaVideo = AaVideoSpec(AaResolution.PORTRAIT_720x1280, dpi = 160)

    /** shared with the 1000 MT-X and 800MT, selectByQr does the real splitting */
    override fun matchesModelId(modelId: String): Boolean = modelId.trim() == "37426"

    /**
     * from a press-every-button capture (2026-07-17): left/right send next/prev, star sends
     * play/pause, and fn / voice / up / down send nothing at all, over neither avrcp nor pxc.
     * up/down are the dash's own volume, voice runs its local speech engine.
     */
    override val knowsButtons = true
    override fun buttonLabel(gesture: ButtonGesture): String? = when (gesture) {
        ButtonGesture.PREV_KEY -> "◀ press"
        ButtonGesture.PREV_DOUBLE -> "◀ double tap"
        ButtonGesture.NEXT_KEY -> "▶ press"
        ButtonGesture.NEXT_DOUBLE -> "▶ double tap"
        ButtonGesture.ENTER_PRESS -> "★ press"
        else -> null
    }

    override fun score(info: JSONObject): Int {
        var s = 0
        if (info.optString("version_name").startsWith("CFDL26")) s += 4
        if (info.optString("package_name") == "com.cfmoto.easyconnect") s += 3
        if (info.optBoolean("enableSockServerAuth", false)) s += 2
        val sdk = info.optString("sdkVersion")
        if (sdk.isNotEmpty() && !sdk.startsWith("0.")) s += 2
        if (info.optInt("supportFunction", 0) == 128) s += 1
        // markers from the 800NK log. everything above is also true of the (unverified) 800MT
        // profile, these are what break the tie towards the geometry we've measured.
        if (info.optString("version_name") == "CFDL26.2.3.0.5") s += 3
        if (info.optString("HUID").startsWith("6KWV")) s += 2
        if (info.optBoolean("supportMirrorOverlayTouch", false)) s += 1
        return s
    }

    override fun buildClientInfoReply(info: JSONObject, huid: String?, phoneUuid: String): JSONObject =
        basePhoneClientInfo(huid, phoneUuid, advertisedSupportFunction).apply {
            put("supportScreenTouch", true)
        }

    override fun handleUnknownControl(
        tag: String, frame: PxcFrame, out: OutputStream, log: (String) -> Unit,
    ): Boolean = Cfdl26PortraitProfile.handleUnknownControl(tag, frame, out, log)
}

/**
 * bike D, CFDL16-class MotoPlay on modelId 66660742 (HUName CFMOTO-4A71BD, sdk 0.9.23.4, flavor
 * 65540, wifi direct DIRECT-go-CFMOTO-...). the 450SR.
 *
 * despite the newer modelId this is a 0.9.x unit like the 675: landscape panel asking for an 800x400
 * capture, no touch, mirrorMode. but unlike the 675 it says supportFunction=128 and sends the
 * CFDL26 control burst (0x103b0 password, 0x10470 voice cmds, 0x104a0 ota ports) which have to be
 * acked.
 *
 * without this profile the other three all score 1 and the tie went to CFDL26 portrait, which asks
 * AA for 720x1280, a tall image letterboxed into a narrow strip on this wide panel, i.e. the
 * "stretched to portrait" symptom.
 */
object Cfdl16MotoPlayLandscapeProfile : BikeProfile {
    override val name = "CFDL16 / MotoPlay Landscape (modelId 66660742)"
    override val requiresSockServerAuth = false
    override val supportsScreenTouch = false
    /** the bike says supportFunction=128, echo it. don't claim touch, it has none. */
    override val advertisedSupportFunction = 128
    /** this one never heartbeats us, so we have to, or its ~7s watchdog kills the session */
    override val requiresPhoneHeartbeat = true
    /** fill the 800x400 panel, cropping the 40px top/bottom margin AA leaves empty */
    override val fillCanvas = true
    /** from the bike's REQ_CONFIG_CAPTURE: it asks for 800x400. with AA at 800x480 that's
     * marginHeight=80, so AA keeps its ui in the visible 800x400 band. */
    override val panelSize = 800 to 400

    /** ~800x400 landscape panel, so AA landscape 800x480 and the compositor fits it */
    override val aaVideo = AaVideoSpec(AaResolution.LANDSCAPE_800x480, dpi = 160)

    override fun matchesModelId(modelId: String): Boolean = modelId.trim() == "66660742"

    /**
     * measured on the bike: up/down send volume, holding them sends next/prev track, and holding
     * enter sends play/pause.
     *
     * the holds were hidden for a while after a test where they seemed dead. that was wrong: later
     * logs show KEYCODE_MEDIA_NEXT/PREVIOUS arriving and driving the knob exactly like a press does
     * ("[BTN] Next track press -> Next item -> sendScroll delta=1"). they're back, and they're the
     * only spare gestures this dash has.
     *
     * no x2 on any of them. they're holds, and a hold can't repeat fast enough for the timer to
     * read two of them, which is the same reason there's no enter x2. see ButtonGesture.
     */
    override val knowsButtons = true
    override fun buttonLabel(gesture: ButtonGesture): String? = when (gesture) {
        ButtonGesture.VOL_UP_PRESS -> "▲ press"
        ButtonGesture.VOL_UP_DOUBLE -> "▲ double tap"
        ButtonGesture.VOL_DOWN_PRESS -> "▼ press"
        ButtonGesture.VOL_DOWN_DOUBLE -> "▼ double tap"
        ButtonGesture.ENTER_PRESS -> "Enter hold"
        ButtonGesture.NEXT_KEY -> "▲ hold"
        ButtonGesture.PREV_KEY -> "▼ hold"
        else -> null
    }

    override fun score(info: JSONObject): Int {
        var s = 0
        // CLIENT_INFO echoes the qr modelId in `channel`
        if (info.optString("channel") == "66660742") s += 10
        // 0.9.x sdk means CFDL16 class, not a CFDL26 (1.1.x) unit
        if (info.optString("sdkVersion").startsWith("0.9")) s += 2
        if (info.optBoolean("supportLandscapeAdaptive", false)) s += 1
        if (!info.optBoolean("supportScreenTouch", false)) s += 1
        return s
    }

    override fun buildClientInfoReply(info: JSONObject, huid: String?, phoneUuid: String): JSONObject =
        basePhoneClientInfo(huid, phoneUuid, advertisedSupportFunction)

    // 0.9.x but it does send the CFDL26 control burst, so ack everything we don't handle (cmd+1,
    // empty) like the CFDL26 profiles or the bike stalls waiting
    override fun handleUnknownControl(
        tag: String, frame: PxcFrame, out: OutputStream, log: (String) -> Unit,
    ): Boolean = Cfdl26PortraitProfile.handleUnknownControl(tag, frame, out, log)
}
