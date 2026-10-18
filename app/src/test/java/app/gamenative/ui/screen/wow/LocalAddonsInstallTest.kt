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
        assertEquals("1.5.0-rc2", root.resolve(".consoleport-version").readText().trim())
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
