package dev.snaipdefix.opencflink

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
import dev.snaipdefix.opencflink.aa.AaInput
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * the whole app in four tabs: connect, control, settings, logs.
 *
 * tabs are <include>d layouts toggled by visibility, not fragments. launching google AA destroys and
 * recreates this activity mid hand-off and the connection has to outlive it (see onDestroy), so one
 * flat view tree with no fragment lifecycle is a lot less to go wrong.
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
    /** true when the pending qr scan should start the AA flow rather than the mirror path */
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
        // remember the bike's wifi so we can auto connect next time
        BikeCreds.save(applicationContext, qr)
        showBikeInfo()

        if (pendingAaStart) {
            pendingAaStart = false
            connectAa(qr)
        } else {
            // mirror path, projection already armed: pick a profile and connect
            BikeProfileHolder.active = BikeProfiles.selectByQr(qr, applicationContext, ::log)
            val spec = BikeProfileHolder.active.aaVideo
            log("→ bike profile (modelId=${qr.modelId}): ${BikeProfileHolder.active.name} " +
                "→ AA ${spec.width}x${spec.height} @${spec.dpi}dpi")
            joinAndStart(qr)
        }
    }

    /**
     * start the whole AA -> bike flow: pick the screen profile, bring up the receiver, and once AA
     * video is steady join the bike wifi and run the pxc handshake. uses process globals so it
     * survives this activity being recreated when google AA launches.
     */
    private fun connectAa(qr: QrData) {
        BikeProfileHolder.active = BikeProfiles.selectByQr(qr, applicationContext, ::log)
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
        // trigger google AA from the foreground activity (background launch rules), after giving
        // the service's :5288 server time to bind
        uiHandler.postDelayed({
            dev.snaipdefix.opencflink.aa.AaSelfMode.trigger(this, log = ::log)
        }, 900)
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK || result.data == null) {
            log("screen-capture consent declined")
            return@registerForActivityResult
        }
        // a mediaProjection foreground service has to be running before getMediaProjection() on
        // api 34+. startForegroundService is async, so poll its flag instead of guessing a delay.
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
                        // mirror used to always re-scan the qr. reuse the saved bike like start does.
                        val saved = BikeCreds.load(applicationContext)
                        if (saved != null) {
                            log("screen-capture armed — using saved bike Wi-Fi (${saved.ssid}), no QR needed")
                            BikeProfileHolder.active = BikeProfiles.selectByQr(saved, applicationContext, ::log)
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

        // reuse the existing prober if there is one (this activity gets recreated while the
        // receiver keeps running in the service). making a new one would orphan the running
        // instance, leaking its sockets and threads and pointing stop at the wrong object.
        prober = BikeLink.prober ?: EasyConnProber(applicationContext, LogBus::log).also { BikeLink.prober = it }

        requestPermissions()

        // load the display prefs once here so the compositor has them from the first frame
        DisplayMode.load(applicationContext)
        DisplayAlign.load(applicationContext)
        ScreenMargins.load(applicationContext)

        // the log mirror and the status tick attach in onResume, see onPause
        log("Ready. Tap Connect. First time, scan the QR the dash shows in MotoPlay.")

        // auto connect on open, if a bike is saved and nothing is running. guarded so the activity
        // recreation google AA causes doesn't fire it twice.
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

        // one button for the whole thing: start when idle, stop when running
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
            Toast.makeText(this, "Bike forgotten", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, "Scan the QR first", Toast.LENGTH_SHORT).show()
            findViewById<MaterialButton>(R.id.btn_scan_qr).performClick()
            return
        }
        // Known bike: skip the QR scan (SSID/pwd are stable), Forget makes it scan again.
        log("→ using saved bike Wi-Fi (ssid=${saved.ssid}) — skipping QR")
        ProjectionHolder.projection = null
        ensureLocationPermission()
        connectAa(saved)
    }

    /** stop everything: receiver, pxc, projection, and leave the bike wifi */
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

    /** show which bike is remembered, so forget has visible meaning */
    private fun showBikeInfo() {
        val saved = BikeCreds.load(applicationContext)
        findViewById<TextView>(R.id.tv_bike_info).text = if (saved == null) {
            "No bike saved. Open MotoPlay on the dash so the QR appears, then scan it."
        } else {
            "${saved.ssid}  ·  model ${saved.modelId ?: "?"}\n" +
                BikeProfiles.selectByQr(saved, applicationContext).name
        }
        findViewById<TextView>(R.id.tv_profile_info).text = saved?.let {
            val p = BikeProfiles.selectByQr(it, applicationContext)
            val s = p.aaVideo
            "profile: ${p.name}\nAA video: ${s.width}x${s.height} @${s.dpi}dpi\n" +
                "panel: ${p.panelSize?.let { ps -> "${ps.first}x${ps.second}" } ?: "—"}"
        } ?: ""
    }

    // ─────────────────────────── control tab ───────────────────────────

    private fun setupControlTab() {
        // d-pad, for driving a non-touch dash from the phone
        findViewById<MaterialButton>(R.id.btn_key_up).setOnClickListener { sendAaKey(AaInput.KEY_UP) }
        findViewById<MaterialButton>(R.id.btn_key_down).setOnClickListener { sendAaKey(AaInput.KEY_DOWN) }
        findViewById<MaterialButton>(R.id.btn_key_left).setOnClickListener { sendAaKey(AaInput.KEY_LEFT) }
        findViewById<MaterialButton>(R.id.btn_key_right).setOnClickListener { sendAaKey(AaInput.KEY_RIGHT) }
        findViewById<MaterialButton>(R.id.btn_key_enter).setOnClickListener { sendAaKey(AaInput.KEY_ENTER) }
        findViewById<MaterialButton>(R.id.btn_key_back).setOnClickListener { sendAaKey(AaInput.KEY_BACK) }
        findViewById<MaterialButton>(R.id.btn_key_home).setOnClickListener { sendAaKey(AaInput.KEY_HOME) }
        // knob. AA treats us as a rotary head unit, so this (not the arrows) steps focus.
        findViewById<MaterialButton>(R.id.btn_scroll_back).setOnClickListener { sendAaScroll(-1) }
        findViewById<MaterialButton>(R.id.btn_scroll_fwd).setOnClickListener { sendAaScroll(+1) }
        // voice: the same key a head unit's talk button sends. AA then opens the mic channel and
        // AaMicrophone streams the phone's mic (the helmet headset) back to it.
        findViewById<MaterialButton>(R.id.btn_key_assistant).setOnClickListener {
            sendAaKey(AaInput.KEY_ASSISTANT)
        }

        findViewById<MaterialButton>(R.id.btn_dash_view).setOnClickListener {
            if (AaVideoBridge.pipeline == null) {
                Toast.makeText(this, "Connect first", Toast.LENGTH_SHORT).show()
            } else {
                startActivity(Intent(this, DashViewActivity::class.java))
            }
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
            "Microphone ready. It uses your helmet headset if one is connected."
        } else {
            "No microphone permission. Tap here to fix, or the Assistant can't hear you."
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
        // fill vs letterbox, applies live
        DisplayMode.load(applicationContext)
        DisplayAlign.load(applicationContext)
        ScreenMargins.load(applicationContext)
        findViewById<MaterialSwitch>(R.id.sw_fill_mode).apply {
            isChecked = DisplayMode.effective()
            setOnCheckedChangeListener { _, checked ->
                DisplayMode.set(applicationContext, checked)
                AaVideoBridge.pipeline?.refreshDisplayMode()
                LogBus.log("→ display mode: ${if (checked) "fill (whole screen)" else "letterbox (bars)"}")
            }
        }

        // bike buttons: drive AA or control media. applies live.
        findViewById<MaterialSwitch>(R.id.sw_button_mode).apply {
            isChecked = ButtonMode.isControlAa(applicationContext)
            setOnCheckedChangeListener { _, checked ->
                ButtonMode.set(applicationContext, checked)
                MediaButtonBridge.instance?.setCaptureActive(checked)
                LogBus.log("→ bike buttons now control ${if (checked) "Android Auto" else "media (music)"}")
            }
        }

        // nothing behind this yet, the switch is disabled in the layout
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

        findViewById<MaterialButton>(R.id.btn_align).setOnClickListener { pickAlign() }
        refreshAlign()

        findViewById<MaterialButton>(R.id.btn_night_mode).setOnClickListener { pickNightMode() }
        refreshNightMode()

        findViewById<MaterialButton>(R.id.btn_video_quality).setOnClickListener { pickVideoQuality() }
        refreshVideoQuality()

        findViewById<MaterialButton>(R.id.btn_button_mapping).setOnClickListener {
            startActivity(Intent(this, ButtonMappingActivity::class.java))
        }

        findViewById<MaterialButton>(R.id.btn_aa_settings).setOnClickListener { openAndroidAutoSettings() }

        findViewById<MaterialButton>(R.id.btn_margins).setOnClickListener { editMargins() }
        refreshMargins()

        findViewById<MaterialButton>(R.id.btn_check_updates).setOnClickListener { checkUpdates(manual = true) }
        // and once a day on our own, but only before we join the bike: its wifi has no internet.
        if (!AndroidAutoService.isRunning) checkUpdates(manual = false)
    }

    // ─────────────────────────── screen margins ───────────────────────────

    /**
     * black borders per edge, applied live so the dash can be watched while they change.
     *
     * this exists because MotoPlay's pull-down arrow sits on top of the projection on an 800NK and a
     * swipe from there kills it. we can't take that strip back from the dash, so we get AA out of it.
     */
    private fun editMargins() {
        val view = layoutInflater.inflate(R.layout.dialog_screen_margins, null)
        val top = view.findViewById<EditText>(R.id.et_margin_top)
        val bottom = view.findViewById<EditText>(R.id.et_margin_bottom)
        val left = view.findViewById<EditText>(R.id.et_margin_left)
        val right = view.findViewById<EditText>(R.id.et_margin_right)
        top.setText(ScreenMargins.top.toString())
        bottom.setText(ScreenMargins.bottom.toString())
        left.setText(ScreenMargins.left.toString())
        right.setText(ScreenMargins.right.toString())

        val panel = AaVideoBridge.pipeline?.bikeCanvasSize()
        view.findViewById<TextView>(R.id.tv_margin_panel).text =
            if (panel != null) "Your dash is ${panel.first}x${panel.second} px."
            else "Connect once and this will show your dash's size in pixels."

        fun read(e: EditText) = e.text.toString().trim().toIntOrNull() ?: 0
        fun apply() {
            ScreenMargins.set(this, read(top), read(bottom), read(left), read(right))
            AaVideoBridge.pipeline?.refreshDisplayMode()   // recomputes the viewport + redraws
            refreshMargins()
        }
        // live: type a number, look at the dash, adjust. no OK-then-check-then-reopen loop.
        for (e in listOf(top, bottom, left, right)) {
            e.addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) = apply()
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Screen margins")
            .setView(view)
            .setPositiveButton("Done", null)
            .setNeutralButton("Reset") { _, _ ->
                ScreenMargins.set(this, 0, 0, 0, 0)
                AaVideoBridge.pipeline?.refreshDisplayMode()
                refreshMargins()
                LogBus.log("→ screen margins reset")
            }
            .show()
    }

    private fun refreshMargins() {
        findViewById<MaterialButton>(R.id.btn_margins).text =
            if (ScreenMargins.any) "Screen margins: ${ScreenMargins.top}/${ScreenMargins.bottom}/" +
                "${ScreenMargins.left}/${ScreenMargins.right}"
            else "Screen margins"
    }

    // ─────────────────────────── updates ───────────────────────────

    /**
     * ask github whether there's a newer apk, and show its release notes if so.
     *
     * a sideloaded app has nothing keeping it current, so people ride on old builds and report bugs
     * that are already fixed. the download goes out to the browser rather than us installing it, so
     * this needs no install permission.
     */
    private fun checkUpdates(manual: Boolean) {
        if (manual) Toast.makeText(this, "Checking…", Toast.LENGTH_SHORT).show()
        Thread {
            val release = UpdateChecker.check(applicationContext, manual)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when {
                    release != null -> showUpdate(release)
                    manual -> Toast.makeText(
                        this,
                        if (AndroidAutoService.isRunning)
                            "Can't check while connected — the bike's Wi-Fi has no internet."
                        else "You're on the latest version.",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }.also { it.isDaemon = true }.start()
    }

    private fun showUpdate(release: UpdateChecker.Release) {
        val notes = release.notes.ifBlank { "No release notes." }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Update available: ${release.version}")
            .setMessage("You have ${BuildConfig.VERSION_NAME}.\n\n$notes")
            .setPositiveButton("Download") { _, _ ->
                LogBus.log("→ opening ${release.downloadUrl}")
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(release.downloadUrl)))
                } catch (e: Exception) {
                    Toast.makeText(this, "Couldn't open the browser", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Later", null)
            .setNeutralButton("Skip this one") { _, _ ->
                UpdateChecker.skip(applicationContext, release.version)
            }
            .show()
    }

    /** which slice of AA's canvas the dash shows, see DisplayAlign */
    private fun pickAlign() {
        val options = DisplayAlign.Mode.entries
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Picture alignment")
            .setSingleChoiceItems(
                options.map { it.label }.toTypedArray(), options.indexOf(DisplayAlign.mode(this))
            ) { dialog, which ->
                DisplayAlign.set(this, options[which])
                DisplayAlign.load(applicationContext)
                // apply live: the compositor recomputes its viewport, and the same viewport maps
                // touches, so both move together
                AaVideoBridge.pipeline?.refreshDisplayMode()
                LogBus.log("→ picture alignment: ${options[which].label}")
                dialog.dismiss()
                refreshAlign()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun refreshAlign() {
        findViewById<MaterialButton>(R.id.btn_align).text = "Picture alignment: ${DisplayAlign.mode(this).label}"
    }

    /** day/night on the dash. unlike picture quality this applies to the live session. */
    private fun pickNightMode() {
        val options = NightMode.Mode.entries
        val labels = options.map { it.label }.toTypedArray()
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Day / night")
            .setSingleChoiceItems(labels, options.indexOf(NightMode.mode(this))) { dialog, which ->
                NightMode.setMode(this, options[which])
                LogBus.log("→ day/night: ${options[which].label}")
                // push now, waiting up to a minute for the tick would make the setting feel broken
                dev.snaipdefix.opencflink.aa.NightModeSender.instance?.pushNow()
                dialog.dismiss()
                refreshNightMode()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun refreshNightMode() {
        val m = NightMode.mode(this)
        val now = if (NightMode.isNight(this)) "night" else "day"
        findViewById<MaterialButton>(R.id.btn_night_mode).text =
            if (m == NightMode.Mode.AUTO) "Day / night: auto ($now)" else "Day / night: ${m.label}"
    }

    /** trade picture bits against bluetooth airtime for the helmet audio, see VideoQuality */
    private fun pickVideoQuality() {
        val options = VideoQuality.entries
        val labels = options.map { "${it.label}  ·  ${it.bitrate / 1000} kbps\n${it.hint}" }.toTypedArray()
        val current = options.indexOf(VideoQuality.get(this))
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Picture quality")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                VideoQuality.set(this, options[which])
                LogBus.log("→ picture quality: ${options[which].label} (${options[which].bitrate / 1000}kbps) — applies on the next connect")
                dialog.dismiss()
                refreshVideoQuality()
                if (AndroidAutoService.isRunning) {
                    Toast.makeText(this, "Applies on the next connect", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun refreshVideoQuality() {
        val q = VideoQuality.get(this)
        findViewById<MaterialButton>(R.id.btn_video_quality).text = "Picture quality: ${q.label}"
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

        findViewById<MaterialButton>(R.id.btn_report).setOnClickListener {
            startActivity(Intent(this, ReportActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.btn_share_log).setOnClickListener { shareLog() }
        findViewById<MaterialButton>(R.id.btn_clear).setOnClickListener {
            LogBus.clear()
            logView.text = ""
        }
    }

    /**
     * mirror the log into the view, but only while we're on screen.
     *
     * everything logs through LogBus, which fires on the caller's thread. during a ride the phone is
     * pocketed with the screen off and this was still hopping to the main thread to append and
     * re-scroll for every line. attaching in onResume and dropping it in onPause means that only
     * costs anything when someone's looking. LogBus keeps buffering either way so nothing is lost.
     */
    private fun attachLogListener() {
        logView.text = LogBus.snapshot()
        logScroll.post { logScroll.scrollTo(0, logView.bottom) }
        LogBus.listener = { line ->
            runOnUiThread {
                // only follow the tail if they're already at the bottom, or scrolling back to read
                // something keeps yanking them down. check before appending.
                val follow = !logScroll.canScrollVertically(1)
                logView.append("$line\n")
                if (follow) {
                    // not fullScroll(FOCUS_DOWN): despite the name it also moves focus to the
                    // bottom-most focusable view, which stole focus from the destination field on
                    // every log line, you could type one letter before it dropped. scrollTo() just
                    // scrolls.
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

    /** nothing on screen, so no log mirroring and no status tick. the ride keeps going. */
    override fun onPause() {
        LogBus.listener = null
        uiHandler.removeCallbacks(statusTick)
        super.onPause()
    }

    // ─────────────────────────── status ───────────────────────────

    /** refresh the header, the connect button and the control tab once a second */
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

            // dead keys are worse than no keys, so show an explanation until AA is live
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
            !running && BikeCreds.load(applicationContext) == null -> "Scan the QR first."
            !running -> "Ignition on, then tap."
            running && !aa -> "Starting Android Auto…"
            running && !bike -> "Joining the bike's Wi-Fi…"
            else -> "Connected. The dash is showing Android Auto."
        }
    }

    // ─────────────────────────── plumbing ───────────────────────────

    private fun requestPermissions() {
        // android 13+: ask for notifications up front, some setups gate the foreground service on
        // being able to post its notification
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3,
            )
        }

        // the assistant needs the mic before AA asks for it: AA opens the mic channel mid session
        // and a denied RECORD_AUDIO there just fails silently
        if (!hasMic()) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 4)
        }
    }

    override fun onDestroy() {
        LogBus.listener = null
        uiHandler.removeCallbacks(statusTick)
        // when the receiver service is running the whole chain (receiver + encoder in the service,
        // wifi + prober in process globals) has to outlive this activity: launching google AA can
        // destroy and recreate MainActivity mid hand-off, and tearing the bike down here is exactly
        // what left the dash black, the pending onSteadyVideo hand-off got cancelled before it
        // fired. only tear down when AA isn't running. the real teardown is the stop button.
        if (!AndroidAutoService.isRunning) {
            AaVideoBridge.onSteadyVideo = null
            prober.stop()
            bleWakeUp?.stop()
            bleWakeUp = null
            ProjectionHolder.projection?.let { try { it.stop() } catch (_: Exception) {} }
            ProjectionHolder.projection = null
            ProjectionService.stop(this)
            // AndroidAutoService is deliberately not stopped here, it's meant to keep running when
            // the phone is locked. that's what the stop button is for.
            BikeWifi.leave(this, ::log)
        }
        super.onDestroy()
    }

    private fun joinAndStart(qr: QrData) {
        // hand the connection to BikeReconnector: it owns the wifi join and rejoins by itself when
        // the bike's ap drops and comes back. uses applicationContext + the global prober so it
        // survives this activity being recreated.
        BikeLink.prober = BikeLink.prober ?: prober
        BikeReconnector.connect(applicationContext, qr)
    }

    private fun ensureLocationPermission() {
        // some oems need fine location to associate via WifiNetworkSpecifier
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
            val file = File(dir, "opencflink-$stamp.log")
            file.writeText(LogBus.snapshot())
            val uris = ArrayList<Uri>()
            uris.add(FileProvider.getUriForFile(this, "$packageName.fileprovider", file))

            // attach any h264 dumps (VideoPipeline writes these to <externalFiles>/video)
            val videoDir = File(getExternalFilesDir(null), "video")
            val dumps = videoDir.listFiles { f -> f.name.endsWith(".h264") }?.sortedBy { it.name } ?: emptyList()
            for (d in dumps) {
                uris.add(FileProvider.getUriForFile(this, "$packageName.fileprovider", d))
                log("attaching video dump: ${d.name} (${d.length()} bytes)")
            }

            val send = Intent(if (uris.size > 1) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND).apply {
                type = if (uris.size > 1) "*/*" else "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "opencflink log $stamp")
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

    /** navigate to whatever was typed. with AA projecting it shows up on the dash. */
    private fun startNavigation() {
        val dest = findViewById<EditText>(R.id.et_destination).text.toString().trim()
        if (dest.isEmpty()) {
            Toast.makeText(this, "Type a destination first", Toast.LENGTH_SHORT).show()
            return
        }
        // same launcher the handlebar nav actions use, so both behave the same
        if (!NavLauncher.navigate(this, dest, ::log)) {
            Toast.makeText(this, "Couldn't start navigation (Google Maps?)", Toast.LENGTH_SHORT).show()
        }
    }

    /** one knob click (-1 back, +1 forward) in the live session */
    private fun sendAaScroll(delta: Int) {
        val sink = AaVideoBridge.scrollSink
        if (sink == null) {
            log("no android auto session, connect first")
            Toast.makeText(this, "Connect first", Toast.LENGTH_SHORT).show()
            return
        }
        sink(delta)
    }

    /** send a key to the live session, or say there isn't one */
    private fun sendAaKey(keycode: Int) {
        val sink = AaVideoBridge.keySink
        if (sink == null) {
            log("no android auto session, connect first")
            Toast.makeText(this, "Connect first", Toast.LENGTH_SHORT).show()
            return
        }
        sink(keycode)
    }

    private fun log(msg: String) = LogBus.log(msg)

    companion object {
        /** so auto connect fires once, not again on the recreation google AA causes */
        @Volatile private var autoConnectDone = false
        /** survives the recreation google AA causes, so the tab doesn't jump back */
        @Volatile private var currentTab = 0
    }
}
