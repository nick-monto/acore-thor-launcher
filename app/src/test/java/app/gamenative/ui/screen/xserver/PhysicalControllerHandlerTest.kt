package app.gamenative.ui.screen.xserver

import android.content.Context
import android.graphics.PointF
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import com.winlator.inputcontrols.Binding
import com.winlator.inputcontrols.BindingCombo
import com.winlator.inputcontrols.ControlsProfile
import com.winlator.inputcontrols.ExternalController
import com.winlator.inputcontrols.ExternalControllerBinding
import com.winlator.inputcontrols.GamepadState
import com.winlator.widget.InputControlsView
import androidx.test.core.app.ApplicationProvider
import app.gamenative.externaldisplay.PadBridge
import app.gamenative.externaldisplay.PadSettings
import com.winlator.xserver.Pointer
import com.winlator.xserver.XServer
import com.winlator.xserver.XKeycode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.TimeUnit.MILLISECONDS

@RunWith(RobolectricTestRunner::class)
class PhysicalControllerHandlerTest {
    @Test
    fun `vertical physical stick release clears gyro mixed gamepad state`() {
        val deviceId = 42
        val rawDownSource = ExternalControllerBinding.getKeyCodeForAxis(MotionEvent.AXIS_Y, 1)
        val rawUpSource = ExternalControllerBinding.getKeyCodeForAxis(MotionEvent.AXIS_Y, -1)
        val controller = motionController(rawDownSource, Binding.GAMEPAD_LEFT_THUMB_UP).apply {
            addControllerBinding(
                ExternalControllerBinding().apply {
                    setKeyCode(rawUpSource)
                    setBinding(Binding.GAMEPAD_LEFT_THUMB_DOWN)
                },
            )
        }
        val gamepadState = GamepadState()
        val profile = mock<ControlsProfile>()
        whenever(profile.getController(deviceId)).thenReturn(controller)
        whenever(profile.gamepadState).thenReturn(gamepadState)
        var baseAxis = 0f
        val handler = PhysicalControllerHandler(
            profile = profile,
            xServer = mock<XServer>(),
            gyroStickMixer = { _, isDown, offset, sourceKeyCode ->
                InputControlsView.updatePhysicalBaseAxis(baseAxis, isDown, offset, sourceKeyCode)
                    .also { baseAxis = it }
            },
            gamepadStateSender = {},
        )
        val event = motionEvent(deviceId)

        try {
            controller.state.thumbLY = 0.8f
            assertTrue(handler.onGenericMotionEvent(event))
            assertEquals(0.8f, gamepadState.thumbLY, 0f)

            controller.state.thumbLY = 0f
            assertTrue(handler.onGenericMotionEvent(event))
            assertEquals(0f, gamepadState.thumbLY, 0f)
        } finally {
            handler.cleanup()
        }
    }

    @Test
    fun `switching profiles transmits the released old gamepad state`() {
        val deviceId = 42
        val keyCode = KeyEvent.KEYCODE_BUTTON_A
        val controllerBinding = ExternalControllerBinding().apply {
            setKeyCode(keyCode)
            setBinding(Binding.GAMEPAD_BUTTON_A)
        }
        val controller = mock<ExternalController>()
        whenever(controller.getControllerBinding(keyCode)).thenReturn(controllerBinding)
        val oldGamepadState = GamepadState()
        val oldProfile = mock<ControlsProfile>()
        whenever(oldProfile.getController(deviceId)).thenReturn(controller)
        whenever(oldProfile.gamepadState).thenReturn(oldGamepadState)
        val newProfile = mock<ControlsProfile>()
        val xServer = mock<XServer>()
        val transmittedButtonStates = mutableListOf<Boolean>()
        val handler = PhysicalControllerHandler(
            profile = oldProfile,
            xServer = xServer,
            gamepadStateSender = { state ->
                transmittedButtonStates.add(
                    state?.isPressed(ExternalController.IDX_BUTTON_A.toInt()) == true,
                )
            },
        )
        val downEvent = mock<KeyEvent>()
        whenever(downEvent.repeatCount).thenReturn(0)
        whenever(downEvent.deviceId).thenReturn(deviceId)
        whenever(downEvent.keyCode).thenReturn(keyCode)
        whenever(downEvent.action).thenReturn(KeyEvent.ACTION_DOWN)

        try {
            assertTrue(handler.onKeyEvent(downEvent))
            assertTrue(oldGamepadState.isPressed(ExternalController.IDX_BUTTON_A.toInt()))
            transmittedButtonStates.clear()

            handler.setProfile(newProfile)

            assertFalse(oldGamepadState.isPressed(ExternalController.IDX_BUTTON_A.toInt()))
            assertEquals(listOf(false), transmittedButtonStates)
        } finally {
            handler.cleanup()
        }
    }

