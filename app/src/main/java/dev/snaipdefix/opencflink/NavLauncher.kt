package dev.snaipdefix.opencflink

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * Start Google Maps turn-by-turn to a destination. With Android Auto projecting, the route appears
 * on the dash — no interaction with the (touchless) dash needed.
 *
 * Shared by the "Navigate to…" box and by the handlebar [ButtonAction.NAV_1]/`2`/`3` actions. The
 * handlebar case starts an Activity while the app is in the background, which Android blocks by
 * default since 10 — see [canLaunchFromBackground].
 */
object NavLauncher {

    /**
     * Whether a nav action fired from the handlebars can actually start Maps.
     *
     * Android blocks background activity starts, and a foreground service does NOT exempt us — so
     * while you're riding, with our UI off screen, `startActivity` would be swallowed silently
     * (no exception, nothing happens, hence nothing to see in a log). "Display over other apps"
     * (SYSTEM_ALERT_WINDOW) is the one grant that lifts it, so it's a real requirement for the nav
     * buttons rather than a nicety. The in-app "Navigate to…" box doesn't need it: we're on screen.
     */
    fun canLaunchFromBackground(context: Context): Boolean = Settings.canDrawOverlays(context)

    /** @return true if we handed the destination to a nav app. */
    fun navigate(context: Context, destination: String, log: (String) -> Unit): Boolean {
        val dest = destination.trim()
        if (dest.isEmpty()) {
            log("[NAV] no destination saved for that button — set one in Button mapping")
            return false
        }
        if (!canLaunchFromBackground(context)) {
            // Log it rather than failing quietly: a silent no-op here is otherwise impossible to
            // diagnose from a ride log.
            log("[NAV] warning: \"Display over other apps\" is off — Android may block this launch")
        }
        val uri = Uri.parse("google.navigation:q=" + Uri.encode(dest))
        // Prefer Google Maps explicitly; fall back to any nav-capable app.
        val intents = listOf(
            Intent(Intent.ACTION_VIEW, uri).setPackage("com.google.android.apps.maps"),
            Intent(Intent.ACTION_VIEW, uri),
        )
        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                log("[NAV] → navigating to \"$dest\" — should appear on the dash")
                return true
            } catch (_: Exception) { /* try next */ }
        }
        log("[NAV] navigation failed — is Google Maps installed?")
        return false
    }

    /** Send the user to the system page where "Display over other apps" can be granted. */
    fun overlayPermissionIntent(context: Context) =
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )
}
