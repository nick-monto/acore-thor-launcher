package app.gamenative.skate

import java.io.File

/**
 * Installs the dropped package into the client the same way `install-client.ps1` does on Windows:
 * the adapter beside `Wow.exe`, the add-on into `Interface/AddOns/WrathRoll`, and the accepted
 * request flags into the state folder. Every path comes from the package manifest.
 *
 * Idempotent: the marker records the package version and adapter hash, and a launch that finds both
 * unchanged copies nothing. A new package, or an adapter edited in place, reinstalls over the old one.
 */
object SkateInstall {
    private const val MARKER = ".skate-package"

    fun ensure(pkg: SkatePackage, onStatus: (String) -> Unit) {
        val assets = File(pkg.root, "skatecraft-wrath/skate-data/assets/rig.json")
        check(assets.isFile) {
            "The Skate package has no converted engine data (${assets.path}). Copy the whole WrathSkate folder."
        }
        pkg.verifyPayload()
        pkg.verifyClient()

        val adapter = pkg.file(pkg.adapterFrom)
        val adapterHash = SkatePackage.sha256(adapter)
        val installed = pkg.file(pkg.adapterTo)
        val marker = File(pkg.clientDir, MARKER)
        val stamp = "${pkg.version} $adapterHash"
        if (marker.isFile && marker.readText().trim() == stamp && installed.isFile &&
            SkatePackage.sha256(installed) == adapterHash
        ) {
            return
        }

        onStatus("Installing the Skate adapter...")
        installed.parentFile?.mkdirs()
        adapter.copyTo(installed, overwrite = true)

        onStatus("Installing the Skate add-on...")
        val addonFrom = pkg.file(pkg.addonFrom)
        val addonTo = pkg.file(pkg.addonTo)
        addonTo.mkdirs()
        val addonFiles = addonFrom.listFiles()?.filter { it.isFile }.orEmpty()
        check(addonFiles.isNotEmpty()) { "The Skate package has no add-on files in ${addonFrom.path}." }
        for (file in addonFiles) file.copyTo(File(addonTo, file.name), overwrite = true)

        onStatus("Installing the Skate request flags...")
        val requestsFrom = pkg.file(pkg.requestsFrom)
        val requestsTo = pkg.file(pkg.requestsTo)
        requestsTo.mkdirs()
        val requests = requestsFrom.listFiles()?.filter { it.isFile }.orEmpty()
        check(requests.isNotEmpty()) { "The Skate package has no accepted request flags in ${requestsFrom.path}." }
        for (file in requests) file.copyTo(File(requestsTo, file.name), overwrite = true)

        // The adapter and the helper write their logs and DXVK writes here; neither creates it.
        File(pkg.root, "native-probe/build").mkdirs()

        marker.writeText("$stamp\n")
    }
}