    @Test
    fun `opening radial menu releases matching axes from other controllers`() {
        val radialDeviceId = 41
        val otherDeviceId = 42
        val axisKeyCode = ExternalControllerBinding.getKeyCodeForAxis(MotionEvent.AXIS_X, 1.toByte())
        val radialController = motionController(axisKeyCode, Binding.OPEN_RADIAL_MENU).apply {
            state.thumbLX = 1f
        }
        val otherController = motionController(axisKeyCode, Binding.KEY_E).apply {
            state.thumbLX = 1f
        }
        val profile = mock<ControlsProfile>()
        whenever(profile.getController(radialDeviceId)).thenReturn(radialController)
        whenever(profile.getController(otherDeviceId)).thenReturn(otherController)
        val xServer = mock<XServer>()
        val handler = PhysicalControllerHandler(profile, xServer)
        val otherMotion = motionEvent(otherDeviceId)
        val radialMotion = motionEvent(radialDeviceId)

        try {
            assertTrue(handler.onGenericMotionEvent(otherMotion))
            assertTrue(handler.onGenericMotionEvent(radialMotion))

            verify(xServer).injectKeyPress(XKeycode.KEY_E)
            verify(xServer).injectKeyRelease(XKeycode.KEY_E)
        } finally {
            handler.cleanup()
        }
    }

