package com.winlator.inputcontrols

import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import app.gamenative.ui.screen.wow.ensureLocalControllerProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The pad profile the AzerothCore flavour installs is the whole contract with the controller add-on:
 * the pad sends keys, the add-on has been bound to those keys. If a key or an axis code moves, the
 * player's controller quietly stops doing the right thing, so the mapping is pinned here - including
 * that the profile survives a save and a reload, because a profile that loads back empty is a pad
 * that does nothing at all.
 */
@RunWith(RobolectricTestRunner::class)
class LocalPadProfileTest {
    /** Installs the profile the launcher installs at launch, twice, and loads it back off disk. */
    private fun installedProfile(): ControlsProfile {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val id = ensureLocalControllerProfile(context)
        // A second launch must find the installed profile, not add another one beside it.
        assertEquals(id, ensureLocalControllerProfile(context))
        // Read it back the way a launch does: through the manager, off disk.
        val reloaded = InputControlsManager(context).getProfile(id)
        assertNotNull("no profile at id $id on disk", reloaded)
        return reloaded!!
    }

    private fun bindings(profile: ControlsProfile): Map<Int, Binding> =
        profile.loadControllers().single { it.id == "*" }
            .getControllerBindings()
            .associate { it.getKeyCodeForAxis() to it.getBinding() }

    @Test
    fun `face buttons, d-pad, centre and shoulders keep the mapper's keys`() {
        val profile = installedProfile()
        val keys = bindings(profile)
        assertEquals("Thor Controller (3.3.5a)", profile.name)
        // WoWpadX's defaults, which are what ConsolePortLK's setup expects to see.
        assertEquals(Binding.KEY_F9, keys[KeyEvent.KEYCODE_BUTTON_Y])
        assertEquals(Binding.KEY_F10, keys[KeyEvent.KEYCODE_BUTTON_B])
        assertEquals(Binding.KEY_F11, keys[KeyEvent.KEYCODE_BUTTON_A])
        assertEquals(Binding.KEY_F12, keys[KeyEvent.KEYCODE_BUTTON_X])
        assertEquals(Binding.KEY_F1, keys[KeyEvent.KEYCODE_DPAD_UP])
        assertEquals(Binding.KEY_F2, keys[KeyEvent.KEYCODE_DPAD_RIGHT])
        assertEquals(Binding.KEY_F3, keys[KeyEvent.KEYCODE_DPAD_DOWN])
        assertEquals(Binding.KEY_F4, keys[KeyEvent.KEYCODE_DPAD_LEFT])
        assertEquals(Binding.KEY_F5, keys[KeyEvent.KEYCODE_BUTTON_SELECT])
        assertEquals(Binding.KEY_F6, keys[KeyEvent.KEYCODE_BUTTON_START])
        assertEquals(Binding.KEY_KP_MULTIPLY, keys[KeyEvent.KEYCODE_BUTTON_MODE])
        // The left stick click is the interact key: EInteract binds E, and ConsolePort has no binding
        // on that button, so this is the one press that is not a ConsolePort button.
        assertEquals(Binding.KEY_E, keys[KeyEvent.KEYCODE_BUTTON_THUMBL])
        assertNull("the right stick click stays free", keys[KeyEvent.KEYCODE_BUTTON_THUMBR])
        // ConsolePort's two shoulder buttons are F7/F8 and its two modifiers are shift/control, so the
        // pad's shoulders carry the first pair and its triggers the second: a prompt that says "L1"
        // must be the button on the left hand.
        assertEquals(Binding.KEY_F7, keys[KeyEvent.KEYCODE_BUTTON_L1])
        assertEquals(Binding.KEY_F8, keys[KeyEvent.KEYCODE_BUTTON_R1])
        assertEquals(Binding.KEY_SHIFT_L, keys[KeyEvent.KEYCODE_BUTTON_L2])
        assertEquals(Binding.KEY_CTRL_L, keys[KeyEvent.KEYCODE_BUTTON_R2])
    }

