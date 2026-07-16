package dev.snaipdefix.opencflink

import android.os.Build
import org.json.JSONObject
import java.io.OutputStream

/**
 * Strategy for a specific CFMoto dashboard generation ("bike variant").
 *
 * The PXC/EasyConn protocol is broadly the same across dashboards, but newer head units add
 * steps and fields the older ones don't. Rather than branch on version literals all over the
 * handshake, each dashboard gets a [BikeProfile] selected from the bike's CLIENT_INFO
 * ([BikeProfiles.select]) and consulted at the points where behavior diverges.
 *
 * Selected in [PxcHandshake.onClientInfo] and stored on the shared [PxcHandshake] instance;
 * the media plane reaches it via `handshake.profile`. The bike opens the media ports only AFTER
 * the control handshake, so the profile is always chosen before the media plane needs it.
 */
enum class AaResolution(val w: Int, val h: Int) {
    LANDSCAPE_800x480(800, 480),
    LANDSCAPE_1280x720(1280, 720),
    PORTRAIT_720x1280(720, 1280),
    PORTRAIT_1080x1920(1080, 1920),
}

/** The Android Auto video config a dash should request: orientation/size + panel density. */
data class AaVideoSpec(val resolution: AaResolution, val dpi: Int) {
    val width: Int get() = resolution.w
    val height: Int get() = resolution.h
}

interface BikeProfile {
    /** Human-readable label — appears in bike-test logs so a capture is self-describing. */
    val name: String

    /** How strongly this profile claims a given CLIENT_INFO. Highest positive score wins; 0 = no claim. */
    fun score(info: JSONObject): Int

    /**
     * Coarse match from the QR `modelId` — the only bike identity available BEFORE connecting.
     * Used to pick [aaVideo] up front (Android Auto's resolution must be chosen before AA starts,
     * which is before the CLIENT_INFO handshake). CLIENT_INFO scoring refines it later.
     */
    fun matchesModelId(modelId: String): Boolean = false

    /** The Android Auto video config this dash should request (orientation + size + density). */
    val aaVideo: AaVideoSpec

    // ---- capability flags ----
    /** Bike advertises enableSockServerAuth — likely needs an auth exchange before media (see Cfdl26). */
    val requiresSockServerAuth: Boolean
    val supportsScreenTouch: Boolean
    /** Value we advertise in our own CLIENT_INFO reply's `supportFunction`. */
    val advertisedSupportFunction: Int

    /** Build the phone's CLIENT_INFO reply (cmd 0x10011). `phoneUuid` is owned by [PxcHandshake]. */
    fun buildClientInfoReply(info: JSONObject, huid: String?, phoneUuid: String): JSONObject

    /**
     * Handle a control-plane cmd not covered by [PxcHandshake]'s fixed switch. Return true if the
     * profile replied/consumed it; false to fall back to the legacy "log only, no reply" behavior.
     */
    fun handleUnknownControl(
        tag: String, frame: PxcFrame, out: OutputStream, log: (String) -> Unit,
    ): Boolean

    // ---- media-plane hooks (behavior-preserving defaults; only rounding is wired this pass) ----
    /** Round and fit requested capture dimensions. Returns Pair(width, height) normalized for this profile. */
    fun roundCaptureDimensions(w: Int, h: Int): Pair<Int, Int> = (w and 0xFFF0) to (h and 0xFFF0)

    /** Whether to force the H.264 encoder to Baseline Profile @ Level 3.1. */
    val forceBaseline: Boolean get() = true

    /**
     * Some dashboards (e.g. the CFDL16 on modelId 66660742) never send their own control-plane
     * heartbeat, so the control socket sits idle and the bike's socketTimeoutPeriodWifi watchdog
     * tears the whole session down every ~7s. When true, EasyConnProber proactively sends
     * CMD_HEARTBEAT on the control socket to keep the session alive.
     */
    val requiresPhoneHeartbeat: Boolean get() = false

    /**
     * How to fit the Android Auto source into the bike canvas when the aspect ratios differ:
     *   false = letterbox — aspect-preserved, centered, black bars (safe default; nothing cropped).
     *   true  = fill — scale to cover the whole panel, cropping the overflow (no bars).
     */
    val fillCanvas: Boolean get() = false

    /**
     * The dash's real panel size, when known (e.g. 800x400). Android Auto only renders at fixed
     * resolutions (800x480, 1280x720, …), so a 2:1 panel can never be an exact match. Declaring the
     * difference as MARGINS in service discovery makes AA lay its UI out inside the visible
     * `panelSize` area and leave the rest empty — the compositor's fill/crop then removes exactly
     * that empty band, giving a pixel-perfect fit with nothing real cropped and no black bars.
     * Null = unknown → declare no margins (AA uses the full frame; fill would crop real content).
     */
    val panelSize: Pair<Int, Int>? get() = null

