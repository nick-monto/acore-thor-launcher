package app.gamenative.ui.screen.wow

import android.content.Context
import android.net.Uri
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

internal const val BUNDLED_TURNIP_740 = "Turnip-WoW-scheduler-test"
internal const val BUNDLED_TURNIP_8XX = "Turnip-V32-RP6sched"

private const val PREFS = "wow_graphics_driver"
private const val SELECTED_DRIVER = "selected_driver"
private const val CUSTOM_PREFIX = "custom-"
private const val MAX_DRIVER_ENTRIES = 128
private const val MAX_DRIVER_BYTES = 128L * 1024L * 1024L

internal val BUNDLED_DRIVER_IDS = setOf(BUNDLED_TURNIP_740, BUNDLED_TURNIP_8XX)

internal data class InstalledGraphicsDriver(
    val id: String,
    val name: String,
    val version: String,
    val minApi: Int,
)

/**
 * Installed Adrenotools drivers plus the user's selection.
 *
 * The bundled drivers are always present under their fixed ids. A user-imported driver gets a
 * `custom-` id, so importing a package whose `meta.json` reuses a bundled name can never overwrite
 * the known-good driver.
 */
internal object GraphicsDriverStore {
    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun driverRoot(context: Context): File =
        File(context.filesDir, "contents/adrenotools")

    /** The raw selection: null means "auto", an id may point at a driver that was since removed. */
    fun selected(context: Context): String? = prefs(context).getString(SELECTED_DRIVER, null)

    fun select(context: Context, id: String?) {
        prefs(context).edit().apply {
            if (id == null) remove(SELECTED_DRIVER) else putString(SELECTED_DRIVER, id)
        }.apply()
    }

    /**
     * The id to put in the container. If the remembered custom driver is gone or incompatible,
     * fall back to the detected profile and forget the stale selection.
     */
    fun resolve(context: Context, autoDriver: String): String {
        val id = selected(context) ?: return autoDriver
        if (installed(context, id) != null) return id
        select(context, null)
        return autoDriver
    }

    fun installed(context: Context, id: String): InstalledGraphicsDriver? {
        if (id.isBlank() || id == "." || id == ".." || id.contains('/') || id.contains('\\')) {
            return null
        }
        val dir = File(driverRoot(context), id)
        val meta = File(dir, "meta.json")
        if (!meta.isFile) return null

        val json = runCatching { JSONObject(meta.readText()) }.getOrNull() ?: return null
        val library = json.optString("libraryName").trim()
        if (library.isEmpty() || library.contains('/') || library.contains('\\')) return null
        if (!File(dir, library).isFile) return null

        val minApi = json.optInt("minApi", 0)
        if (minApi > Build.VERSION.SDK_INT) return null

        return InstalledGraphicsDriver(
            id = id,
            name = json.optString("name").trim().ifBlank { id },
            version = json.optString("driverVersion").trim(),
            minApi = minApi,
        )
    }

    fun customDrivers(context: Context): List<InstalledGraphicsDriver> =
        driverRoot(context).listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isDirectory }
            .map { it.name }
            .filter {
                it !in BUNDLED_DRIVER_IDS &&
                    it != "tmp" &&
                    it != "System" &&
                    !it.startsWith(".")
            }
            .mapNotNull { installed(context, it) }
            .sortedBy { it.name.lowercase() }
            .toList()

    /**
     * Imports an Adrenotools or `.adpkg.zip` driver package. Both are zip archives with `meta.json`
     * and the library named by `libraryName` at the root; that is the layout the bundled drivers
     * and [com.winlator.contents.AdrenotoolsManager] expect.
     */
    fun import(context: Context, uri: Uri): InstalledGraphicsDriver {
        val root = driverRoot(context).apply { mkdirs() }
        val temp = File(root, ".import-${System.nanoTime()}").apply { mkdirs() }
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                extractZip(input, temp)
            } ?: error("Could not read the selected file.")

            val metaFile = File(temp, "meta.json")
            require(metaFile.isFile) {
                "The archive has no meta.json at its root. Driver zips (including .adpkg.zip) are flat, with meta.json and the .so inside."
            }
            val json = JSONObject(metaFile.readText())
            val name = json.optString("name").trim()
            require(name.isNotEmpty()) { "meta.json has no name." }
            val library = json.optString("libraryName").trim()
            require(library.isNotEmpty()) { "meta.json has no libraryName." }
            require(!library.contains('/') && !library.contains('\\')) {
                "meta.json's libraryName must be a file name, not a path."
            }
            require(File(temp, library).isFile) { "The archive is missing $library." }

            val minApi = json.optInt("minApi", 0)
            require(minApi <= Build.VERSION.SDK_INT) {
                "The driver needs Android API $minApi; this device is API ${Build.VERSION.SDK_INT}."
            }

            val destination = File(root, customDriverId(name))
            val backup = File(root, ".backup-${System.nanoTime()}")
            val replacing = destination.exists()
            require(!replacing || destination.renameTo(backup)) {
                "Could not replace the existing ${json.optString("name")} driver."
            }
            if (!temp.renameTo(destination)) {
                if (replacing) backup.renameTo(destination)
                error("Could not move the imported driver into place.")
            }
            backup.deleteRecursively()

            return installed(context, destination.name)
                ?: error("The imported driver has no usable meta.json or library.")
        } catch (e: Exception) {
            temp.deleteRecursively()
            throw e
        }
    }

    fun remove(context: Context, id: String) {
        if (id in BUNDLED_DRIVER_IDS) return
        if (id.isBlank() || id == "." || id == ".." || id.contains('/') || id.contains('\\')) return
        val dir = File(driverRoot(context), id)
        if (dir.exists() && !dir.deleteRecursively()) {
            error("Could not remove the driver from ${dir.absolutePath}.")
        }
        if (selected(context) == id) select(context, null)
    }

    private fun customDriverId(name: String): String {
        val slug = name.lowercase()
            .replace(Regex("[^a-z0-9._-]+"), "-")
            .trim('-', '.', '_')
            .take(48)
            .ifBlank { "driver" }
        return CUSTOM_PREFIX + slug
    }

    private fun extractZip(input: InputStream, destination: File) {
        val destinationPath = destination.canonicalPath + File.separator
        var entries = 0
        var totalBytes = 0L
        val buffer = ByteArray(64 * 1024)

        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries++
                require(entries <= MAX_DRIVER_ENTRIES) { "The archive has too many files." }

                val name = entry.name
                require(
                    name.isNotBlank() &&
                        !name.startsWith('/') &&
                        !name.contains('\\') &&
                        name.split('/').none { it == ".." },
                ) { "The archive contains an unsafe path." }

                val target = File(destination, name).canonicalFile
                require(target.path == destination.canonicalPath || target.path.startsWith(destinationPath)) {
                    "The archive contains a path outside the driver directory."
                }

                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { sink ->
                        while (true) {
                            val read = zip.read(buffer)
                            if (read <= 0) break
                            totalBytes += read
                            require(totalBytes <= MAX_DRIVER_BYTES) { "The driver archive is too large." }
                            sink.write(buffer, 0, read)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
    }
}
