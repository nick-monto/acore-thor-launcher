package app.gamenative.skate

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * The folder the player drops on the device: `package.json` plus `client/`, `native-probe/` and
 * `build/`. The launcher ships none of these bytes in the APK; it verifies them here and installs
 * them into the client with [SkateInstall].
 *
 * `SOTSK_ROOT` is the same folder inside the container, always drive `G:` (the app's game root), so
 * every path in the manifest is resolved by [guestPath] rather than by a second convention.
 */
class SkatePackage private constructor(
    val root: File,
    val version: String,
    val wire: Int,
    val clientExe: String,
    val clientSha256: String,
    val adapterFrom: String,
    val adapterTo: String,
    val addonFrom: String,
    val addonTo: String,
    val requestsFrom: String,
    val requestsTo: String,
    val env: Map<String, String>,
    val files: List<FileHash>,
) {
    data class FileHash(val path: String, val bytes: Long, val sha256: String)

    val clientDir: File get() = File(root, clientExe.substringBeforeLast('/'))

    fun file(relative: String): File = File(root, relative.replace('/', File.separatorChar))

    /** Every hashed payload file still matches the manifest. The addon and both binaries are small. */
    fun verifyPayload() {
        for (entry in files) {
            val file = file(entry.path)
            check(file.isFile) { "The Skate package is incomplete: ${entry.path} is missing from ${root.path}." }
            val actual = sha256(file)
            check(actual == entry.sha256) {
                "The Skate package is damaged: ${entry.path} hashes to $actual, not ${entry.sha256}. Copy the folder again."
            }
        }
    }

    /**
     * The adapter patches fixed addresses in build 12340, so a different `Wow.exe` silently disables
     * every hook. Refuse it here, where the message can say which file is wrong.
     */
    fun verifyClient() {
        val exe = File(clientDir, "Wow.exe")
        check(exe.isFile) { "No Wow.exe in ${clientDir.path}. Copy a 3.3.5a build 12340 client there." }
        val actual = sha256(exe)
        check(actual == clientSha256) {
            "Wow.exe is build $actual, not the audited $clientSha256. The Skate adapter only works with that build."
        }
    }

    companion object {
        const val FILE = "package.json"

        /** The adapter/helper contract this launcher understands. Bump with the protocol, not the app. */
        const val WIRE = 1
        private const val SCHEMA = 1

        private const val GUEST_ROOT = "G:\\"

        fun load(root: File): SkatePackage {
            val manifest = File(root, FILE)
            check(manifest.isFile) {
                "No $FILE in ${root.path}. Copy the WrathSkate folder there (it holds client/ and the payload)."
            }
            val json = JSONObject(manifest.readText().removePrefix("\uFEFF"))
            val schema = json.optInt("schema", 0)
            check(schema == SCHEMA) { "Unsupported Skate package schema $schema; this launcher reads $SCHEMA." }
            val wire = json.optInt("wire", 0)
            check(wire == WIRE) {
                "The Skate package speaks wire $wire, this launcher speaks $WIRE. Update the package or the app."
            }
            val client = json.getJSONObject("client")
            val install = json.getJSONObject("install")
            val adapter = install.getJSONObject("adapter")
            val addon = install.getJSONObject("addon")
            val requests = install.getJSONObject("requests")
            val env = linkedMapOf<String, String>()
            json.optJSONObject("env")?.let { declared ->
                for (key in declared.keys()) env[key] = guestPath(declared.getString(key))
            }
            val files = mutableListOf<FileHash>()
            (json.optJSONArray("files") ?: JSONArray()).let { array ->
                for (index in 0 until array.length()) {
                    val entry = array.getJSONObject(index)
                    files += FileHash(entry.getString("path"), entry.optLong("bytes", 0L), entry.getString("sha256"))
                }
            }
            return SkatePackage(
                root = root,
                version = json.optString("version", "0"),
                wire = wire,
                clientExe = client.getString("exe"),
                clientSha256 = client.getString("sha256"),
                adapterFrom = adapter.getString("from"),
                adapterTo = adapter.getString("to"),
                addonFrom = addon.getString("from"),
                addonTo = addon.getString("to"),
                requestsFrom = requests.getString("from"),
                requestsTo = requests.getString("to"),
                env = env,
                files = files,
            )
        }

        /** `.` is the drive root; `client/Data` is `G:\client\Data`. */
        private fun guestPath(relative: String): String {
            val trimmed = relative.trim().trimStart('.', '/', '\\')
            return if (trimmed.isEmpty()) GUEST_ROOT else GUEST_ROOT + trimmed.replace('/', '\\')
        }

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { stream ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        }
    }
}