    /** Rewrites the installed profile's controller bindings, the way an older launcher left them. */
    private fun rewriteBindings(context: Context, id: Int, entries: Map<Int, Binding>) {
        val profile = ControlsProfile(context, id)
        profile.setName("Thor Controller (3.3.5a)")
        val pad = profile.addController("*")
        entries.forEach { (keyCode, binding) ->
            // A controller binding refuses to be added twice for one key, so replace what is there.
            pad.getControllerBinding(keyCode)?.let { pad.removeControllerBinding(it) }
            val entry = ExternalControllerBinding()
            entry.setKeyCode(keyCode)
            entry.setBinding(binding)
            pad.addControllerBinding(entry)
        }
        profile.save()
    }

    /** Reads the profile the way a launch does: a new manager, off disk. */
    private fun bindingsOnDisk(context: Context, id: Int): Map<Int, Binding> =
        bindings(InputControlsManager(context).getProfile(id)!!)

    @Test
    fun `a profile from before the shoulder fix is corrected once, and a player's own is not`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val id = ensureLocalControllerProfile(context)

        // What the launcher shipped before: the two button shoulders on the right hand, shift and
        // control on the left. A prompt that says "L1" was reaching the right shoulder.
        rewriteBindings(
            context, id, mapOf(
                KeyEvent.KEYCODE_BUTTON_L1 to Binding.KEY_SHIFT_L,
                KeyEvent.KEYCODE_BUTTON_R1 to Binding.KEY_F7,
                KeyEvent.KEYCODE_BUTTON_L2 to Binding.KEY_CTRL_L,
                KeyEvent.KEYCODE_BUTTON_R2 to Binding.KEY_F8,
            ),
        )

        assertEquals(id, ensureLocalControllerProfile(context))
        val fixed = bindingsOnDisk(context, id)
        assertEquals(Binding.KEY_F7, fixed[KeyEvent.KEYCODE_BUTTON_L1])
        assertEquals(Binding.KEY_F8, fixed[KeyEvent.KEYCODE_BUTTON_R1])
        assertEquals(Binding.KEY_SHIFT_L, fixed[KeyEvent.KEYCODE_BUTTON_L2])
        assertEquals(Binding.KEY_CTRL_L, fixed[KeyEvent.KEYCODE_BUTTON_R2])

        // Any other shoulder layout is the player's, so a launch leaves it exactly as it is.
        rewriteBindings(context, id, mapOf(KeyEvent.KEYCODE_BUTTON_L1 to Binding.KEY_F5))

        assertEquals(id, ensureLocalControllerProfile(context))
        assertEquals(Binding.KEY_F5, bindingsOnDisk(context, id)[KeyEvent.KEYCODE_BUTTON_L1])
    }

    @Test
    fun `the left stick moves and the right stick drives the pointer`() {
        val keys = bindings(installedProfile())
        fun axis(axis: Int, sign: Int) = ExternalControllerBinding.getKeyCodeForAxis(axis, sign.toByte())

        assertEquals(Binding.KEY_W, keys[axis(MotionEvent.AXIS_Y, -1)]) // a stick pushed up is a negative Y
        assertEquals(Binding.KEY_S, keys[axis(MotionEvent.AXIS_Y, 1)])
        assertEquals(Binding.KEY_A, keys[axis(MotionEvent.AXIS_X, -1)])
        assertEquals(Binding.KEY_D, keys[axis(MotionEvent.AXIS_X, 1)])
        assertEquals(Binding.MOUSE_MOVE_UP, keys[axis(MotionEvent.AXIS_RZ, -1)])
        assertEquals(Binding.MOUSE_MOVE_DOWN, keys[axis(MotionEvent.AXIS_RZ, 1)])
        assertEquals(Binding.MOUSE_MOVE_LEFT, keys[axis(MotionEvent.AXIS_Z, -1)])
        assertEquals(Binding.MOUSE_MOVE_RIGHT, keys[axis(MotionEvent.AXIS_Z, 1)])

        // Eight directions, eight distinct codes: none of them collapsed onto another direction.
        assertEquals(8, keys.keys.count { it < 0 })
    }
}
