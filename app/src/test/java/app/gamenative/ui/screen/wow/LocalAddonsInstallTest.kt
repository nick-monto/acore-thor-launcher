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
        // Runewords rides beside ConsolePort as this project's own files, and its generated pattern
        // table must land too: without it the add-on loads its frame and recognises no word at all.
        assertTrue(
            "Runewords was not installed",
            root.resolve("Interface/AddOns/Runewords/Runewords.toc").isFile,
        )
        assertTrue(
            "Runewords' pattern table was not installed",
            root.resolve("Interface/AddOns/Runewords/Runewords_Data.lua").isFile,
        )
        // CreatureAffixes rides beside Runewords as this project's own files. Its TOC includes the
        // marker table and the TidyPlates nameplate module; all must land or the add-on loads half.
        assertTrue(
            "CreatureAffixes was not installed",
            root.resolve("Interface/AddOns/CreatureAffixes/CreatureAffixes.toc").isFile,
        )
        assertTrue(
            "CreatureAffixes' marker table was not installed",
            root.resolve("Interface/AddOns/CreatureAffixes/CreatureAffixes_Data.lua").isFile,
        )
        assertTrue(
            "CreatureAffixes' TidyPlates module was not installed",
            root.resolve("Interface/AddOns/CreatureAffixes/CreatureAffixes_Plates.lua").isFile,
        )
        assertTrue(
            "CreatureAffixes' TidyPlates module has no native GUID bridge",
            root.resolve("Interface/AddOns/CreatureAffixes/CreatureAffixes_Plates.lua")
                .readText().contains("WrathNameplateGUID"),
        )
        // The skateboard add-on was retired with its paused server module: a fresh client gets no
        // bar and no bindings for a deck that refuses every press. EInteract was retired with its
        // server module too, so no client is asked to install a key that whispers to nothing.
        assertFalse(
            "Skateboard is retired but was installed",
            root.resolve("Interface/AddOns/Skateboard").exists(),
        )
        assertFalse(
            "EInteract is retired but was installed",
            root.resolve("Interface/AddOns/EInteract").exists(),
        )
        // One version: the pinned, unpatched archive.
        assertEquals("1.5.0-rc2+ei17", root.resolve(".consoleport-version").readText().trim())
    }

    @Test
    fun `a client root left by an older launch loses every retired add-on`() {
        val root = context.cacheDir.resolve("client-root-stale").apply { mkdirs() }
        // What an earlier launch left: every add-on it shipped, their saved variables and its marker.
        root.resolve("Interface/AddOns/ThorPad").mkdirs()
        root.resolve("Interface/AddOns/ThorPad/ThorPad.toc").writeText("## Interface: 30300\n")
        root.resolve("Interface/AddOns/Skateboard").mkdirs()
        root.resolve("Interface/AddOns/Skateboard/Skateboard.toc").writeText("## Interface: 30300\n")
        root.resolve("Interface/AddOns/EInteract").mkdirs()
        root.resolve("Interface/AddOns/EInteract/EInteract.toc").writeText("## Interface: 30300\n")
        root.resolve("WTF/Account/PLAYER/SavedVariables").mkdirs()
        root.resolve("WTF/Account/PLAYER/SavedVariables/ThorPad.lua").writeText("ThorPadDB = {}\n")
        root.resolve("WTF/Account/PLAYER/SavedVariables/Skateboard.lua").writeText("SkateboardDB = {}\n")
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
        assertFalse(
            "the retired skateboard add-on is still in the client",
            root.resolve("Interface/AddOns/Skateboard").exists(),
        )
        assertFalse(
            "the retired skateboard add-on's saved variables outlived it",
            root.resolve("WTF/Account/PLAYER/SavedVariables/Skateboard.lua").exists(),
        )
        // EInteract is retired now, so the stale folder and the key the player chose for it go with
        // the module that answered it.
        assertFalse(
            "the retired interact add-on is still in the client",
            root.resolve("Interface/AddOns/EInteract").exists(),
        )
        assertFalse(
            "the retired interact add-on's saved variables outlived it",
            root.resolve("WTF/Account/PLAYER/SavedVariables/EInteract.lua").exists(),
        )
        assertEquals("1.5.0-rc2+ei17", root.resolve(".consoleport-version").readText().trim())
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
