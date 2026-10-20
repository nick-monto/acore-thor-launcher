package app.gamenative.ui.screen.wow

import android.content.Context
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Read-only view of the self-hosted AzerothCore realm, which runs in Termux on this device.
 *
 * Nothing here starts or stops anything. One Android app cannot run or signal another app's process,
 * and the one channel that would have allowed it - Termux's RUN_COMMAND service - needs Termux's
 * `allow-external-apps` property, which is a deliberate security switch: with it off, no app at all
 * may run commands in Termux. That switch stays off, so the realm is started in Termux, by hand or
 * from a Termux:Boot script, and this object only reports what is reachable.
 *
 * Status is a probe of the logon and world ports, which is the only answer that stays true when the
 * realm was started outside the launcher, is still importing SQL, or has died.
 */
object LocalServer {
    const val ADDRESS = "127.0.0.1"
    const val AUTH_PORT = 3724
    const val WORLD_PORT = 8085

    private const val TERMUX_PACKAGE = "com.termux"

    /** The logon server listens as soon as its database is up; the world follows the SQL import. */
    data class Status(val auth: Boolean = false, val world: Boolean = false) {
        val running get() = auth

        val label get() = when {
            auth && world -> "running"
            auth -> "logon up, world still starting"
            else -> "stopped"
        }
    }

    fun termuxInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
        true
    } catch (e: Exception) {
        false
    }

    fun probe(): Status = Status(auth = portOpen(AUTH_PORT), world = portOpen(WORLD_PORT))

    private fun portOpen(port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(ADDRESS, port), 400) }
        true
    } catch (e: Exception) {
        false
    }
}
