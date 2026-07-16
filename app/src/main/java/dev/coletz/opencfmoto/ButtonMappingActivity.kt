package dev.coletz.opencfmoto

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Remap what each handlebar gesture does, and keep the destinations a button can navigate to.
 *
 * Rows are built from [ButtonGesture]'s entries rather than written out in XML, so adding a gesture
 * (say, if another dash turns out to send something new) needs no layout change. Everything is read
 * live by [MediaButtonBridge] on each press — no reconnect needed to try a change.
 */
class ButtonMappingActivity : AppCompatActivity() {

    private val placeNames = mutableListOf<EditText>()
    private val placeQueries = mutableListOf<EditText>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_button_mapping)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.mapping_root)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        buildGestureRows()
        buildPlaceRows()

        findViewById<MaterialButton>(R.id.btn_overlay).setOnClickListener {
            try {
                startActivity(NavLauncher.overlayPermissionIntent(this))
            } catch (e: Exception) {
                LogBus.log("couldn't open the overlay permission screen ($e)")
                Toast.makeText(this, "Open Settings → Apps → Open CfMoto → Display over other apps",
                    Toast.LENGTH_LONG).show()
            }
        }

        findViewById<MaterialButton>(R.id.btn_reset).setOnClickListener { confirmReset() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    /** Saved places are free text, so commit them whenever the screen goes away. */
    override fun onPause() {
        savePlaces()
        super.onPause()
    }

    // ─────────────────────────── gestures ───────────────────────────

    private fun buildGestureRows() {
        val container = findViewById<LinearLayout>(R.id.mapping_container)
        container.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for (gesture in ButtonGesture.entries) {
            val row = inflater.inflate(R.layout.row_button_mapping, container, false)
            row.tag = gesture
            row.findViewById<TextView>(R.id.tv_gesture).text = gesture.label
            row.findViewById<TextView>(R.id.tv_hint).text = gesture.hint
            row.setOnClickListener { pickAction(gesture) }
            container.addView(row)
        }
    }

    private fun pickAction(gesture: ButtonGesture) {
        val actions = ButtonAction.entries
        val labels = actions.map { it.displayLabel(this) }.toTypedArray()
        val current = actions.indexOf(ButtonMap.get(this, gesture))
        MaterialAlertDialogBuilder(this)
            .setTitle(gesture.label)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                val action = actions[which]
                ButtonMap.set(this, gesture, action)
                LogBus.log("→ button mapping: ${gesture.label} = ${action.label}")
                if (action.isNav && !SavedPlaces.isSet(this, action.navSlot)) {
                    Toast.makeText(this, "Set that place's address below", Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ─────────────────────────── saved places ───────────────────────────

    private fun buildPlaceRows() {
        val container = findViewById<LinearLayout>(R.id.places_container)
        container.removeAllViews()
        placeNames.clear()
        placeQueries.clear()
        val inflater = LayoutInflater.from(this)
        for (slot in 0 until SavedPlaces.COUNT) {
            val row = inflater.inflate(R.layout.row_saved_place, container, false)
            val name = row.findViewById<EditText>(R.id.et_place_name)
            val query = row.findViewById<EditText>(R.id.et_place_query)
            name.setText(SavedPlaces.name(this, slot))
            query.setText(SavedPlaces.query(this, slot))
            placeNames += name
            placeQueries += query
            container.addView(row)
        }
    }

    private fun savePlaces() {
        for (slot in 0 until SavedPlaces.COUNT) {
            SavedPlaces.set(
                this, slot,
                placeNames.getOrNull(slot)?.text?.toString() ?: "",
                placeQueries.getOrNull(slot)?.text?.toString() ?: "",
            )
        }
    }

    // ─────────────────────────── state ───────────────────────────

    /** Re-read the store into every row — cheap, and keeps place renames visible in the mappings. */
    private fun refresh() {
        savePlaces()   // so a just-typed name shows up in the action labels below
        val container = findViewById<LinearLayout>(R.id.mapping_container)
        var navMapped = false
        for (i in 0 until container.childCount) {
            val row = container.getChildAt(i)
            val gesture = row.tag as? ButtonGesture ?: continue
            val action = ButtonMap.get(this, gesture)
            row.findViewById<TextView>(R.id.tv_action).text = action.displayLabel(this)
            if (action.isNav) navMapped = true
        }

        // The overlay grant only matters once a button can actually launch Maps.
        findViewById<MaterialCardView>(R.id.card_overlay).visibility =
            if (navMapped && !NavLauncher.canLaunchFromBackground(this)) View.VISIBLE else View.GONE

        findViewById<MaterialButton>(R.id.btn_reset).isEnabled = !ButtonMap.isAllDefault(this)
    }

    private fun confirmReset() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Reset to defaults?")
            .setMessage(
                "Every gesture goes back to how it shipped: ▲/▼ press = knob, ▲▲ = home, " +
                    "▼▼ = back, enter = select.\n\nSaved places are kept."
            )
            .setPositiveButton("Reset") { _, _ ->
                ButtonMap.resetAll(this)
                LogBus.log("→ button mapping reset to defaults")
                refresh()
                Toast.makeText(this, "Reset to defaults", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}

/** True for the navigate-to-a-saved-place actions. */
private val ButtonAction.isNav: Boolean
    get() = this == ButtonAction.NAV_1 || this == ButtonAction.NAV_2 || this == ButtonAction.NAV_3

/** Which [SavedPlaces] slot a nav action points at. */
private val ButtonAction.navSlot: Int
    get() = when (this) {
        ButtonAction.NAV_1 -> 0
        ButtonAction.NAV_2 -> 1
        else -> 2
    }
