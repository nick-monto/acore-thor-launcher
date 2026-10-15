package app.gamenative.externaldisplay

import android.content.Context
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import app.gamenative.R
import com.winlator.xserver.XKeycode
import com.winlator.xserver.XServer

class ExternalOnScreenKeyboardView(
    context: Context,
    private val xServer: XServer,
    private val theme: PadTheme = PadTheme.DEFAULT,
) : LinearLayout(context) {

    private enum class ShiftState { OFF, ON, CTRL, ALT, CAPS }

    private data class KeySpec(
        val normalLabel: String,
        val shiftedLabel: String? = null,
        val keycode: XKeycode? = null,
        val weight: Float = 1f,
        val isLetter: Boolean = false,
        val action: Action = Action.INPUT,
        /** Symbol-page keys that type the shifted character (e.g. "!" is Shift+1). */
        val sendShifted: Boolean = false,
    )

    private enum class Action { INPUT, SHIFT, BACKSPACE, ENTER, SPACE, ARROW_LEFT, ARROW_DOWN, ARROW_RIGHT, ARROW_UP, SYMBOLS }

    private data class KeyButton(
        val spec: KeySpec,
        val button: Button,
    )

    /**
     * Text-entry mode: keys go to these callbacks instead of the game. Used to type a name for a pad
     * button. Enter finishes the entry.
     */
    class Capture(val onText: (String) -> Unit, val onBackspace: () -> Unit, val onEnter: () -> Unit)

    var capture: Capture? = null

    private val keyButtons = mutableListOf<KeyButton>()
    private val downKeys = mutableSetOf<XKeycode>()
    private var shiftState: ShiftState = ShiftState.OFF
    private var showSymbols = false

    // These must be declared before init: building the layout uses them.
    // Gboard-style pop-up above a pressed character key. Not for Shift, Space, Backspace or the symbols key.
    private val previews = mutableMapOf<KeySpec, PopupWindow>()
    private val previewHides = mutableMapOf<KeySpec, Runnable>()
    private val repeatHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val repeatTasks = mutableMapOf<XKeycode, Runnable>()
    private val pressedAt = mutableMapOf<XKeycode, Long>()
    private var lastShiftTapAt = 0L
    private var shiftBeforeTap = ShiftState.OFF
    private var built = false

    init {
        orientation = VERTICAL
        setMotionEventSplittingEnabled(true)
        val padding = dp(8)
        setPadding(padding, padding, padding, padding)
        setBackgroundColor(theme.background)
        buildLayout()
        refreshLabels()
        built = true
    }

    private fun buildLayout() {
        hideAllPreviews()
        keyButtons.clear()
        removeAllViews()
        if (showSymbols) buildSymbolsPage() else buildMainPage()
    }

    /** Letters, digits and the few symbols used when chatting. Everything else lives on the symbols page. */
    private fun buildMainPage() {
        // Laid out like Gboard: number row, then QWERTY with the home row nudged in, no Esc or Tab.
        addRow(
            listOf(
                KeySpec("1", "!", XKeycode.KEY_1),
                KeySpec("2", "@", XKeycode.KEY_2),
                KeySpec("3", "#", XKeycode.KEY_3),
                KeySpec("4", "$", XKeycode.KEY_4),
                KeySpec("5", "%", XKeycode.KEY_5),
                KeySpec("6", "^", XKeycode.KEY_6),
                KeySpec("7", "&", XKeycode.KEY_7),
                KeySpec("8", "*", XKeycode.KEY_8),
                KeySpec("9", "(", XKeycode.KEY_9),
                KeySpec("0", ")", XKeycode.KEY_0),
            ),
        )

        addRow(
            listOf(
                KeySpec("q", "Q", XKeycode.KEY_Q, isLetter = true),
                KeySpec("w", "W", XKeycode.KEY_W, isLetter = true),
                KeySpec("e", "E", XKeycode.KEY_E, isLetter = true),
                KeySpec("r", "R", XKeycode.KEY_R, isLetter = true),
                KeySpec("t", "T", XKeycode.KEY_T, isLetter = true),
                KeySpec("y", "Y", XKeycode.KEY_Y, isLetter = true),
                KeySpec("u", "U", XKeycode.KEY_U, isLetter = true),
                KeySpec("i", "I", XKeycode.KEY_I, isLetter = true),
                KeySpec("o", "O", XKeycode.KEY_O, isLetter = true),
                KeySpec("p", "P", XKeycode.KEY_P, isLetter = true),
            ),
        )

        addRow(
            listOf(
                KeySpec("a", "A", XKeycode.KEY_A, isLetter = true),
                KeySpec("s", "S", XKeycode.KEY_S, isLetter = true),
                KeySpec("d", "D", XKeycode.KEY_D, isLetter = true),
                KeySpec("f", "F", XKeycode.KEY_F, isLetter = true),
                KeySpec("g", "G", XKeycode.KEY_G, isLetter = true),
                KeySpec("h", "H", XKeycode.KEY_H, isLetter = true),
                KeySpec("j", "J", XKeycode.KEY_J, isLetter = true),
                KeySpec("k", "K", XKeycode.KEY_K, isLetter = true),
                KeySpec("l", "L", XKeycode.KEY_L, isLetter = true),
            ),
            indent = 0.5f,
        )

        addRow(
            listOf(
                KeySpec("Shift", weight = 1.5f, action = Action.SHIFT),
                KeySpec("z", "Z", XKeycode.KEY_Z, isLetter = true),
                KeySpec("x", "X", XKeycode.KEY_X, isLetter = true),
                KeySpec("c", "C", XKeycode.KEY_C, isLetter = true),
                KeySpec("v", "V", XKeycode.KEY_V, isLetter = true),
                KeySpec("b", "B", XKeycode.KEY_B, isLetter = true),
                KeySpec("n", "N", XKeycode.KEY_N, isLetter = true),
                KeySpec("m", "M", XKeycode.KEY_M, isLetter = true),
                KeySpec("⌫", keycode = XKeycode.KEY_BKSP, weight = 1.5f, action = Action.BACKSPACE),
            ),
        )

        addRow(
            listOf(
                KeySpec("?123", weight = 1.5f, action = Action.SYMBOLS),
                KeySpec(",", "<", XKeycode.KEY_COMMA),
                KeySpec("/", "?", XKeycode.KEY_SLASH),
                KeySpec("Space", keycode = XKeycode.KEY_SPACE, weight = 4f, action = Action.SPACE),
                KeySpec(".", ">", XKeycode.KEY_PERIOD),
                KeySpec("Enter", keycode = XKeycode.KEY_ENTER, weight = 1.5f, action = Action.ENTER),
            ),
        )
    }

    /** Every symbol key, each typing the character it shows. "ABC" returns to the letters. */
    private fun buildSymbolsPage() {
        fun sym(label: String, key: XKeycode, shifted: Boolean) = KeySpec(label, keycode = key, sendShifted = shifted)

        addRow(
            listOf(
                sym("!", XKeycode.KEY_1, true),
                sym("@", XKeycode.KEY_2, true),
                sym("#", XKeycode.KEY_3, true),
                sym("$", XKeycode.KEY_4, true),
                sym("%", XKeycode.KEY_5, true),
                sym("^", XKeycode.KEY_6, true),
                sym("&", XKeycode.KEY_7, true),
                sym("*", XKeycode.KEY_8, true),
                sym("(", XKeycode.KEY_9, true),
                sym(")", XKeycode.KEY_0, true),
                KeySpec("⌫", keycode = XKeycode.KEY_BKSP, weight = 1.75f, action = Action.BACKSPACE),
            ),
        )

        addRow(
            listOf(
                sym("-", XKeycode.KEY_MINUS, false),
                sym("_", XKeycode.KEY_MINUS, true),
                sym("=", XKeycode.KEY_EQUAL, false),
                sym("+", XKeycode.KEY_EQUAL, true),
                sym("[", XKeycode.KEY_BRACKET_LEFT, false),
                sym("]", XKeycode.KEY_BRACKET_RIGHT, false),
                sym("{", XKeycode.KEY_BRACKET_LEFT, true),
                sym("}", XKeycode.KEY_BRACKET_RIGHT, true),
                sym("\\", XKeycode.KEY_BACKSLASH, false),
                sym("|", XKeycode.KEY_BACKSLASH, true),
            ),
        )

        addRow(
            listOf(
                sym(";", XKeycode.KEY_SEMICOLON, false),
                sym(":", XKeycode.KEY_SEMICOLON, true),
                sym("'", XKeycode.KEY_APOSTROPHE, false),
                sym("\"", XKeycode.KEY_APOSTROPHE, true),
                sym("`", XKeycode.KEY_GRAVE, false),
                sym("~", XKeycode.KEY_GRAVE, true),
                sym("<", XKeycode.KEY_COMMA, true),
                sym(">", XKeycode.KEY_PERIOD, true),
                sym("/", XKeycode.KEY_SLASH, false),
                sym("?", XKeycode.KEY_SLASH, true),
                KeySpec("Enter", keycode = XKeycode.KEY_ENTER, weight = 1.75f, action = Action.ENTER),
            ),
        )

        addRow(
            listOf(
                KeySpec("ABC", weight = 1.5f, action = Action.SYMBOLS),
                KeySpec("Space", keycode = XKeycode.KEY_SPACE, weight = 6f, action = Action.SPACE),
                KeySpec("←", keycode = XKeycode.KEY_LEFT, weight = 1.25f, action = Action.ARROW_LEFT),
                KeySpec("↓", keycode = XKeycode.KEY_DOWN, weight = 1.25f, action = Action.ARROW_DOWN),
                KeySpec("↑", keycode = XKeycode.KEY_UP, weight = 1.25f, action = Action.ARROW_UP),
                KeySpec("→", keycode = XKeycode.KEY_RIGHT, weight = 1.25f, action = Action.ARROW_RIGHT),
            ),
        )
    }

    private fun addRow(keys: List<KeySpec>, indent: Float = 0f) {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        val margin = dp(3)
        val height = dp(56 * PadSettings.int(PadSettings.KB_HEIGHT) / 100)

        fun gap() = row.addView(View(context), LayoutParams(0, height, indent))
        if (indent > 0f) gap()
        keys.forEach { spec ->
            val button = Button(context).apply {
                isAllCaps = false
                setTextColor(theme.text)
                setTextSize(16f)
                typeface = theme.typeface
                text = spec.normalLabel
                background = createKeyBackground(normal = true)
                setPadding(0, 0, 0, 0)
                layoutParams = LayoutParams(0, height, spec.weight).apply {
                    setMargins(margin, margin, margin, margin)
                }
                setOnTouchListener { view, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        // Same tap feedback as the pad buttons.
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    }
                    handleKeyTouch(spec, event, this)
                    false
                }
            }
            keyButtons += KeyButton(spec, button)
            row.addView(button)
        }
        if (indent > 0f) gap()

        addView(row)
    }

    private fun handleKeyTouch(spec: KeySpec, event: MotionEvent, button: Button) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (spec.action == Action.INPUT && PadSettings.bool(PadSettings.KB_POPUP)) showPreview(spec, button)
                onKeyDown(spec)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                hidePreview(spec)
                onKeyUp(spec, cancel = event.actionMasked == MotionEvent.ACTION_CANCEL)
            }
        }
    }


    private fun showPreview(spec: KeySpec, button: Button) {
        dropPreview(spec)
        try {
            val label = button.text?.toString().orEmpty()
            if (label.isEmpty()) return
            val density = resources.displayMetrics.density
            val w = (button.width + 12 * density).toInt()
            val h = (button.height + 4 * density).toInt()
            val text = TextView(context).apply {
                this.text = label
                gravity = Gravity.CENTER
                setTextColor(theme.text)
                setTextSize(26f)
                typeface = theme.typeface
                background = theme.buttonBackground(density, 10f, active = true)
            }
            val popup = PopupWindow(text, w, h, false).apply {
                isTouchable = false
                isClippingEnabled = false
            }
            val loc = IntArray(2)
            button.getLocationOnScreen(loc)
            val x = loc[0] - (w - button.width) / 2
            val y = loc[1] - h - (4 * density).toInt()
            popup.showAtLocation(button, Gravity.NO_GRAVITY, x, y)
            previews[spec] = popup
        } catch (_: Throwable) {
            // A missing preview must never stop the key from typing.
        }
    }

    /** Lets go of the pop-up after a short hold, so a quick tap is still readable. */
    private fun hidePreview(spec: KeySpec) {
        val popup = previews[spec] ?: return
        val hold = PadSettings.int(PadSettings.KB_POPUP_MS).toLong()
        val hide = Runnable {
            previewHides.remove(spec)
            if (previews[spec] === popup) previews.remove(spec)
            try { popup.dismiss() } catch (_: Throwable) {}
        }
        previewHides.remove(spec)?.let { repeatHandler.removeCallbacks(it) }
        if (hold <= 0L) hide.run() else {
            previewHides[spec] = hide
            repeatHandler.postDelayed(hide, hold)
        }
    }

    /** Drops the pop-up for a key at once (used when the same key is pressed again). */
    private fun dropPreview(spec: KeySpec) {
        previewHides.remove(spec)?.let { repeatHandler.removeCallbacks(it) }
        previews.remove(spec)?.let { try { it.dismiss() } catch (_: Throwable) {} }
    }

    private fun hideAllPreviews() {
        previewHides.values.forEach { repeatHandler.removeCallbacks(it) }
        previewHides.clear()
        previews.values.forEach { try { it.dismiss() } catch (_: Throwable) {} }
        previews.clear()
    }

    private fun onKeyDown(spec: KeySpec) {
        capture?.let { cap ->
            when (spec.action) {
                Action.SHIFT, Action.SYMBOLS -> Unit
                Action.BACKSPACE -> cap.onBackspace()
                Action.ENTER -> cap.onEnter()
                Action.SPACE -> cap.onText(" ")
                Action.INPUT -> {
                    val useShift = spec.sendShifted || when (shiftState) {
                        ShiftState.OFF, ShiftState.CTRL, ShiftState.ALT -> false
                        ShiftState.ON -> true
                        ShiftState.CAPS -> spec.isLetter
                    }
                    // Symbol-page keys already show the character they type.
                    val text = if (useShift && !spec.sendShifted) spec.shiftedLabel ?: spec.normalLabel else spec.normalLabel
                    cap.onText(text)
                    consumeOneShot()
                }
                else -> Unit
            }
            return
        }
        when (spec.action) {
            Action.SHIFT, Action.SYMBOLS -> Unit
            Action.BACKSPACE -> pressRepeating(XKeycode.KEY_BKSP, heldMods())
            Action.ENTER -> pressKey(XKeycode.KEY_ENTER, heldMods())
            Action.SPACE -> pressKey(XKeycode.KEY_SPACE, heldMods())
            Action.ARROW_LEFT -> pressRepeating(XKeycode.KEY_LEFT, arrowMods())
            Action.ARROW_DOWN -> pressRepeating(XKeycode.KEY_DOWN, arrowMods())
            Action.ARROW_RIGHT -> pressRepeating(XKeycode.KEY_RIGHT, arrowMods())
            Action.ARROW_UP -> pressRepeating(XKeycode.KEY_UP, arrowMods())
            Action.INPUT -> {
                val keycode = spec.keycode ?: return
                val useShift = spec.sendShifted || when (shiftState) {
                    ShiftState.OFF, ShiftState.CTRL, ShiftState.ALT -> false
                    ShiftState.ON -> true
                    ShiftState.CAPS -> spec.isLetter
                }
                val mods = heldMods(useShift)
                pressKey(keycode, mods)
            }
        }
        if (spec.action != Action.SHIFT && spec.action != Action.SYMBOLS) consumeOneShot()
    }

    /** Modifier keys to hold around the next key: Ctrl or Alt when chosen on the Shift key, plus Shift when asked. */
    private fun heldMods(shift: Boolean = shiftState == ShiftState.ON): List<XKeycode> = buildList {
        if (shift) add(XKeycode.KEY_SHIFT_L)
        if (shiftState == ShiftState.CTRL) add(XKeycode.KEY_CTRL_L)
        if (shiftState == ShiftState.ALT) add(XKeycode.KEY_ALT_L)
    }

    /** The on-screen arrows always carry Alt: the WoW chat box only moves its cursor / history on Alt+arrow. */
    private fun arrowMods(): List<XKeycode> = (heldMods() + XKeycode.KEY_ALT_L).distinct()

    /** Shift, Ctrl and Alt apply to one key press only; Caps stays on. */
    private fun consumeOneShot() {
        if (shiftState == ShiftState.ON || shiftState == ShiftState.CTRL || shiftState == ShiftState.ALT) {
            shiftState = ShiftState.OFF
            refreshLabels()
        }
    }

    private fun onKeyUp(spec: KeySpec, cancel: Boolean) {
        if (capture != null && spec.action != Action.SHIFT && spec.action != Action.SYMBOLS) return
        when (spec.action) {
            Action.SHIFT -> if (!cancel) cycleShift()
            Action.SYMBOLS -> if (!cancel) post {
                showSymbols = !showSymbols
                buildLayout()
                refreshLabels()
            }
            Action.BACKSPACE -> releaseRepeating(XKeycode.KEY_BKSP)
            Action.ENTER -> releaseKey(XKeycode.KEY_ENTER)
            Action.SPACE -> releaseKey(XKeycode.KEY_SPACE)
            Action.ARROW_LEFT -> releaseRepeating(XKeycode.KEY_LEFT)
            Action.ARROW_DOWN -> releaseRepeating(XKeycode.KEY_DOWN)
            Action.ARROW_RIGHT -> releaseRepeating(XKeycode.KEY_RIGHT)
            Action.ARROW_UP -> releaseRepeating(XKeycode.KEY_UP)
            Action.INPUT -> spec.keycode?.let { releaseKey(it) }
        }
    }


    /** One tap steps Shift, Ctrl, Alt, off. A quick second tap turns Caps on (or off, if it was on). */
    private fun cycleShift() {
        val now = android.os.SystemClock.uptimeMillis()
        if (lastShiftTapAt != 0L && now - lastShiftTapAt < PadSettings.int(PadSettings.KB_CAPS_MS)) {
            shiftState = if (shiftBeforeTap == ShiftState.CAPS) ShiftState.OFF else ShiftState.CAPS
            lastShiftTapAt = 0L
        } else {
            shiftBeforeTap = shiftState
            lastShiftTapAt = now
            shiftState = when (shiftState) {
                ShiftState.OFF -> ShiftState.ON
                ShiftState.ON -> ShiftState.CTRL
                ShiftState.CTRL -> ShiftState.ALT
                ShiftState.ALT, ShiftState.CAPS -> ShiftState.OFF
            }
        }
        refreshLabels()
    }

    private fun refreshLabels() {
        val shiftForLetters = shiftState == ShiftState.ON || shiftState == ShiftState.CAPS
        keyButtons.forEach { (spec, button) ->
            if (spec.action == Action.SHIFT) {
                val label = when (shiftState) {
                    ShiftState.OFF -> "Shift"
                    ShiftState.ON -> "Shift"
                    ShiftState.CTRL -> "Ctrl"
                    ShiftState.ALT -> "Alt"
                    ShiftState.CAPS -> "Caps"
                }
                button.text = label
                button.background = when (shiftState) {
                    ShiftState.OFF -> createKeyBackground(normal = true)
                    ShiftState.ON, ShiftState.CTRL, ShiftState.ALT -> createKeyBackground(highlight = true)
                    ShiftState.CAPS -> createKeyBackground(highlight = true, strong = true)
                }
                return@forEach
            }

            val showShifted = when {
                spec.isLetter -> shiftForLetters
                shiftState == ShiftState.ON -> true
                else -> false
            }

            button.text = if (showShifted && spec.shiftedLabel != null) spec.shiftedLabel else spec.normalLabel
            button.background = createKeyBackground(normal = true)
        }
    }

    // Backspace and the arrows: a tap lasts at least MIN_HOLD_MS (so the game sees it), and holding repeats.

    private fun pressRepeating(key: XKeycode, mods: List<XKeycode> = emptyList()) {
        repeatTasks.remove(key)?.let { repeatHandler.removeCallbacks(it) }
        pressedAt[key] = android.os.SystemClock.uptimeMillis()
        pressKey(key, mods)
        val task = object : Runnable {
            override fun run() {
                releaseKey(key)
                pressKey(key, mods)
                pressedAt[key] = android.os.SystemClock.uptimeMillis()
                repeatHandler.postDelayed(this, 55L)
            }
        }
        repeatTasks[key] = task
        repeatHandler.postDelayed(task, 400L)
    }

    private fun releaseRepeating(key: XKeycode) {
        repeatTasks.remove(key)?.let { repeatHandler.removeCallbacks(it) }
        val held = android.os.SystemClock.uptimeMillis() - (pressedAt.remove(key) ?: 0L)
        val wait = (40L - held).coerceAtLeast(0L)
        if (wait == 0L) releaseKey(key) else repeatHandler.postDelayed({ releaseKey(key) }, wait)
    }

    private fun pressKey(key: XKeycode, mods: List<XKeycode> = emptyList()) {
        if (!downKeys.add(key)) return
        // Shift is skipped if it is already held, so it is not released out from under something else.
        val toHold = mods.filter { it != XKeycode.KEY_SHIFT_L || !xServer.keyboard.modifiersMask.isSet(1) }
        toHold.forEach { xServer.injectKeyPress(it) }
        xServer.injectKeyPress(key)
        toHold.reversed().forEach { xServer.injectKeyRelease(it) }
    }

    private fun releaseKey(key: XKeycode) {
        if (!downKeys.remove(key)) return
        xServer.injectKeyRelease(key)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** Picks up keyboard setting changes (height) each time the keyboard is shown. */
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (changedView === this && visibility == VISIBLE && built) {
            buildLayout()
            refreshLabels()
        }
    }

    override fun onDetachedFromWindow() {
        hideAllPreviews()
        repeatTasks.values.forEach { repeatHandler.removeCallbacks(it) }
        repeatTasks.clear()
        downKeys.toList().forEach { key ->
            xServer.injectKeyRelease(key)
        }
        downKeys.clear()
        super.onDetachedFromWindow()
    }

    /** Normal keys use the pad's stone face; Shift/Caps use the glowing face, Caps with the bright outline. */
    private fun createKeyBackground(
        normal: Boolean = false,
        highlight: Boolean = false,
        strong: Boolean = false,
    ): StateListDrawable {
        val density = resources.displayMetrics.density
        val face = if (highlight) theme.buttonBackground(density, 8f, active = true, emphasized = strong)
        else theme.buttonBackground(density, 8f, active = false)
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), theme.buttonBackground(density, 8f, active = true))
            addState(intArrayOf(), face)
        }
    }
}
