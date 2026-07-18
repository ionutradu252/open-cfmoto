package dev.snaipdefix.opencflink

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * turns "it did something weird" into a report someone can act on.
 *
 * the value is in what's filled in automatically. a rider writes "the screen went black"; what you
 * need to diagnose it is the profile, the panel the dash asked for, the wifi band and the app
 * version, none of which they know. so ask the two things only they know, which bike, what
 * happened, and work out the rest.
 *
 * no server, no upload. a github token can't ship in an apk (anyone can pull it out), so real auto
 * submit means hosting a proxy, paying for it and looking after other people's bike data. a share
 * intent gets the same report to the same place. see LogRedactor for why the attachment is safe.
 */
class ReportActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("report", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_report)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.report_root)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        // remember the bike between reports, nobody should type "450SR" twice
        findViewById<EditText>(R.id.et_model).setText(prefs.getString("model", "") ?: "")
        findViewById<EditText>(R.id.et_year).setText(prefs.getString("year", "") ?: "")

        findViewById<TextView>(R.id.tv_diagnostics).text = diagnostics()
        val lines = LogBus.snapshot().count { it == '\n' }
        findViewById<TextView>(R.id.tv_attach_note).text = "Log attached: $lines lines."

        findViewById<MaterialButton>(R.id.btn_send_report).setOnClickListener { send() }
    }

    /** everything the rider can't be expected to know */
    private fun diagnostics(): String {
        val p = BikeProfileHolder.active
        val spec = p.aaVideo
        val saved = BikeCreds.load(applicationContext)
        val band = AppStatus.wifiMhz.let {
            when {
                it == 0 -> "unknown"
                it in 2400..2500 -> "${it}MHz (2.4GHz — shared with Bluetooth)"
                else -> "${it}MHz (${it / 1000}GHz)"
            }
        }
        return buildString {
            appendLine("App:        ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Phone:      ${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("Profile:    ${p.name}")
            appendLine("Model id:   ${saved?.modelId ?: "—"}")
            appendLine("AA video:   ${spec.width}x${spec.height} @${spec.dpi}dpi")
            appendLine("Panel:      ${p.panelSize?.let { "${it.first}x${it.second}" } ?: "—"}" +
                (AppStatus.panelRequested?.let { "  (dash asked for $it)" } ?: ""))
            appendLine("Wi-Fi:      $band")
            appendLine("Display:    ${if (DisplayMode.effective()) "fill" else "letterbox"}")
            appendLine("Picture:    ${VideoQuality.get(this@ReportActivity).label} (${VideoQuality.get(this@ReportActivity).bitrate / 1000}kbps)")
            appendLine("Day/night:  ${NightMode.mode(this@ReportActivity).label} → ${if (NightMode.isNight(this@ReportActivity)) "night" else "day"}")
            appendLine("Bike btns:  ${if (ButtonMode.isControlAa(this@ReportActivity)) "drive Android Auto" else "control media"}")
            append("Last seen:  AA ${if (AppStatus.aaConnected) "connected, ${AppStatus.aaFps}fps" else "not connected"}" +
                ", bike ${if (BikeLink.prober?.bikeConnected == true) "connected" else "not connected"}")
        }
    }

    private fun send() {
        val problem = findViewById<EditText>(R.id.et_problem).text.toString().trim()
        val model = findViewById<EditText>(R.id.et_model).text.toString().trim()
        val year = findViewById<EditText>(R.id.et_year).text.toString().trim()

        if (problem.isEmpty()) {
            Toast.makeText(this, "Say what went wrong first", Toast.LENGTH_SHORT).show()
            findViewById<EditText>(R.id.et_problem).requestFocus()
            return
        }
        if (model.isEmpty()) {
            Toast.makeText(this, "Which bike is it?", Toast.LENGTH_SHORT).show()
            findViewById<EditText>(R.id.et_model).requestFocus()
            return
        }
        prefs.edit().putString("model", model).putString("year", year).apply()

        val diag = diagnostics()
        try {
            val dir = File(cacheDir, "logs").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val file = File(dir, "opencflink-report-$stamp.txt")
            // answers go in the file, not just the share body (see ProblemReport). LogBus redacts
            // on write so the snapshot is already safe to attach.
            file.writeText(ProblemReport.file(problem, model, year, diag, LogBus.snapshot()))
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)

            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, ProblemReport.subject(model, BuildConfig.VERSION_NAME))
                putExtra(Intent.EXTRA_TEXT, ProblemReport.body(problem, model, year, diag))
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Send report"))
            LogBus.log("→ report prepared for $model ($stamp, ${file.length()} bytes)")
        } catch (e: Exception) {
            LogBus.log("report failed: $e")
            Toast.makeText(this, "Couldn't build the report ($e)", Toast.LENGTH_LONG).show()
        }
    }
}
