package app.gamenative.ui.screen.wow

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * R3 cursor mode is the launcher's own mouse/camera switch for the right stick. The local realm's
 * client runs ConsolePort, which drives that stick and R3 itself, so the launcher's mode starts off
 * there and the per-game switch has to be able to turn it on. These pin that default, and that one
 * game's choice never becomes another game's default.
 */
@RunWith(RobolectricTestRunner::class)
class R3ToggleDefaultTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `the launcher cursor mode starts off for the local realm`() {
        assertFalse(ButtonPad.r3Toggle(context, WowFlavor.LOCAL_AC))
    }

    @Test
    fun `the other games keep the launcher cursor mode on`() {
        assertTrue(ButtonPad.r3Toggle(context, WowFlavor.FOREVER))
        assertTrue(ButtonPad.r3Toggle(context, WowFlavor.CLASSIC_ERA))
    }

    @Test
    fun `a per-game choice wins over the default and does not leak to another game`() {
        ButtonPad.setR3Toggle(context, WowFlavor.LOCAL_AC, true)
        assertTrue(ButtonPad.r3Toggle(context, WowFlavor.LOCAL_AC))
        assertTrue(ButtonPad.r3Toggle(context, WowFlavor.FOREVER))

        ButtonPad.setR3Toggle(context, WowFlavor.FOREVER, false)
        assertFalse(ButtonPad.r3Toggle(context, WowFlavor.FOREVER))
        assertTrue(ButtonPad.r3Toggle(context, WowFlavor.LOCAL_AC))
    }
}