    @Test
    fun `removing a controller releases its held digital bindings`() {
        val deviceId = 42
        val keyCode = KeyEvent.KEYCODE_BUTTON_A
        val bindingCombo = BindingCombo.fromBindings(
            listOf(Binding.KEY_E, Binding.MOUSE_LEFT_BUTTON, Binding.GAMEPAD_BUTTON_A),
        )
        val controllerBinding = ExternalControllerBinding().apply {
            setKeyCode(keyCode)
            setBindingCombo(bindingCombo)
        }
        val controller = mock<ExternalController>()
        whenever(controller.getControllerBinding(keyCode)).thenReturn(controllerBinding)
        val gamepadState = GamepadState()
        val profile = mock<ControlsProfile>()
        whenever(profile.getController(deviceId)).thenReturn(controller)
        whenever(profile.gamepadState).thenReturn(gamepadState)
        val xServer = mock<XServer>()
        val handler = PhysicalControllerHandler(profile, xServer)
        val downEvent = mock<KeyEvent>()
        whenever(downEvent.repeatCount).thenReturn(0)
        whenever(downEvent.deviceId).thenReturn(deviceId)
        whenever(downEvent.keyCode).thenReturn(keyCode)
        whenever(downEvent.action).thenReturn(KeyEvent.ACTION_DOWN)

        try {
            assertTrue(handler.onKeyEvent(downEvent))
            assertTrue(gamepadState.isPressed(ExternalController.IDX_BUTTON_A.toInt()))

            handler.onInputDeviceRemoved(deviceId)

            verify(xServer).injectKeyPress(XKeycode.KEY_E)
            verify(xServer).injectKeyRelease(XKeycode.KEY_E)
            verify(xServer).injectPointerButtonPress(Pointer.Button.BUTTON_LEFT)
            verify(xServer).injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT)
            assertFalse(gamepadState.isPressed(ExternalController.IDX_BUTTON_A.toInt()))
        } finally {
            handler.cleanup()
        }
    }

    @Test
    fun `removing a controller releases its held analog trigger binding`() {
        val deviceId = 42
        val controller = motionController(KeyEvent.KEYCODE_BUTTON_L2, Binding.KEY_E).apply {
            state.triggerL = 1f
        }
        val profile = mock<ControlsProfile>()
        whenever(profile.getController(deviceId)).thenReturn(controller)
        val xServer = mock<XServer>()
        val handler = PhysicalControllerHandler(profile, xServer)

        try {
            assertTrue(handler.onGenericMotionEvent(motionEvent(deviceId)))
            handler.onInputDeviceRemoved(deviceId)

            verify(xServer).injectKeyPress(XKeycode.KEY_E)
            verify(xServer).injectKeyRelease(XKeycode.KEY_E)
        } finally {
            handler.cleanup()
        }
    }

    @Test
    fun `sequence releases mouse movement without another motion event`() {
        val keyCode = KeyEvent.KEYCODE_BUTTON_A
        val sequenceDelayMs = 150
        val controllerBinding = ExternalControllerBinding().apply {
            setKeyCode(keyCode)
            setBindingCombo(
                BindingCombo.fromBindings(
                    listOf(Binding.MOUSE_MOVE_RIGHT, Binding.KEY_E),
                    BindingCombo.Mode.SEQUENCE,
                    sequenceDelayMs,
                ),
            )
        }
        val controller = mock<ExternalController>()
        whenever(controller.getControllerBinding(keyCode)).thenReturn(controllerBinding)
        val profile = mock<ControlsProfile>()
        whenever(profile.getController(KeyEvent(KeyEvent.ACTION_DOWN, keyCode).deviceId)).thenReturn(controller)
        whenever(profile.cursorSpeed).thenReturn(1f)
        val handler = PhysicalControllerHandler(profile, mock<XServer>())

        try {
            handler.onKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            shadowOf(Looper.getMainLooper()).idleFor(1, MILLISECONDS)
            assertEquals(1f, mouseMoveOffset(handler).x, 0f)

            shadowOf(Looper.getMainLooper()).idleFor(sequenceDelayMs.toLong() - 1, MILLISECONDS)
            assertEquals(0f, mouseMoveOffset(handler).x, 0f)
            assertNull(privateField(handler, "mouseMoveTimer"))
        } finally {
            handler.cleanup()
        }
    }

    @Test
    fun `the pad taking the pointer lets go of the camera stick`() {
        PadSettings.init(ApplicationProvider.getApplicationContext<Context>())
        val deviceId = 42
        val controller = motionController(
            ExternalControllerBinding.getKeyCodeForAxis(MotionEvent.AXIS_Z, 1),
            Binding.GAMEPAD_RIGHT_THUMB_RIGHT,
        )
        val gamepadState = GamepadState()
        val profile = mock<ControlsProfile>()
        whenever(profile.getController(deviceId)).thenReturn(controller)
        whenever(profile.gamepadState).thenReturn(gamepadState)
        val handler = PhysicalControllerHandler(profile = profile, xServer = mock<XServer>(), gamepadStateSender = {})
        val event = motionEvent(deviceId)

        try {
            controller.state.thumbRX = 0.8f
            assertTrue(handler.onGenericMotionEvent(event))
            assertEquals("the controller is driving the camera", 0.8f, gamepadState.thumbRX, 0f)

            // The trackpad was touched: the pad is the pointer, and the camera stick is not the camera any more.
            PadBridge.beginPointerSession()
            assertEquals("a stick held a moment ago cannot turn the view under the trackpad", 0f, gamepadState.thumbRX, 0f)
            assertTrue(PadBridge.isCursorRingShown())

            // The controller is used again: the camera comes back, and the ring goes away.
            assertTrue(handler.onGenericMotionEvent(event))
            assertEquals(0.8f, gamepadState.thumbRX, 0f)
            assertFalse(PadBridge.pointerSession)
            assertFalse(PadBridge.isCursorRingShown())
        } finally {
            PadBridge.endPointerSession()
            handler.cleanup()
        }
    }

    @Test
    fun `a controller press ends the pad's pointer session`() {
        PadSettings.init(ApplicationProvider.getApplicationContext<Context>())
        val deviceId = 42
        val keyCode = KeyEvent.KEYCODE_BUTTON_A
        val xServer = mock<XServer>()
        val handler = padClickHandler(xServer, deviceId, keyCode, Binding.KEY_F11)

        try {
            PadBridge.beginPointerSession()
            assertTrue(PadBridge.isCursorRingShown())

            assertTrue(handler.onKeyEvent(keyEvent(deviceId, keyCode, KeyEvent.ACTION_DOWN)))

            assertFalse(PadBridge.pointerSession)
            assertFalse(PadBridge.isCursorRingShown())
            verify(xServer).injectKeyPress(XKeycode.KEY_F11)
        } finally {
            PadBridge.endPointerSession()
            handler.cleanup()
        }
    }

    @Test
    fun `a face button never clicks while the pad's trackpad is in use`() {
        PadSettings.init(ApplicationProvider.getApplicationContext<Context>())
        PadSettings.set(PadSettings.CLICK_AB, true)
        val deviceId = 42
        val keyCode = KeyEvent.KEYCODE_BUTTON_A
        val xServer = mock<XServer>()
        // The installed local profile maps A to the key ConsolePort binds to its bottom face button.
        val handler = padClickHandler(xServer, deviceId, keyCode, Binding.KEY_F11)

        try {
            assertFalse("cursor mode is off for this case", privateField(handler, "rightStickMouseMode") as Boolean)
            PadBridge.beginPointerSession()

            assertTrue(handler.onKeyEvent(keyEvent(deviceId, keyCode, KeyEvent.ACTION_DOWN)))

            verify(xServer).injectKeyPress(XKeycode.KEY_F11)
            verify(xServer, never()).injectPointerButtonPress(any())
        } finally {
            PadBridge.endPointerSession()
            handler.cleanup()
        }
    }

    /** A handler whose pad maps [keyCode] to [binding], the way the installed local profile does. */
    private fun padClickHandler(xServer: XServer, deviceId: Int, keyCode: Int, binding: Binding): PhysicalControllerHandler {
        val controllerBinding = ExternalControllerBinding().apply {
            setKeyCode(keyCode)
            setBindingCombo(BindingCombo.fromBindings(listOf(binding)))
        }
        val controller = mock<ExternalController>()
        whenever(controller.getControllerBinding(keyCode)).thenReturn(controllerBinding)
        val profile = mock<ControlsProfile>()
        whenever(profile.getController(deviceId)).thenReturn(controller)
        whenever(profile.gamepadState).thenReturn(GamepadState())
        return PhysicalControllerHandler(profile, xServer)
    }

    private fun keyEvent(deviceId: Int, keyCode: Int, action: Int): KeyEvent {
        return mock<KeyEvent>().also { event ->
            whenever(event.repeatCount).thenReturn(0)
            whenever(event.deviceId).thenReturn(deviceId)
            whenever(event.keyCode).thenReturn(keyCode)
            whenever(event.action).thenReturn(action)
        }
    }

    private fun mouseMoveOffset(handler: PhysicalControllerHandler): PointF {
        return privateField(handler, "mouseMoveOffset") as PointF
    }

    private fun motionController(keyCode: Int, binding: Binding): ExternalController {
        return object : ExternalController() {
            override fun updateStateFromMotionEvent(event: MotionEvent): Boolean = true
        }.apply {
            addControllerBinding(
                ExternalControllerBinding().apply {
                    setKeyCode(keyCode)
                    setBinding(binding)
                },
            )
        }
    }

    private fun motionEvent(deviceId: Int): MotionEvent {
        return mock<MotionEvent>().also { event ->
            whenever(event.deviceId).thenReturn(deviceId)
        }
    }

    private fun privateField(handler: PhysicalControllerHandler, name: String): Any? {
        val field = PhysicalControllerHandler::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(handler)
    }
}
