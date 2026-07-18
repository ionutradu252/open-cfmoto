package dev.snaipdefix.opencflink

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * start google maps turn-by-turn. with AA projecting the route shows up on the dash.
 *
 * used by the "navigate to..." box and by the handlebar nav actions. the handlebar case starts an
 * activity while the app is in the background, which android blocks by default, see
 * canLaunchFromBackground.
 */
object NavLauncher {

    /**
     * can a nav action from the handlebars actually start maps?
     *
     * android blocks background activity starts and a foreground service does not exempt us, so
     * while riding with the ui off screen startActivity is swallowed silently, no exception,
     * nothing happens, nothing in the log. "display over other apps" is the one grant that lifts it,
     * so it's a real requirement for the nav buttons. the in-app box doesn't need it, we're on
     * screen.
     */
    fun canLaunchFromBackground(context: Context): Boolean = Settings.canDrawOverlays(context)

    /** true if we handed the destination to a nav app */
    fun navigate(context: Context, destination: String, log: (String) -> Unit): Boolean {
        val dest = destination.trim()
        if (dest.isEmpty()) {
            log("[NAV] no destination saved for that button — set one in Button mapping")
            return false
        }
        if (!canLaunchFromBackground(context)) {
            // log it, a silent no-op here is impossible to diagnose from a ride log
            log("[NAV] warning: \"Display over other apps\" is off — Android may block this launch")
        }
        val uri = Uri.parse("google.navigation:q=" + Uri.encode(dest))
        // prefer google maps, fall back to anything that can navigate
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

    /** system page where "display over other apps" is granted */
    fun overlayPermissionIntent(context: Context) =
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )
}
