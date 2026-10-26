package app.gamenative.ui.screen.wow

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The controller add-on is what the local flavour's pad talks to, and it is the one part of the launch
 * path that cannot be eyeballed from the source: it comes out of a pinned archive in the APK and lands
 * in the player's client folder on the device. These pin the two things that would break it silently -
 * an archive that is not in the build, or a marker that does not stop a second launch from extracting
 * ten megabytes of textures again.
 */
@RunWith(RobolectricTestRunner::class)
class LocalAddonsInstallTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `the add-on lands in the client's Interface AddOns and is marked by version`() {
        val root = context.cacheDir.resolve("client-root").apply { mkdirs() }

        installLocalAddons(context, root, {})

        // Every sub-addon of the release, at the path a 3.3.5a client scans.
        listOf(
            "ConsolePort",
            "ConsolePortAdvanced",
            "ConsolePortBar",
            "ConsolePortHelp",
            "ConsolePortKeyboard",
            "ConsolePortLoader",
            "ConsolePortUI_Loot",
            "ConsolePortUI_Menu",
        ).forEach { name ->
            assertTrue(
                "$name was not installed",
                root.resolve("Interface/AddOns/$name/$name.toc").isFile,
            )
        }
        // The interact add-on rides beside it, as this project's own files.
        assertTrue(
            "EInteract was not installed",
            root.resolve("Interface/AddOns/EInteract/EInteract.toc").isFile,
        )
        assertTrue(
            "EInteract's code was not installed",
            root.resolve("Interface/AddOns/EInteract/EInteract.lua").isFile,
        )
        // The survivor add-on rides with it: its own folder, its own saved variables.
        assertTrue(
            "Survivor was not installed",
            root.resolve("Interface/AddOns/Survivor/Survivor.toc").isFile,
        )
        assertTrue(
            "Survivor's code was not installed",
            root.resolve("Interface/AddOns/Survivor/Survivor.lua").isFile,
        )
        // One version: the pinned, unpatched archive, plus the marker of the add-ons added above.
        assertEquals("1.5.0-rc2+ei1+sv1", root.resolve(".consoleport-version").readText().trim())
    }

    @Test
    fun `a client root left by an older launch loses ThorPad and keeps the interact add-on`() {
        val root = context.cacheDir.resolve("client-root-stale").apply { mkdirs() }
        // What an earlier launch left: its own two add-ons, their saved variables and its marker.
        root.resolve("Interface/AddOns/ThorPad").mkdirs()
        root.resolve("Interface/AddOns/ThorPad/ThorPad.toc").writeText("## Interface: 30300\n")
        root.resolve("Interface/AddOns/EInteract").mkdirs()
        root.resolve("Interface/AddOns/EInteract/EInteract.toc").writeText("## Interface: 30300\n")
        root.resolve("WTF/Account/PLAYER/SavedVariables").mkdirs()
        root.resolve("WTF/Account/PLAYER/SavedVariables/ThorPad.lua").writeText("ThorPadDB = {}\n")
        root.resolve("WTF/Account/PLAYER/SavedVariables/EInteract.lua").writeText("EInteractDB = {}\n")
        root.resolve(".consoleport-version").writeText("1.5.0-rc2-thor2 2 1\n")

        installLocalAddons(context, root, {})

        assertFalse(
            "ThorPad is gone from the build but still in the client",
            root.resolve("Interface/AddOns/ThorPad").exists(),
        )
        assertFalse(
            "ThorPad's saved variables outlived the add-on",
            root.resolve("WTF/Account/PLAYER/SavedVariables/ThorPad.lua").exists(),
        )
        // EInteract is shipped again, so its folder is replaced rather than deleted, and the player's
        // saved variables - the key they chose - survive the upgrade.
        assertTrue(
            "EInteract was not reinstalled over the old copy",
            root.resolve("Interface/AddOns/EInteract/EInteract.lua").isFile,
        )
        assertTrue(
            "EInteract's saved variables were deleted",
            root.resolve("WTF/Account/PLAYER/SavedVariables/EInteract.lua").isFile,
        )
        assertEquals("1.5.0-rc2+ei1+sv1", root.resolve(".consoleport-version").readText().trim())
    }

    @Test
    fun `a second launch leaves the installed copy alone`() {
        val root = context.cacheDir.resolve("client-root-again").apply { mkdirs() }
        installLocalAddons(context, root, {})

        // Stand in for anything the player does to the folder, or for the freshly extracted files.
        val toc = root.resolve("Interface/AddOns/ConsolePortLoader/ConsolePortLoader.toc")
        assertTrue(toc.delete())

        installLocalAddons(context, root, {})

        assertFalse("the launch re-extracted the whole archive instead of trusting the marker", toc.isFile)
    }
}