    /** Media-plane GET_VERSION reply (version, subVersion). */
    fun versionReply(): Pair<Int, Int> = 3 to 1
}

/** Registry + selection. Never returns null — falls back to the legacy (BIKE A) profile. */
object BikeProfiles {
    val legacy: BikeProfile = LegacyCfdl16Profile
    private val all: List<BikeProfile> = listOf(
        Cfdl16MotoPlayLandscapeProfile, Cfdl26NkTouchProfile, Cfdl26PortraitProfile,
        Cfdl26LandscapeProfile, LegacyCfdl16Profile,
    )

    /** Authoritative selection from CLIENT_INFO (during the PXC handshake). */
    fun select(info: JSONObject, log: (String) -> Unit): BikeProfile {
        val scored = all.map { it to it.score(info) }
        log("[profile] scores=" + scored.joinToString { "${it.first.name}=${it.second}" })
        return scored.filter { it.second > 0 }.maxByOrNull { it.second }?.first ?: legacy
    }

    /** Early selection from the QR code data, before we connect. Falls back to legacy. */
    fun selectByQr(qr: QrData?): BikeProfile {
        if (qr == null) return legacy
        val matches = all.filter { it.matchesModelId(qr.modelId ?: "") }
        if (matches.isEmpty()) return legacy
        if (matches.size == 1) return matches[0]

        // Several CFDL26 bikes share modelId 37426, and the QR carries nothing else that separates
        // them — so this split decides the AA resolution for all of them, and it has to be decided
        // HERE: Android Auto's video size is fixed when AA connects, which is before the bike's
        // CLIENT_INFO (let alone its REQ_CONFIG_CAPTURE) ever arrives.
        //   • Wi-Fi Direct (DIRECT-… ssid) → 1000 MT-X, tall portrait panel.
        //   • AP mode → the 800NK. Its 720x712 panel is MEASURED from a real bike log; the 800MT
        //     profile's geometry never was, so when the two are indistinguishable we go with the one
        //     we have evidence for. A real 800MT would need a QR-time tell we don't have yet.
        val isP2p = qr.supportsP2p || qr.ssid.startsWith("DIRECT-")
        return if (isP2p) Cfdl26PortraitProfile else Cfdl26NkTouchProfile
    }

    /** Legacy helper for backward compatibility / tests. */
    fun selectByModelId(modelId: String?): BikeProfile =
        modelId?.let { id -> all.firstOrNull { it.matchesModelId(id) } } ?: legacy
}

/**
 * Process-wide active bike profile. Set early from the QR code data ([BikeProfiles.selectByQr])
 * so the Android Auto stack ([ServiceDiscoveryResponse]) can request the right resolution before AA
 * starts, then confirmed authoritatively from CLIENT_INFO in [PxcHandshake]. Read across the
 * activity + the Android Auto foreground service, so it lives here as a process global (like
 * [ProjectionHolder] / [AaVideoBridge]).
 */
object BikeProfileHolder {
    @Volatile var active: BikeProfile = BikeProfiles.legacy
}

/** Shared base CLIENT_INFO reply. Keys/order match the original PxcHandshake.buildClientInfoReply so
 *  that LegacyCfdl16Profile (supportFunction=0) produces byte-identical output. */
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
 * BIKE A — the CFDL16 head unit (sdkVersion 0.9.29.1) the app was reverse-engineered against and
 * confirmed working end-to-end. This profile reproduces the current behavior EXACTLY (byte-identical
 * CLIENT_INFO reply, no reply to unknown cmds) and is the safe default for any unrecognized bike.
 */
object LegacyCfdl16Profile : BikeProfile {
    override val name = "CFDL16 / legacy (BIKE A)"
    override val requiresSockServerAuth = false
    override val supportsScreenTouch = false
    override val advertisedSupportFunction = 0

    /** The 675's 5" panel is landscape ~800x386. */
    override val aaVideo = AaVideoSpec(AaResolution.LANDSCAPE_800x480, dpi = 160)

    /** Known 675 QR modelId. Legacy is also the fallback, so this is just for a positive early match. */
    override fun matchesModelId(modelId: String): Boolean = modelId.trim() == "37416"

    /** Constant floor so legacy always wins ties and is the guaranteed fallback. */
    override fun score(info: JSONObject): Int = 1

    override fun buildClientInfoReply(info: JSONObject, huid: String?, phoneUuid: String): JSONObject =
        basePhoneClientInfo(huid, phoneUuid, advertisedSupportFunction)

    override fun handleUnknownControl(
        tag: String, frame: PxcFrame, out: OutputStream, log: (String) -> Unit,
    ): Boolean = false  // reproduces the original "log only, no reply" else-branch
}

