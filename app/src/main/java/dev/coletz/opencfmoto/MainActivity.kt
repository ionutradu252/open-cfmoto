package dev.coletz.opencfmoto

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import dev.coletz.opencfmoto.aa.AaInput
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The whole app, in four tabs: Connect (pair → connect → navigate), Control (knob/D-pad/voice),
 * Settings and Logs.
 *
 * Tabs are `<include>`d layouts toggled by visibility, not Fragments: launching Google Android Auto
 * destroys and recreates this activity mid-hand-off, and the connection deliberately outlives it
 * (see [onDestroy]) — one flat view tree with no fragment lifecycle is far less to go wrong.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var connectBtn: MaterialButton
    private lateinit var connectHint: TextView
    private val tabs = mutableListOf<View>()
    private val uiHandler = Handler(Looper.getMainLooper())
    private lateinit var prober: EasyConnProber
    private var bleWakeUp: BleWakeUp? = null
    /** True when the pending QR scan should kick off the Android Auto flow (vs the mirror path). */
    private var pendingAaStart = false

    private val scanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val raw = result.data?.getStringExtra(QrScanActivity.RESULT_QR)
        if (result.resultCode != RESULT_OK || raw == null) {
            log("QR scan cancelled")
            return@registerForActivityResult
        }
        log("QR raw: $raw")
        val qr = QrData.parse(raw)
        if (qr == null) {
            log("QR parse FAILED — missing ssid/pwd?")
            Toast.makeText(this, "Invalid QR", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        log(
            "QR parsed: ssid=${qr.ssid} mac=${qr.mac} action=${qr.action} " +
                "(ap=${qr.supportsAp}, p2p=${qr.supportsP2p}) modelId=${qr.modelId} sn=${qr.sn}"
        )
        // Remember the bike's Wi-Fi so we can auto-connect next time without re-scanning.
        BikeCreds.save(applicationContext, qr)
        showBikeInfo()

        if (pendingAaStart) {
            pendingAaStart = false
            connectAa(qr)
        } else {
            // Mirror path (screen projection already armed): pick profile, connect straight away.
            BikeProfileHolder.active = BikeProfiles.selectByQr(qr)
            val spec = BikeProfileHolder.active.aaVideo
            log("→ bike profile (modelId=${qr.modelId}): ${BikeProfileHolder.active.name} " +
                "→ AA ${spec.width}x${spec.height} @${spec.dpi}dpi")
            joinAndStart(qr)
        }
    }

    /**
     * Start the full Android Auto → bike flow for a bike ([qr] from a fresh scan or saved creds):
     * pick the screen profile, bring up the receiver, and (once AA video is steady) join the bike
     * Wi-Fi and run the PXC handshake. Uses process-global state so it survives the activity being
     * recreated when Google Android Auto launches.
     */
    private fun connectAa(qr: QrData) {
        BikeProfileHolder.active = BikeProfiles.selectByQr(qr)
        val spec = BikeProfileHolder.active.aaVideo
        log("→ bike profile (modelId=${qr.modelId}): ${BikeProfileHolder.active.name} " +
            "→ AA ${spec.width}x${spec.height} @${spec.dpi}dpi")
        log("→ starting Android Auto receiver (loopback self-mode). Ensure Android Auto is installed & set up.")
        AaVideoBridge.onSteadyVideo = {
            AaVideoBridge.onSteadyVideo = null
            LogBus.log("→ Android Auto video is live — joining bike Wi-Fi")
            joinAndStart(qr)
        }
        AndroidAutoService.start(this)
        // Trigger Google AA to project from the FOREGROUND activity (background-activity-launch
        // safe on Android 12+/15), after giving the service's :5288 server time to bind.
        uiHandler.postDelayed({
            dev.coletz.opencfmoto.aa.AaSelfMode.trigger(this, log = ::log)
        }, 900)
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK || result.data == null) {
            log("screen-capture consent declined")
            return@registerForActivityResult
        }
        // FGS of type mediaProjection must be RUNNING before getMediaProjection() on API 34+.
        // startForegroundService is async, so poll the service's foreground flag (~every 100ms)
        // instead of guessing a fixed delay.
        ProjectionService.start(this)
        val code = result.resultCode
        val data = result.data!!
        val maxTries = 50  // 50 * 100ms = 5s ceiling
        val poll = object : Runnable {
            var tries = 0
            override fun run() {
                if (ProjectionService.isForeground) {
                    try {
                        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        ProjectionHolder.projection = mpm.getMediaProjection(code, data)
                        // Mirror used to always re-scan the QR. Reuse the saved bike like Start does.
                        val saved = BikeCreds.load(applicationContext)
                        if (saved != null) {
                            log("screen-capture armed — using saved bike Wi-Fi (${saved.ssid}), no QR needed")
                            BikeProfileHolder.active = BikeProfiles.selectByQr(saved)
                            joinAndStart(saved)
                        } else {
                            log("screen-capture armed (FGS up after ${tries * 100}ms) — now scan the QR")
                            scanLauncher.launch(Intent(this@MainActivity, QrScanActivity::class.java))
                        }
                    } catch (e: Exception) {
                        log("getMediaProjection failed: $e")
                        ProjectionService.stop(this@MainActivity)
                    }
                } else if (tries++ < maxTries) {
                    uiHandler.postDelayed(this, 100)
                } else {
                    log("foreground service did not start within 5s — aborting mirror")
                    ProjectionService.stop(this@MainActivity)
                }
            }
        }
        uiHandler.post(poll)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }

        setupTabs()
        setupLogTab()
        setupConnectTab()
        setupControlTab()
        setupSettingsTab()

        findViewById<TextView>(R.id.tv_version).text = "v${BuildConfig.VERSION_NAME}"

        // Reuse the process-global prober if one already exists (e.g. this activity was recreated
        // while the Android Auto receiver kept running in the foreground service). Constructing a
        // fresh one here would orphan the running instance — leaking its sockets/threads and making
        // the Stop button operate on the wrong object. See [BikeLink].
        prober = BikeLink.prober ?: EasyConnProber(applicationContext, LogBus::log).also { BikeLink.prober = it }

        requestPermissions()

        // Display/button/auto-connect preferences are persisted; load the display override once here
        // so the compositor uses it from the first frame.
        DisplayMode.load(applicationContext)

        // The log mirror and the status tick are attached in onResume, not here — see [onPause].
        log("Ready. Tap Connect — first time, scan the QR the dash shows in MotoPlay.")

        // Auto-connect on open: if we've saved a bike and nothing is running, start Android Auto
        // straight away (no QR scan). Guarded so an activity recreation (which Google AA triggers)
        // doesn't re-fire it.
        if (!AndroidAutoService.isRunning && !autoConnectDone && AppPrefs.isAutoConnect(applicationContext)) {
            BikeCreds.load(applicationContext)?.let { saved ->
                autoConnectDone = true
                log("→ auto-connect: saved bike (ssid=${saved.ssid}) — starting Android Auto")
                ProjectionHolder.projection = null
                ensureLocationPermission()
                connectAa(saved)
            }
        }
    }

    // ─────────────────────────── tabs ───────────────────────────

    private fun setupTabs() {
        tabs.clear()
        tabs += listOf(
            findViewById(R.id.tab_connect),
            findViewById(R.id.tab_control),
            findViewById(R.id.tab_settings),
            findViewById(R.id.tab_logs),
        )
        val nav = findViewById<BottomNavigationView>(R.id.bottom_nav)
        nav.setOnItemSelectedListener { item ->
            showTab(
                when (item.itemId) {
                    R.id.nav_control -> 1
                    R.id.nav_settings -> 2
                    R.id.nav_logs -> 3
                    else -> 0
                }
            )
            true
        }
        nav.selectedItemId = when (currentTab) {
            1 -> R.id.nav_control
            2 -> R.id.nav_settings
            3 -> R.id.nav_logs
            else -> R.id.nav_connect
        }
        showTab(currentTab)
    }

    private fun showTab(index: Int) {
        currentTab = index
        tabs.forEachIndexed { i, v -> v.visibility = if (i == index) View.VISIBLE else View.GONE }
    }

    // ─────────────────────────── connect tab ───────────────────────────

    private fun setupConnectTab() {
        connectBtn = findViewById(R.id.btn_connect)
        connectHint = findViewById(R.id.tv_connect_hint)

        // One button for the whole thing: start when idle, stop when running.
        connectBtn.setOnClickListener {
            if (AndroidAutoService.isRunning) stopEverything() else startAa()
        }

        findViewById<MaterialButton>(R.id.btn_scan_qr).setOnClickListener {
            log("→ scan the QR the dash shows in MotoPlay")
            pendingAaStart = true
            ProjectionHolder.projection = null   // bike uses the AA pipeline, not mirror
            ensureLocationPermission()
            try {
                scanLauncher.launch(Intent(this, QrScanActivity::class.java))
            } catch (e: Exception) {
                log("scan launch failed ($e)")
                pendingAaStart = false
            }
        }

        findViewById<MaterialButton>(R.id.btn_forget_bike).setOnClickListener {
            BikeCreds.clear(applicationContext)
            log("→ forgot saved bike — Connect will scan the QR again")
            Toast.makeText(this, "Saved bike forgotten", Toast.LENGTH_SHORT).show()
            showBikeInfo()
        }

        // Type a destination → start Google Maps navigation, which shows on the dash via Android
        // Auto. The easiest way to navigate on a non-touch dash: no on-screen interaction needed.
        findViewById<MaterialButton>(R.id.btn_navigate).setOnClickListener { startNavigation() }
        findViewById<EditText>(R.id.et_destination).setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) { startNavigation(); true } else false
        }

        val tutorialBody = findViewById<TextView>(R.id.tv_tutorial_body)
        val chevron = findViewById<TextView>(R.id.tv_tutorial_chevron)
        findViewById<View>(R.id.row_tutorial).setOnClickListener {
            val open = tutorialBody.visibility != View.VISIBLE
            tutorialBody.visibility = if (open) View.VISIBLE else View.GONE
            chevron.text = if (open) "▴" else "▾"
        }

        findViewById<MaterialButton>(R.id.btn_mirror_start).setOnClickListener {
            log("→ Mirror Mode: requesting screen-capture consent…")
            pendingAaStart = false
            ensureLocationPermission()
            try {
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                projectionLauncher.launch(mpm.createScreenCaptureIntent())
            } catch (e: Exception) {
                log("mirror start failed ($e)")
            }
        }

        showBikeInfo()
    }

    private fun startAa() {
        val saved = BikeCreds.load(applicationContext)
        if (saved == null) {
            log("→ no bike saved yet — scan the QR the dash shows in MotoPlay (step 1)")
            Toast.makeText(this, "Scan the bike QR first", Toast.LENGTH_SHORT).show()
            findViewById<MaterialButton>(R.id.btn_scan_qr).performClick()
            return
        }
        // Known bike: skip the QR scan (SSID/pwd are stable) — Forget makes it scan again.
        log("→ using saved bike Wi-Fi (ssid=${saved.ssid}) — skipping QR")
        ProjectionHolder.projection = null
        ensureLocationPermission()
        connectAa(saved)
    }

    /** Stop everything: Android Auto receiver, bike PXC, projection, and leave the bike Wi-Fi. */
    private fun stopEverything() {
        log("→ stopping everything (Android Auto + bike)")
        AaVideoBridge.onSteadyVideo = null
        BikeReconnector.cancel()   // don't auto-rejoin after a deliberate stop
        AndroidAutoService.stop(this)
        prober.stop()
        bleWakeUp?.stop()
        bleWakeUp = null
        ProjectionHolder.projection?.let { try { it.stop() } catch (_: Exception) {} }
        ProjectionHolder.projection = null
        ProjectionService.stop(this)
        BikeWifi.leave(this, ::log)
    }

    /** Show which bike is remembered (or that none is), so "Forget" has visible meaning. */
    private fun showBikeInfo() {
        val saved = BikeCreds.load(applicationContext)
        findViewById<TextView>(R.id.tv_bike_info).text = if (saved == null) {
            "No bike saved. On the dash open MotoPlay so the QR appears, then scan it — once."
        } else {
            "Saved: ${saved.ssid}  ·  model ${saved.modelId ?: "?"}\n" +
                "Screen profile: ${BikeProfiles.selectByQr(saved).name}"
        }
        findViewById<TextView>(R.id.tv_profile_info).text = saved?.let {
            val p = BikeProfiles.selectByQr(it)
            val s = p.aaVideo
            "profile: ${p.name}\nAA video: ${s.width}x${s.height} @${s.dpi}dpi\n" +
                "panel: ${p.panelSize?.let { ps -> "${ps.first}x${ps.second}" } ?: "—"}"
        } ?: ""
    }

    // ─────────────────────────── control tab ───────────────────────────

    private fun setupControlTab() {
        // Android Auto D-pad — drive a non-touch dash from the phone (set a destination, then ride).
        findViewById<MaterialButton>(R.id.btn_key_up).setOnClickListener { sendAaKey(AaInput.KEY_UP) }
        findViewById<MaterialButton>(R.id.btn_key_down).setOnClickListener { sendAaKey(AaInput.KEY_DOWN) }
        findViewById<MaterialButton>(R.id.btn_key_left).setOnClickListener { sendAaKey(AaInput.KEY_LEFT) }
        findViewById<MaterialButton>(R.id.btn_key_right).setOnClickListener { sendAaKey(AaInput.KEY_RIGHT) }
        findViewById<MaterialButton>(R.id.btn_key_enter).setOnClickListener { sendAaKey(AaInput.KEY_ENTER) }
        findViewById<MaterialButton>(R.id.btn_key_back).setOnClickListener { sendAaKey(AaInput.KEY_BACK) }
        findViewById<MaterialButton>(R.id.btn_key_home).setOnClickListener { sendAaKey(AaInput.KEY_HOME) }
        // Rotary knob: AA treats this dash as a rotary HU, so the knob (not the arrows) is what
        // steps focus through list items.
        findViewById<MaterialButton>(R.id.btn_scroll_back).setOnClickListener { sendAaScroll(-1) }
        findViewById<MaterialButton>(R.id.btn_scroll_fwd).setOnClickListener { sendAaScroll(+1) }
        // Voice: the same key a head unit's talk button sends. AA then opens the mic channel and
        // AaMicrophone streams the phone's mic (i.e. the helmet headset) back to it.
        findViewById<MaterialButton>(R.id.btn_key_assistant).setOnClickListener {
            sendAaKey(AaInput.KEY_ASSISTANT)
        }

        val micStatus = findViewById<TextView>(R.id.tv_mic_status)
        micStatus.setOnClickListener {
            if (!hasMic()) ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), 4,
            )
        }
        refreshMicStatus()
    }

    private fun hasMic() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun refreshMicStatus() {
        findViewById<TextView>(R.id.tv_mic_status).text = if (hasMic()) {
            "Microphone ready — the Assistant hears whatever your phone hears, so a helmet headset works."
        } else {
            "Microphone permission not granted — tap here to fix, or the Assistant can't hear you."
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 4) refreshMicStatus()
    }

    // ─────────────────────────── settings tab ───────────────────────────

    private fun setupSettingsTab() {
        // Display: fill vs letterbox (applies live to the running compositor).
        DisplayMode.load(applicationContext)
        findViewById<MaterialSwitch>(R.id.sw_fill_mode).apply {
            isChecked = DisplayMode.effective()
            setOnCheckedChangeListener { _, checked ->
                DisplayMode.set(applicationContext, checked)
                AaVideoBridge.pipeline?.refreshDisplayMode()
                LogBus.log("→ display mode: ${if (checked) "fill (whole screen)" else "letterbox (bars)"}")
            }
        }

        // Controls: bike buttons → Android Auto vs media (applies live).
        findViewById<MaterialSwitch>(R.id.sw_button_mode).apply {
            isChecked = ButtonMode.isControlAa(applicationContext)
            setOnCheckedChangeListener { _, checked ->
                ButtonMode.set(applicationContext, checked)
                MediaButtonBridge.instance?.setCaptureActive(checked)
                LogBus.log("→ bike buttons now control ${if (checked) "Android Auto" else "media (music)"}")
            }
        }

        // Audio: nothing behind it yet — the switch is disabled in the layout, so just reflect state.
        findViewById<MaterialSwitch>(R.id.sw_aa_audio).isChecked = AppPrefs.isAaAudio(applicationContext)

        findViewById<MaterialSwitch>(R.id.sw_auto_connect).apply {
            isChecked = AppPrefs.isAutoConnect(applicationContext)
            setOnCheckedChangeListener { _, checked ->
                AppPrefs.setAutoConnect(applicationContext, checked)
                LogBus.log("→ auto-connect on open: ${if (checked) "on" else "off"}")
            }
        }

        findViewById<MaterialSwitch>(R.id.sw_reconnect).apply {
            isChecked = AppPrefs.isReconnect(applicationContext)
            setOnCheckedChangeListener { _, checked ->
                AppPrefs.setReconnect(applicationContext, checked)
                LogBus.log("→ rejoin after ignition cycle: ${if (checked) "on" else "off"}")
            }
        }

        findViewById<MaterialButton>(R.id.btn_button_mapping).setOnClickListener {
            startActivity(Intent(this, ButtonMappingActivity::class.java))
        }

        findViewById<MaterialButton>(R.id.btn_aa_settings).setOnClickListener { openAndroidAutoSettings() }
    }

    private fun openAndroidAutoSettings() {
        LogBus.log("→ opening Android Auto settings…")
        try {
            startActivity(
                Intent("android.settings.ANDROID_AUTO_SETTINGS")
                    .setPackage("com.google.android.projection.gearhead")
            )
        } catch (e: Exception) {
            try {
                startActivity(
                    Intent(Intent.ACTION_MAIN).setClassName(
                        "com.google.android.projection.gearhead",
                        "com.google.android.projection.gearhead.companion.settings.DefaultSettingsActivity",
                    )
                )
            } catch (e2: Exception) {
                LogBus.log("failed to open Android Auto settings ($e2)")
                Toast.makeText(this, "Couldn't open Android Auto settings", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ─────────────────────────── logs tab ───────────────────────────

    private fun setupLogTab() {
        logView = findViewById(R.id.log_view)
        logScroll = findViewById(R.id.log_scroll)
        logView.movementMethod = ScrollingMovementMethod()

        findViewById<MaterialButton>(R.id.btn_share_log).setOnClickListener { shareLog() }
        findViewById<MaterialButton>(R.id.btn_clear).setOnClickListener {
            LogBus.clear()
            logView.text = ""
        }
    }

    /**
     * Mirror the process-wide log into the view — but only while we're actually on screen.
     *
     * Everything (bike PXC, the AA receiver, the video pipeline — including the parts inside the
     * foreground service) logs through [LogBus], which fires on the caller's thread. During a ride
     * the phone is pocketed with the screen off, and this listener was still hopping to the main
     * thread to append + re-scroll for every single line. Attaching in onResume and dropping it in
     * onPause makes that cost exist only when someone is looking; [LogBus] keeps buffering
     * regardless, so nothing is lost and Share still exports the whole session.
     */
    private fun attachLogListener() {
        logView.text = LogBus.snapshot()
        logScroll.post { logScroll.scrollTo(0, logView.bottom) }
        LogBus.listener = { line ->
            runOnUiThread {
                // Only follow the tail if the user is already at the bottom — otherwise scrolling back
                // to read something would keep yanking them down. Check BEFORE appending.
                val follow = !logScroll.canScrollVertically(1)
                logView.append("$line\n")
                if (follow) {
                    // NOT fullScroll(FOCUS_DOWN): despite the name it also MOVES FOCUS to the
                    // bottom-most focusable view, which stole focus from the "Navigate to" field on
                    // every single log line (you could only type one letter before it dropped).
                    // scrollTo() just scrolls and leaves focus alone.
                    logScroll.post { logScroll.scrollTo(0, logView.bottom) }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        attachLogListener()
        uiHandler.removeCallbacks(statusTick)
        uiHandler.post(statusTick)
        refreshMicStatus()
        showBikeInfo()
    }

    /** Nothing on screen → no log mirroring and no 1 Hz status refresh. The ride keeps running. */
    override fun onPause() {
        LogBus.listener = null
        uiHandler.removeCallbacks(statusTick)
        super.onPause()
    }

    // ─────────────────────────── status ───────────────────────────

    /** Refresh the status header, the connect button and the control tab once a second. */
    private val statusTick = object : Runnable {
        override fun run() {
            val live = ContextCompat.getColor(this@MainActivity, R.color.status_live)
            val idle = ContextCompat.getColor(this@MainActivity, R.color.status_idle)
            fun set(id: Int, on: Boolean, text: String) {
                findViewById<TextView>(id).apply {
                    this.text = (if (on) "● " else "○ ") + text
                    setTextColor(if (on) live else idle)
                }
            }

            val aa = AppStatus.aaConnected
            val bike = BikeLink.prober?.bikeConnected == true
            val frames = BikeLink.prober?.framesSentCount ?: 0
            val keys = AaVideoBridge.keySink != null
            set(R.id.tv_stat_aa, aa, if (aa) "${AppStatus.aaFps} fps" else "off")
            set(R.id.tv_stat_bike, bike, if (bike) fmt(frames) else "off")
            set(R.id.tv_stat_keys, keys, if (keys) "ready" else "—")

            refreshConnectButton(aa, bike)

            // Dead keys are worse than no keys: swap the pad for an explanation until AA is live.
            findViewById<View>(R.id.control_body).visibility = if (keys) View.VISIBLE else View.GONE
            findViewById<View>(R.id.control_empty).visibility = if (keys) View.GONE else View.VISIBLE

            uiHandler.postDelayed(this, 1000)
        }
    }

    private fun fmt(n: Int) = if (n >= 1000) "${n / 1000}k" else n.toString()

    private fun refreshConnectButton(aa: Boolean, bike: Boolean) {
        val running = AndroidAutoService.isRunning
        connectBtn.text = if (running) "Stop" else "Start Android Auto"
        val bg = MaterialColors.getColor(
            connectBtn,
            // colorPrimary comes from appcompat; the rest are Material 3's own.
            if (running) com.google.android.material.R.attr.colorErrorContainer
            else androidx.appcompat.R.attr.colorPrimary,
        )
        val fg = MaterialColors.getColor(
            connectBtn,
            if (running) com.google.android.material.R.attr.colorOnErrorContainer
            else com.google.android.material.R.attr.colorOnPrimary,
        )
        connectBtn.backgroundTintList = android.content.res.ColorStateList.valueOf(bg)
        connectBtn.setTextColor(fg)
        connectBtn.iconTint = android.content.res.ColorStateList.valueOf(fg)

        connectHint.text = when {
            !running && BikeCreds.load(applicationContext) == null ->
                "Scan the bike's QR first (step 1)."
            !running -> "Ignition on, then tap. No QR needed — the bike is saved."
            running && !aa -> "Waiting for Android Auto to start projecting…"
            running && !bike -> "Android Auto is up — joining the bike's Wi-Fi…"
            else -> "Live. The dash is showing Android Auto."
        }
    }

    // ─────────────────────────── plumbing ───────────────────────────

    private fun requestPermissions() {
        // Android 13+: request notification permission up front so the mediaProjection
        // foreground-service notification can be posted (some setups gate the FGS on it).
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3,
            )
        }

        // The Assistant needs the mic BEFORE Android Auto asks for it: AA opens the microphone
        // channel mid-session, and a denied RECORD_AUDIO there just fails the request silently.
        if (!hasMic()) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 4)
        }
    }

    override fun onDestroy() {
        LogBus.listener = null
        uiHandler.removeCallbacks(statusTick)
        // When the Android Auto receiver service is running, the whole AA→bike chain (receiver +
        // encoder in the FGS, plus the Wi-Fi + prober in process globals) must OUTLIVE this activity:
        // launching Google Android Auto can destroy/recreate MainActivity mid-hand-off, and tearing
        // the bike down here is exactly what left the dash on a black screen (the pending
        // onSteadyVideo hand-off was cancelled before it could fire). Only tear down when AA is NOT
        // running — i.e. the mirror path or a genuine exit. Full teardown is the "Stop" button.
        if (!AndroidAutoService.isRunning) {
            AaVideoBridge.onSteadyVideo = null
            prober.stop()
            bleWakeUp?.stop()
            bleWakeUp = null
            ProjectionHolder.projection?.let { try { it.stop() } catch (_: Exception) {} }
            ProjectionHolder.projection = null
            ProjectionService.stop(this)
            // NOTE: AndroidAutoService is intentionally NOT stopped here — it is a foreground service
            // meant to keep running when the phone is backgrounded/locked. Use "Stop".
            BikeWifi.leave(this, ::log)
        }
        super.onDestroy()
    }

    private fun joinAndStart(qr: QrData) {
        // Hand the connection to BikeReconnector: it owns the Wi-Fi join and re-joins by itself when
        // the bike's AP drops (ignition off) and returns — no more manual Stop/Start. It uses
        // applicationContext + the process-global prober, so it survives this activity being
        // destroyed/recreated (which launching Google Android Auto can cause).
        BikeLink.prober = BikeLink.prober ?: prober
        BikeReconnector.connect(applicationContext, qr)
    }

    private fun ensureLocationPermission() {
        // Some OEMs require fine location to associate via WifiNetworkSpecifier.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1,
            )
        }
    }

    private fun shareLog() {
        try {
            val dir = File(cacheDir, "logs").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val file = File(dir, "opencfmoto-$stamp.log")
            file.writeText(LogBus.snapshot())
            val uris = ArrayList<Uri>()
            uris.add(FileProvider.getUriForFile(this, "$packageName.fileprovider", file))

            // Attach any diagnostic H.264 dumps (VideoPipeline writes these to <externalFiles>/video).
            val videoDir = File(getExternalFilesDir(null), "video")
            val dumps = videoDir.listFiles { f -> f.name.endsWith(".h264") }?.sortedBy { it.name } ?: emptyList()
            for (d in dumps) {
                uris.add(FileProvider.getUriForFile(this, "$packageName.fileprovider", d))
                log("attaching video dump: ${d.name} (${d.length()} bytes)")
            }

            val send = Intent(if (uris.size > 1) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND).apply {
                type = if (uris.size > 1) "*/*" else "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "opencfmoto log $stamp")
                if (uris.size > 1) putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                else putExtra(Intent.EXTRA_STREAM, uris[0])
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Share log"))
            log("log saved: ${file.absolutePath} (${file.length()} bytes)")
        } catch (e: Exception) {
            log("share failed: $e")
        }
    }

    /** Launch Google Maps turn-by-turn navigation to the typed destination. With Android Auto
     *  projecting, the navigation appears on the bike dash — no on-dash interaction needed. */
    private fun startNavigation() {
        val dest = findViewById<EditText>(R.id.et_destination).text.toString().trim()
        if (dest.isEmpty()) {
            Toast.makeText(this, "Type a destination first", Toast.LENGTH_SHORT).show()
            return
        }
        // Same launcher the handlebar NAV_* actions use, so both behave identically.
        if (!NavLauncher.navigate(this, dest, ::log)) {
            Toast.makeText(this, "Couldn't start navigation (Google Maps?)", Toast.LENGTH_SHORT).show()
        }
    }

    /** Emulate one rotary-knob click (-1 back / +1 forward) in the live AA session. */
    private fun sendAaScroll(delta: Int) {
        val sink = AaVideoBridge.scrollSink
        if (sink == null) {
            log("AA control: no active Android Auto session — connect first")
            Toast.makeText(this, "Start Android Auto first", Toast.LENGTH_SHORT).show()
            return
        }
        sink(delta)
    }

    /** Send an Android Auto key press via the live AA session, or note that none is active. */
    private fun sendAaKey(keycode: Int) {
        val sink = AaVideoBridge.keySink
        if (sink == null) {
            log("AA control: no active Android Auto session — connect first")
            Toast.makeText(this, "Start Android Auto first", Toast.LENGTH_SHORT).show()
            return
        }
        sink(keycode)
    }

    private fun log(msg: String) = LogBus.log(msg)

    companion object {
        /** Process-lived guard so auto-connect fires once, not again on the AA-triggered recreation. */
        @Volatile private var autoConnectDone = false
        /** Survives the activity recreation Google Android Auto causes, so the tab doesn't jump back. */
        @Volatile private var currentTab = 0
    }
}
