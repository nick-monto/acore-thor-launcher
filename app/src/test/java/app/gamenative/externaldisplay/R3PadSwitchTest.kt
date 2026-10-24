package app.gamenative.externaldisplay

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.test.core.app.ApplicationProvider
import app.gamenative.ui.screen.wow.ButtonPad
import app.gamenative.ui.screen.wow.WowFlavor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The R3 switch on the launcher's main screen can only be reached by leaving the game, so the pad
 * carries the same switch. It has to flip the setting the controller reads immediately and save the
 * per-game choice, or turning cursor mode off for ConsolePort means quitting first.
 */
@RunWith(RobolectricTestRunner::class)
class R3PadSwitchTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun findButton(root: View, text: String): Button? {
        if (root is Button && root.text.toString() == text) return root
        if (root !is ViewGroup) return null

        for (i in 0 until root.childCount) {
            findButton(root.getChildAt(i), text)?.let { return it }
        }

        return null
    }

    private fun isVisibleInHierarchy(view: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current.visibility != View.VISIBLE) return false
            current = current.parent as? View
        }
        return true
    }

    @Test
    fun `the pad switch turns cursor mode on and saves it for this game`() {
        PadSettings.init(context)
        val flavor = WowFlavor.current
        ButtonPad.setR3Toggle(context, flavor, false)

        val settings = ExternalPadSettingsView(context, PadTheme.DEFAULT)
        val switch = findButton(settings, "R3 toggles mouse / camera")
        assertNotNull("the pad settings carry the R3 switch", switch)
        assertFalse("cursor mode starts off for the switch under test", PadSettings.bool(PadSettings.R3_TOGGLE))

        switch!!.performClick()

        assertTrue("the controller reads this key on the next R3 press", PadSettings.bool(PadSettings.R3_TOGGLE))
        assertTrue("the choice survives the next launch", ButtonPad.r3Toggle(context, flavor))

        switch.performClick()

        assertFalse(PadSettings.bool(PadSettings.R3_TOGGLE))
        assertFalse(ButtonPad.r3Toggle(context, flavor))
    }

    @Test
    fun `the ring stays reachable with cursor mode off, and the stick's clicks do not`() {
        PadSettings.init(context)
        // The local realm runs with the launcher's cursor mode off; the pad's trackpad still shows the ring,
        // while the face buttons are ConsolePort's there, so their click switches are cursor-mode rows.
        ButtonPad.setR3Toggle(context, WowFlavor.current, false)
        val settings = ExternalPadSettingsView(context, PadTheme.DEFAULT)
        val header = findText(settings, "RIGHT-STICK CURSOR (R3)")
        assertNotNull("the R3 section is listed", header)
        assertTrue(isVisibleInHierarchy(header!!))
        header.performClick()

        val ringSwitch = findButton(settings, "Ring around the cursor")
        assertNotNull("the ring marks the pointer on the trackpad too", ringSwitch)
        assertTrue("so it is settable with the mode off", isVisibleInHierarchy(ringSwitch!!))

        val clickSwitch = findButton(settings, "A / B click")
        assertNotNull("the click switches are still listed for the mode", clickSwitch)
        assertFalse("but hidden while the face buttons are ConsolePort's", isVisibleInHierarchy(clickSwitch!!))
        assertFalse("and so is the stick tuning", isVisibleInHierarchy(findText(settings, "Stick deadzone")!!))

        findButton(settings, "R3 toggles mouse / camera")!!.performClick()
        assertTrue("turning the mode on shows the clicks", isVisibleInHierarchy(clickSwitch))
        assertTrue("and the stick tuning", isVisibleInHierarchy(findText(settings, "Stick deadzone")!!))

        findButton(settings, "R3 toggles mouse / camera")!!.performClick()
        assertFalse(isVisibleInHierarchy(clickSwitch))
        assertFalse(isVisibleInHierarchy(findText(settings, "Stick deadzone")!!))
        assertTrue("the ring is not one of those rows", isVisibleInHierarchy(ringSwitch))
    }

    private fun findText(root: View, contains: String): View? {
        if (root is android.widget.TextView && root.text.toString().contains(contains)) return root
        if (root !is ViewGroup) return null

        for (i in 0 until root.childCount) {
            findText(root.getChildAt(i), contains)?.let { return it }
        }

        return null
    }
}
