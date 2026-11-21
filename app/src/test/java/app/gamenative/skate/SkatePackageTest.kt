package app.gamenative.skate

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The package contract the dropped folder has to satisfy: paths resolve for Wine, hashes gate a
 * damaged copy or a different Wow.exe, and the install lands exactly where the adapter expects it.
 */
class SkatePackageTest {
    private fun tempRoot(): File = Files.createTempDirectory("skate-package").toFile()

    private fun writePackage(
        root: File,
        wire: Int = SkatePackage.WIRE,
        clientBytes: String = "wow-12340",
        adapterBytes: String = "adapter",
    ): SkatePackage {
        val clientDir = File(root, "client")
        clientDir.mkdirs()
        File(clientDir, "Data/enUS").mkdirs()
        File(clientDir, "Data/common.MPQ").writeText("mpq")
        val exe = File(clientDir, "Wow.exe")
        exe.writeText(clientBytes)

        val adapter = File(root, "native-probe/build/dinput8.dll")
        adapter.parentFile!!.mkdirs()
        adapter.writeText(adapterBytes)
        val helper = File(root, "build/debug/wrath-skate-bridge.exe")
        helper.parentFile!!.mkdirs()
        helper.writeText("helper")

        val addon = File(root, "native-probe/addon")
        addon.mkdirs()
        File(addon, "WrathRoll.toc").writeText("## Interface: 30300\nWrathRoll.lua\n")
        File(addon, "WrathRoll.lua").writeText("-- addon")
        val requests = File(root, "native-probe/requests/accepted")
        requests.mkdirs()
        File(requests, "character-pose.request").writeText("")

        val assets = File(root, "skatecraft-wrath/skate-data/assets")
        assets.mkdirs()
        File(assets, "rig.json").writeText("{}")

        val files = JSONArray()
        for (relative in listOf(
            "native-probe/build/dinput8.dll",
            "build/debug/wrath-skate-bridge.exe",
            "native-probe/addon/WrathRoll.toc",
            "native-probe/addon/WrathRoll.lua",
        )) {
            val file = File(root, relative)
            files.put(
                JSONObject()
                    .put("path", relative)
                    .put("bytes", file.length())
                    .put("sha256", SkatePackage.sha256(file)),
            )
        }
        val manifest = JSONObject()
            .put("schema", 1)
            .put("version", "1.2.3")
            .put("wire", wire)
            .put("client", JSONObject().put("exe", "client/Wow.exe").put("sha256", SkatePackage.sha256(exe)))
            .put(
                "env",
                JSONObject().put("SOTSK_ROOT", ".").put("SOTSK_DATA", "client/Data"),
            )
            .put(
                "install",
                JSONObject()
                    .put(
                        "adapter",
                        JSONObject().put("from", "native-probe/build/dinput8.dll").put("to", "client/dinput8.dll"),
                    )
                    .put(
                        "addon",
                        JSONObject().put("from", "native-probe/addon").put("to", "client/Interface/AddOns/WrathRoll"),
                    )
                    .put(
                        "requests",
                        JSONObject().put("from", "native-probe/requests/accepted").put("to", "native-probe"),
                    ),
            )
            .put("files", files)
        File(root, SkatePackage.FILE).writeText(manifest.toString())
        return SkatePackage.load(root)
    }

    @Test
    fun `manifest paths resolve to the drive the app mounts`() {
        val pkg = writePackage(tempRoot())

        assertEquals("G:\\", pkg.env["SOTSK_ROOT"])
        assertEquals("G:\\client\\Data", pkg.env["SOTSK_DATA"])
        assertEquals(File(pkg.root, "client"), pkg.clientDir)
    }

    @Test
    fun `a different wire is refused`() {
        val error = runCatching { writePackage(tempRoot(), wire = SkatePackage.WIRE + 1) }.exceptionOrNull()

        assertTrue(error?.message.orEmpty().contains("wire"))
    }

    @Test
    fun `a damaged payload names the file`() {
        val pkg = writePackage(tempRoot())
        pkg.file("native-probe/addon/WrathRoll.lua").writeText("-- edited")

        val error = runCatching { pkg.verifyPayload() }.exceptionOrNull()

        assertTrue(error?.message.orEmpty().contains("WrathRoll.lua"))
    }

    @Test
    fun `a different client build is refused`() {
        val pkg = writePackage(tempRoot())
        File(pkg.clientDir, "Wow.exe").writeText("wow-other-build")

        val error = runCatching { pkg.verifyClient() }.exceptionOrNull()

        assertTrue(error?.message.orEmpty().contains("Wow.exe"))
    }

    @Test
    fun `install copies adapter, addon and request flags once`() {
        val pkg = writePackage(tempRoot())

        SkateInstall.ensure(pkg, onStatus = {})

        assertEquals("adapter", File(pkg.clientDir, "dinput8.dll").readText())
        assertTrue(File(pkg.clientDir, "Interface/AddOns/WrathRoll/WrathRoll.lua").isFile)
        assertTrue(File(pkg.root, "native-probe/character-pose.request").isFile)
        assertTrue(File(pkg.root, "native-probe/build").isDirectory)
        assertTrue(File(pkg.clientDir, ".skate-package").isFile)

        // A second launch with the same package leaves the marker and the files alone.
        val marker = File(pkg.clientDir, ".skate-package")
        val stamped = marker.readText()
        marker.setLastModified(1_000_000)
        SkateInstall.ensure(pkg, onStatus = {})
        assertEquals(stamped, marker.readText())
        assertEquals(1_000_000, marker.lastModified())
    }

    @Test
    fun `install refreshes an adapter edited in place`() {
        val pkg = writePackage(tempRoot())
        SkateInstall.ensure(pkg, onStatus = {})
        File(pkg.clientDir, "dinput8.dll").writeText("stale")

        SkateInstall.ensure(pkg, onStatus = {})

        assertEquals("adapter", File(pkg.clientDir, "dinput8.dll").readText())
    }

    @Test
    fun `a package without engine data is refused before any copy`() {
        val pkg = writePackage(tempRoot())
        File(pkg.root, "skatecraft-wrath/skate-data/assets/rig.json").delete()

        val error = runCatching { SkateInstall.ensure(pkg, onStatus = {}) }.exceptionOrNull()

        assertTrue(error?.message.orEmpty().contains("rig.json"))
        assertFalse(File(pkg.clientDir, "dinput8.dll").exists())
    }
}
