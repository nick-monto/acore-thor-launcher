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
    fun `clicks and the ring stay reachable with cursor mode off`() {
        PadSettings.init(context)
        // The local realm runs with the launcher's cursor mode off, but the pad's trackpad is a mouse of
        // its own, so the click shortcuts and the ring keep working - and have to stay settable.
        ButtonPad.setR3Toggle(context, WowFlavor.current, false)
        val settings = ExternalPadSettingsView(context, PadTheme.DEFAULT)
        val header = findText(settings, "RIGHT-STICK CURSOR (R3)")
        assertNotNull("the R3 section is listed", header)
        assertTrue(isVisibleInHierarchy(header!!))
        header.performClick()

        val clickSwitch = findButton(settings, "A / B click")
        assertNotNull("with cursor mode off, the click switches are still listed", clickSwitch)
        assertTrue("and reachable without turning the stick tuning back on", isVisibleInHierarchy(clickSwitch!!))
        assertTrue(isVisibleInHierarchy(findButton(settings, "Ring around the cursor")!!))
        assertFalse("the stick tuning stays collapsed while the mode is off", isVisibleInHierarchy(findText(settings, "Stick deadzone")!!))

        findButton(settings, "R3 toggles mouse / camera")!!.performClick()
        assertTrue("turning the mode on opens the stick tuning", isVisibleInHierarchy(findText(settings, "Stick deadzone")!!))

        findButton(settings, "R3 toggles mouse / camera")!!.performClick()
        assertFalse("and turning it off again hides only that", isVisibleInHierarchy(findText(settings, "Stick deadzone")!!))
        assertTrue("the click switches are not part of the stick tuning", isVisibleInHierarchy(clickSwitch))
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
