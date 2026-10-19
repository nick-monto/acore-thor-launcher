package app.gamenative.ui.screen.wow

import android.content.Context
import app.gamenative.PrefManager
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

    /** What the installer configures when no bot count is given: the realm's own default. */
    const val DEFAULT_BOTS = 50

    /** The guest script refuses more than this, so the panel does not record more either. */
    const val MAX_BOTS = 2000

    /**
     * The realm's configured bot count, as recorded by whoever ran the installer. The rootfs belongs
     * to Termux, so there is nothing on this device to read it back from: this is the launcher's own
     * note of it, and the number the panel states.
     */
    private const val PREF_BOTS = "acore_local_server_bots"

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

    fun loadBots(context: Context): Int = PrefManager.getFloat(PREF_BOTS, DEFAULT_BOTS.toFloat()).toInt()

    fun saveBots(context: Context, count: Int) =
        PrefManager.setFloat(PREF_BOTS, count.coerceIn(0, MAX_BOTS).toFloat())

    fun probe(): Status = Status(auth = portOpen(AUTH_PORT), world = portOpen(WORLD_PORT))

    private fun portOpen(port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(ADDRESS, port), 400) }
        true
    } catch (e: Exception) {
        false
    }
}