/**
 * BIKE B — the CFDL26 / MotoPlay head unit on the 1000 MT-X (sdkVersion 1.1.4,
 * package com.cfmoto.cfdashmotoplay, enableSockServerAuth=true, WiFi-Direct P2P).
 */
object Cfdl26PortraitProfile : BikeProfile {
    override val name = "CFDL26 / MotoPlay Portrait (1000 MT-X)"
    override val requiresSockServerAuth = true
    override val supportsScreenTouch = true
    override val advertisedSupportFunction = 128

    /** The 1000 MT-X's 8" panel is a tall PORTRAIT screen (requests ~800x951). Ask AA for portrait
     *  720x1280 at the panel's advertised 240 dpi; the compositor letterboxes it into the canvas. */
    override val aaVideo = AaVideoSpec(AaResolution.PORTRAIT_720x1280, dpi = 240)

    /** Known CFDL26 / 1000 MT-X QR modelId (from the bike's pairing QR). */
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
        // After CHECK_SN the CFDL26 unit sends a burst of JSON notify frames the older CFDL16 never
        // did — 0x10780 (log), 0x103a0 (OTA FTP creds), 0x10020 (media-feature flags), and possibly
        // more — and will NOT connect to the media ports until each is acked. The whole PXC protocol
        // acks with reply = cmd+1 (empty), so ack every otherwise-unhandled control frame that way.
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

/**
 * BIKE C — the CFDL26 / MotoPlay head unit on the 800MT (sdkVersion 1.1.2,
 * package com.cfmoto.easyconnect, enableSockServerAuth=true, AP mode).
 */
object Cfdl26LandscapeProfile : BikeProfile {
    override val name = "CFDL26 / MotoPlay Landscape (800MT)"
    override val requiresSockServerAuth = true
    override val supportsScreenTouch = true
    override val advertisedSupportFunction = 128

    /** The 800MT has a landscape screen. Ask AA for landscape 800x480 — the resolution proven to
     *  stream end-to-end on this dash (see docs/05-DEBUG-KNOWLEDGE.md). The compositor letterboxes it
     *  into the bike's ~1280x576 canvas. 1280x720 is sharper but unverified end-to-end; revisit once
     *  AA is confirmed working. */
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

    // Baseline@3.1 (the interface default). This USED to be false (Main/High), which is the prime
    // suspect for the Android Auto black screen: the dash accepts and continuously pulls our Main/High
    // frames but renders nothing (embedded decoders often only handle Baseline — no CABAC/B-frame
    // reordering, which the PTS-less bike wire format can't support anyway). Baseline is a strict
    // subset, so it cannot regress the working mirror path, and createEncoder falls back to default
    // if a device can't configure it. See docs/05-DEBUG-KNOWLEDGE.md §2.
    // override val forceBaseline defaults to true.

    override fun handleUnknownControl(
        tag: String, frame: PxcFrame, out: OutputStream, log: (String) -> Unit,
    ): Boolean {
        return Cfdl26PortraitProfile.handleUnknownControl(tag, frame, out, log)
    }
}

/**
 * BIKE E — the CFDL26 head unit on the 800NK: a TOUCHSCREEN, and a nearly-square 720x712 panel
 * (sdkVersion 1.1.2, package com.cfmoto.easyconnect, version_name CFDL26.2.3.0.5, AP mode).
 * Built from a real bike log (2026-07-16), including the geometry.
 *
 * **The panel is 720x712** — measured, not guessed: the dash asks for exactly that in
 * REQ_CONFIG_CAPTURE. Nothing Android Auto can render is anywhere near 1.01:1, which is why landing
 * on the 800MT profile (AA landscape 800x480) looked so wrong: letterbox squeezed the picture into a
 * 720x432 strip with 136px bars top and bottom, and fill scaled it to 1173x704 and threw away 226px
 * off *each side*.
 *
 * The fix is the margin trick the 450SR already uses, turned on its side. Ask AA for PORTRAIT
 * 720x1280: the width matches the panel EXACTLY, so pixels map 1:1 with no scaling at all, and
 * declaring marginHeight = 1280 − 712 = 568 makes AA keep its UI inside a 720x712 band centred in
 * that canvas. The compositor's fill then crops away precisely the empty band above and below.
 *
 * The margin is a big fraction of the canvas (44%, against 17% on the 450SR), so this is the part
 * most likely to need adjusting — if AA lays out badly, that ratio is the first thing to look at.
 * [AaVideoSpec.dpi] is the other guess: 160 mirrors the 450SR, but this panel is physically smaller
 * per pixel, so the UI may come out large.
 */
object Cfdl26NkTouchProfile : BikeProfile {
    override val name = "CFDL26 / 800NK (touch, 720x712)"
    override val requiresSockServerAuth = true
    override val supportsScreenTouch = true
    override val advertisedSupportFunction = 128
    /** Crop the empty band AA leaves above/below its UI — see the margin note above. */
    override val fillCanvas = true
    /** Measured from the bike's REQ_CONFIG_CAPTURE (w=720 h=712). */
    override val panelSize = 720 to 712

