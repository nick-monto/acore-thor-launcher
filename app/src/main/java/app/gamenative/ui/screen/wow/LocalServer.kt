package app.gamenative.ui.screen.wow

import android.content.Context
import android.content.Intent
import app.gamenative.PrefManager
import java.net.InetSocketAddress
import java.net.Socket
import timber.log.Timber

/**
 * Start, stop and configure the self-hosted AzerothCore realm, which runs in Termux on this device.
 *
 * One Android app cannot run or signal another app's process, so control goes through Termux's
 * documented RUN_COMMAND service and one script: the installer leaves `wow-server.sh` in Termux's
 * home next to `start-wow-server.sh`/`stop-wow-server.sh`, and that script reads the install's own
 * `state.env` to find the rootfs and config directory. Termux only accepts the command with
 * `allow-external-apps = true` in `~/.termux/termux.properties`, which is why the screen says so
 * when a command produces no effect.
 *
 * Status is never taken from the intent. It is a probe of the logon and world ports, which is the
 * only answer that stays true when the realm was started from a Termux session instead, is still
 * importing SQL, or has died.
 */
object LocalServer {
    const val ADDRESS = "127.0.0.1"
    const val AUTH_PORT = 3724
    const val WORLD_PORT = 8085

    /** What the installer configures when no bot count is given. */
    const val DEFAULT_BOTS = 50

    /** The guest script refuses more than this, so the dialog does not offer more either. */
    const val MAX_BOTS = 2000

    private const val TERMUX_PACKAGE = "com.termux"
    private const val TERMUX_SERVICE = "com.termux.app.RunCommandService"
    private const val TERMUX_HOME = "/data/data/com.termux/files/home"
    private const val TERMUX_BASH = "/data/data/com.termux/files/usr/bin/bash"

    private const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
    private const val EXTRA_PATH = "com.termux.RUN_COMMAND_PATH"
    private const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
    private const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
    private const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
    private const val EXTRA_SESSION_ACTION = "com.termux.RUN_COMMAND_SESSION_ACTION"

    private const val CONTROL_SCRIPT = "$TERMUX_HOME/wow-server.sh"
    private const val START_SCRIPT = "$TERMUX_HOME/start-wow-server.sh"
    private const val STOP_SCRIPT = "$TERMUX_HOME/stop-wow-server.sh"

    /** Remembered so the panel can show the count without asking the rootfs for it. */
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

    /** True when Termux accepted the command. Whether it worked is what the port probe answers. */
    fun start(context: Context): Boolean = run(context, START_SCRIPT, background = true)

    fun stop(context: Context): Boolean = run(context, STOP_SCRIPT, background = false)

    /**
     * Set the random bot count. The world reads it at its next start, so this does not restart
     * anything: the panel's Start/Restart button is how a change reaches a running realm.
     */
    fun setBots(context: Context, count: Int): Boolean =
        run(context, CONTROL_SCRIPT, extra = "bots ${count.coerceIn(0, MAX_BOTS)}", background = true)

    fun loadBots(context: Context): Int = PrefManager.getFloat(PREF_BOTS, DEFAULT_BOTS.toFloat()).toInt()

    fun saveBots(context: Context, count: Int) = PrefManager.setFloat(PREF_BOTS, count.toFloat())

    fun probe(): Status = Status(auth = portOpen(AUTH_PORT), world = portOpen(WORLD_PORT))

    private fun portOpen(port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(ADDRESS, port), 400) }
        true
    } catch (e: Exception) {
        false
    }

    private fun run(context: Context, script: String, extra: String = "", background: Boolean): Boolean {
        val command = if (extra.isEmpty()) script else "$script $extra"
        val intent = Intent(ACTION_RUN_COMMAND).apply {
            setClassName(TERMUX_PACKAGE, TERMUX_SERVICE)
            putExtra(EXTRA_PATH, TERMUX_BASH)
            putExtra(EXTRA_ARGUMENTS, "-l -c \"$command\"")
            putExtra(EXTRA_WORKDIR, TERMUX_HOME)
            putExtra(EXTRA_BACKGROUND, background)
            // 0: do not switch Termux to the new session. The server runs out of sight; a stop or a
            // bot count is a short command whose output goes to the Termux notification.
            putExtra(EXTRA_SESSION_ACTION, 0)
        }
        return try {
            context.startService(intent)
            true
        } catch (e: Exception) {
            Timber.w(e, "Termux RUN_COMMAND failed for %s", command)
            false
        }
    }
}