    /** Width matches the panel exactly → 1:1 pixels; marginHeight = 1280 − 712 = 568. */
    override val aaVideo = AaVideoSpec(AaResolution.PORTRAIT_720x1280, dpi = 160)

    /** Shared with the 1000 MT-X and 800MT — [BikeProfiles.selectByQr] does the real splitting. */
    override fun matchesModelId(modelId: String): Boolean = modelId.trim() == "37426"

    override fun score(info: JSONObject): Int {
        var s = 0
        if (info.optString("version_name").startsWith("CFDL26")) s += 4
        if (info.optString("package_name") == "com.cfmoto.easyconnect") s += 3
        if (info.optBoolean("enableSockServerAuth", false)) s += 2
        val sdk = info.optString("sdkVersion")
        if (sdk.isNotEmpty() && !sdk.startsWith("0.")) s += 2
        if (info.optInt("supportFunction", 0) == 128) s += 1
        // Concrete markers from the 800NK log. Everything above this line is also true of the
        // (unverified) 800MT profile — these are what break the tie in favour of measured geometry.
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
 * BIKE D — a CFDL16-class MotoPlay head unit seen on modelId 66660742 (HUName "CFMOTO-4A71BD",
 * HUID CRCP24…, sdkVersion 0.9.23.4, flavor 65540, Wi-Fi Direct "DIRECT-go-CFMOTO-…").
 *
 * Despite the newer modelId this is a 0.9.x unit like the 675 (BIKE A): a LANDSCAPE panel that
 * requests an 800x400 capture, supportScreenTouch=false, mirrorMode. But unlike the 675 it
 * advertises supportFunction=128 and sends the CFDL26-style control burst (0x103b0 password,
 * 0x10470 voice cmds, 0x104a0 OTA ports), so those must be acked.
 *
 * Without this profile the three existing profiles all score 1 and the tie resolves to CFDL26
 * PORTRAIT, which asks Android Auto for 720x1280 — a tall image the compositor letterboxes into a
 * narrow centered strip on this wide panel (the "stretched to portrait" symptom). This profile
 * pins it to LANDSCAPE 800x480 so the image fills the panel in the right orientation.
 */
object Cfdl16MotoPlayLandscapeProfile : BikeProfile {
    override val name = "CFDL16 / MotoPlay Landscape (modelId 66660742)"
    override val requiresSockServerAuth = false
    override val supportsScreenTouch = false
    /** The bike reports supportFunction=128; echo it (but do NOT claim touch — it has none). */
    override val advertisedSupportFunction = 128
    /** This unit never heartbeats us → the phone must, or the ~7s watchdog resets the session. */
    override val requiresPhoneHeartbeat = true
    /** Fill the whole 800x400 landscape panel (crop the 40px top/bottom margin AA leaves empty). */
    override val fillCanvas = true
    /** Verified from the bike's REQ_CONFIG_CAPTURE: it asks for an 800x400 capture. With AA at
     *  800x480 this declares marginHeight=80, so AA keeps its UI in the visible 800x400 band. */
    override val panelSize = 800 to 400

    /** Landscape ~800x400 panel → request AA landscape 800x480; the compositor fits it to 800x400. */
    override val aaVideo = AaVideoSpec(AaResolution.LANDSCAPE_800x480, dpi = 160)

    override fun matchesModelId(modelId: String): Boolean = modelId.trim() == "66660742"

    override fun score(info: JSONObject): Int {
        var s = 0
        // Definitive: CLIENT_INFO echoes the QR modelId in `channel`.
        if (info.optString("channel") == "66660742") s += 10
        // 0.9.x SDK ⇒ CFDL16-class, not a CFDL26 (1.1.x) unit — disambiguates from the CFDL26 profiles.
        if (info.optString("sdkVersion").startsWith("0.9")) s += 2
        if (info.optBoolean("supportLandscapeAdaptive", false)) s += 1
        if (!info.optBoolean("supportScreenTouch", false)) s += 1
        return s
    }

    override fun buildClientInfoReply(info: JSONObject, huid: String?, phoneUuid: String): JSONObject =
        basePhoneClientInfo(huid, phoneUuid, advertisedSupportFunction)

    // 0.9.x but it DOES send the CFDL26 control burst — ack every otherwise-unhandled control frame
    // (reply = cmd+1, empty) exactly like the CFDL26 profiles, or the bike stalls waiting for acks.
    override fun handleUnknownControl(
        tag: String, frame: PxcFrame, out: OutputStream, log: (String) -> Unit,
    ): Boolean = Cfdl26PortraitProfile.handleUnknownControl(tag, frame, out, log)
}
