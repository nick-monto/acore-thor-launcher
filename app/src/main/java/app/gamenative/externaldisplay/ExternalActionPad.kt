package app.gamenative.externaldisplay

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.annotation.DrawableRes
import android.util.TypedValue
import android.widget.TextView
import android.annotation.SuppressLint
import android.view.MotionEvent
import android.os.SystemClock
import com.winlator.widget.TouchpadView
import com.winlator.winhandler.MouseEventFlags
import com.winlator.xserver.Pointer
import com.winlator.xserver.XKeycode
import com.winlator.xserver.XServer
import app.gamenative.R
import app.gamenative.data.TouchGestureConfig

/**
 * Whole second-screen UI: a slim header with a trackpad button (left) and a keyboard button (right),
 * and below it either the 12 action buttons or a laptop-style trackpad. The keyboard is the app's own
 * on-screen keyboard, drawn over the bottom of whichever is showing.
 */
class ExternalActionPad(
    context: Context,
    private val xServer: XServer,
    private val theme: PadTheme,
    private val touchpadViewProvider: () -> TouchpadView?,
) : LinearLayout(context) {

    private val density = resources.displayMetrics.density
    private lateinit var trackpadButton: ImageButton
    private lateinit var keyboardButton: ImageButton
    private lateinit var settingsButton: ImageButton
    private lateinit var settingsView: ExternalPadSettingsView
    private lateinit var keyboardView: ExternalOnScreenKeyboardView
    private lateinit var textEntryPanel: LinearLayout
    private lateinit var padView: ExternalActionBarView
    private var settingsOpen = false
    private var currentPage = 0
    private val pageTabs = mutableListOf<TextView>()
    private var settingsVersion = 0
    private val heldModifiers = mutableSetOf<XKeycode>()
    private val lockedModifiers = mutableSetOf<XKeycode>()
    // Each modifier can have a button on the pad and one on the trackpad; both follow the same state.
    private val modifierStyles = mutableMapOf<XKeycode, MutableList<(Boolean) -> Unit>>()
    private lateinit var trackpadView: TouchpadView
    private lateinit var trackpadPanel: LinearLayout
    private lateinit var padModifierRow: LinearLayout
    private lateinit var trackpadModifierRow: LinearLayout
    private val releaseMouseButtons = mutableListOf<() -> Unit>()
    // The trackpad click buttons, which an armed modifier also applies to.
    private val glowViews = mutableListOf<TextView>()
    private val lockedMouseButtons = mutableSetOf<String>()

    init {
        PadSettings.init(context)
        orientation = VERTICAL
        setBackgroundColor(theme.background)
        buildUi()
    }

    /** Builds (or rebuilds, after settings changed) everything below the pad background. */
    private fun buildUi() {
        removeAllViews()
        // The panels start hidden again, so the pad is not the pointer any more either.
        PadBridge.endPointerSession()
        // Let go of anything still held (including locked modifiers) before the old buttons are dropped.
        heldModifiers.toList().forEach { xServer.injectKeyRelease(it) }
        releaseMouseButtons.forEach { it() }
        heldModifiers.clear()
        lockedModifiers.clear()
        modifierStyles.clear()
        releaseMouseButtons.clear()
        glowViews.clear()
        settingsOpen = false

        padView = ExternalActionBarView(
            context, xServer, theme,
            onKeyTapped = { releaseModifiers() },
            onChatTyping = { setKeyboard(true); chatKeyboard = true; chatBar.visibility = View.VISIBLE; styleButton(keyboardButton, false) },
            onSuspendModifiers = { suspendModifiers() },
            onResumeModifiers = { resumeModifiers(it) },
            initialPage = currentPage,
        ).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        trackpadView = TouchpadView(context, xServer, false).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            background = theme.buttonBackground(density, 16f, active = false)
            touchpadViewProvider()?.let { setSimTouchScreen(it.isSimTouchScreen) }
            // Slower, steadier cursor for precise aiming: no speed-up on fast swipes, and a lower base speed.
            setCursorAcceleration(PadSettings.trackpadAcceleration)
            setSensitivity(PadSettings.trackpadSensitivity)
            setPrecisionCursor(true)
            if (!PadSettings.bool(PadSettings.TP_TAP)) {
                setGestureConfig(TouchGestureConfig().copy(tapEnabled = false))
            }
            // Touching the trackpad takes the pointer from the controller: the ring shows where a click lands
            // and the camera stick is let go, so a stick deflected a moment ago cannot turn the view under it.
            setOnInteractionListener { PadBridge.beginPointerSession() }
        }
        trackpadPanel = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            // The trackpad plus its thin scroll strip (if enabled) share one slot above the click buttons.
            addView(
                FrameLayout(context).apply {
                    layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                        val m = (10 * density).toInt()
                        // Only a thin gap here; the modifier row below keeps its own gap above the click buttons.
                        setMargins(m, (3 * density).toInt(), m, (4 * density).toInt())
                    }
                    addView(trackpadView)
                    if (PadSettings.bool(PadSettings.SCROLLBAR_ON)) {
                        addView(
                            TrackpadScrollBar(context, theme) { direction -> sendWheel(direction) }.apply {
                                layoutParams = FrameLayout.LayoutParams((26 * density).toInt(), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                                    val edge = (4 * density).toInt()
                                    gravity = if (PadSettings.int(PadSettings.SCROLLBAR_SIDE) == 1) Gravity.START else Gravity.END
                                    if (gravity == Gravity.START) leftMargin = edge else rightMargin = edge
                                }
                            },
                        )
                    }
                },
            )
            addView(
                LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    val m = (10 * density).toInt()
                    layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (64 * density).toInt()).apply {
                        setMargins(m, 0, m, m)
                    }
                    addView(mouseButton("Left click", Pointer.Button.BUTTON_LEFT, MouseEventFlags.LEFTDOWN, MouseEventFlags.LEFTUP))
                    addView(mouseButton("Right click", Pointer.Button.BUTTON_RIGHT, MouseEventFlags.RIGHTDOWN, MouseEventFlags.RIGHTUP))
                },
            )
            visibility = View.GONE
        }
        keyboardView = ExternalOnScreenKeyboardView(context, xServer).apply {
            onEnterReleased = { if (chatKeyboard) post { setKeyboard(false) } }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            visibility = View.GONE
        }
        // A slim strip above the keyboard while a chat button has it up: the close icon cancels the chat.
        val density = context.resources.displayMetrics.density
        chatBar = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (34 * density).toInt())
            setBackgroundColor(theme.background)
            visibility = View.GONE
            addView(Button(context).apply {
                contentDescription = "Cancel chat"
                background = null
                foreground = ChatIconDrawable("Close", theme.text, theme.textPressed)
                layoutParams = FrameLayout.LayoutParams((56 * density).toInt(), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END)
                setOnClickListener {
                    PadSettings.haptic(this)
                    xServer.injectKeyPress(XKeycode.KEY_ESC)
                    xServer.injectKeyRelease(XKeycode.KEY_ESC)
                    setKeyboard(false)
                }
            })
        }
        keyboardHolder = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { gravity = Gravity.BOTTOM }
            addView(chatBar)
            addView(keyboardView)
        }

        trackpadButton = circleButton(R.drawable.icon_trackpad, "Trackpad") { setTrackpad(trackpadPanel.visibility != View.VISIBLE) }
        keyboardButton = circleButton(R.drawable.icon_keyboard, "Keyboard") {
            if (chatKeyboard) {
                // Up only for chat: the header button turns it into the normal keyboard.
                chatKeyboard = false
                chatBar.visibility = View.GONE
                styleButton(keyboardButton, true)
            } else setKeyboard(keyboardView.visibility != View.VISIBLE)
        }
        settingsButton = circleButton(R.drawable.icon_settings, "Settings") { setSettings(!settingsOpen) }
        settingsView = ExternalPadSettingsView(context, theme).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            visibility = View.GONE
        }

        val header = LinearLayout(context).apply {
            headerView = this
            orientation = HORIZONTAL
            // The two header buttons sit on a faint gold backing, like the button groups below.
            background = theme.groupBackground(density, strong = false)
            val side = (8 * density).toInt()
            layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                // Only 1 dp on the side facing the body: the pad's panels bring 3 dp of their own, and the
                // trackpad has a 3 dp top margin, so everything sits 4 dp from the tab bar.
                val gap = (1 * density).toInt()
                if (PadSettings.int(PadSettings.TAB_POS) == 1) setMargins(side, gap, side, side / 2) else setMargins(side, side / 2, side, gap)
            }
            val inset = (4 * density).toInt()
            setPadding(inset, inset, inset, inset)
            gravity = Gravity.CENTER_VERTICAL
            // Page tabs on the left share the width; the three mode buttons stay fixed on the right.
            pageTabs.clear()
            if (!PadSettings.bool(PadSettings.SIDE_BY_SIDE)) {
                ExternalActionBarView.PAGE_TITLES.forEachIndexed { i, title ->
                    pageTabs.add(pageTab(title) { selectPage(i) })
                }
            }
            val modeButtons = listOf(settingsButton, trackpadButton, keyboardButton)
            if (PadSettings.bool(PadSettings.SIDE_BY_SIDE)) {
                // Side by side: no page tabs. The three buttons live at the top of the pad's right column;
                // this header only shows (with the buttons in it) while the trackpad, keyboard or settings are open.
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                // The empty space where the tabs would be names the screen that is open.
                modeTitle = pageTab("") {}.apply {
                    isClickable = false
                    background = theme.buttonBackground(density, 12f, true, muted = true)
                    setTextColor(theme.textPressed)
                }
                // The title sits over the page area and the buttons over the right column, so the buttons keep
                // exactly the size they have on the pad.
                setPadding(0, inset, 0, inset)
                modeTitle.layoutParams = LayoutParams(0, (44 * density).toInt(), ExternalActionBarView.pageWeightNow()).apply { setMargins(m3(), 0, m3(), 0) }
                headerModes = LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    layoutParams = LayoutParams(0, (44 * density).toInt(), 1f)
                }
                if (PadSettings.bool(PadSettings.SWAP_SIDES)) { addView(headerModes); addView(modeTitle) } else { addView(modeTitle); addView(headerModes) }
            } else if (PadSettings.bool(PadSettings.SWAP_TABS)) {
                // "Swap sides (tabs on the right)" puts the three mode buttons first.
                modeButtons.forEach { addView(it) }
                pageTabs.forEach { addView(it) }
            } else {
                pageTabs.forEach { addView(it) }
                modeButtons.forEach { addView(it) }
            }
        }
        // Modifier buttons on the pad (above the number block) follow the "modifier keys shown" setting.
        // With none showing, the row and its backing stay out of the way.
        padModifierRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            val m = (3 * density).toInt()
            setPadding(0, m, 0, 0)
            modifierOrder(PadSettings.MOD_ORDER).forEach { (name, key, setting) ->
                if (PadSettings.bool(setting)) addView(modifierButton(name, key))
            }
        }
        if (padModifierRow.childCount > 0) padView.setModifierRow(padModifierRow)
        // The trackpad always has all three, above the click buttons, whatever the setting says.
        trackpadModifierRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            val m = (10 * density).toInt()
            layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (44 * density).toInt()).apply {
                // A small gap below, so the modifier keys do not touch the click buttons.
                setMargins(m, 0, m, (3 * density).toInt())
            }
            modifierOrder(PadSettings.MOD_ORDER).forEach { (name, key, _) -> addView(modifierButton(name, key)) }
        }
        trackpadPanel.addView(trackpadModifierRow, 1)
        textEntryPanel = buildTextEntryPanel()
        settingsView.onRequestText = { prompt, initial, max, done -> openTextEntry(prompt, initial, max, done) }
        val body = FrameLayout(context).apply {
            layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(padView)
            addView(trackpadPanel)
            addView(keyboardHolder)
            addView(settingsView)
            addView(textEntryPanel)
        }
        if (PadSettings.bool(PadSettings.SIDE_BY_SIDE)) {
            // The same three buttons, in a row at the top of the pad's right column (see [updateModeButtons]).
            padModeRow = LinearLayout(context).apply {
                orientation = HORIZONTAL
                layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (44 * density).toInt() + 2 * m3())
                setPadding(0, m3(), 0, m3())
            }
            padView.setModeRow(padModeRow)
        }
        if (PadSettings.int(PadSettings.TAB_POS) == 1) {
            addView(body)
            addView(header)
        } else {
            addView(header)
            addView(body)
        }
        refreshTabs()
    }

    private fun m3() = (3 * density).toInt()

    private lateinit var headerView: LinearLayout
    private lateinit var modeTitle: TextView
    private lateinit var headerModes: LinearLayout
    private lateinit var padModeRow: LinearLayout

    /**
     * Side by side only: the three mode buttons sit in the pad while it shows. With the trackpad, keyboard or
     * settings open the pad is hidden, so they move up into the header, which shows just then.
     */
    private fun updateModeButtons() {
        if (!PadSettings.bool(PadSettings.SIDE_BY_SIDE) || !::padModeRow.isInitialized) return
        val onPad = padView.visibility == View.VISIBLE
        val target: ViewGroup = if (onPad) padModeRow else headerModes
        listOf(settingsButton, trackpadButton, keyboardButton).forEach { b ->
            if (b.parent !== target) {
                (b.parent as? ViewGroup)?.removeView(b)
                b.layoutParams = LayoutParams(0, (44 * density).toInt(), 1f).apply { setMargins(m3(), 0, m3(), 0) }
                target.addView(b)
            }
        }
        modeTitle.text = when {
            settingsOpen -> "Settings"
            trackpadPanel.visibility == View.VISIBLE -> "Trackpad"
            keyboardView.visibility == View.VISIBLE -> "Keyboard"
            else -> ""
        }
        headerView.visibility = if (onPad) View.GONE else View.VISIBLE
    }

    private fun pageTab(title: String, onClick: () -> Unit) = TextView(context).apply {
        layoutParams = LayoutParams(0, (44 * density).toInt(), 1f).apply {
            val m = (3 * density).toInt()
            setMargins(m, 0, m, 0)
        }
        text = title
        gravity = Gravity.CENTER
        textSize = 14f
        typeface = theme.typeface
        maxLines = 1
        setOnClickListener { onClick() }
    }

    /** Shows a page on the pad, closing the trackpad, keyboard or settings if one of them is open. */
    private fun selectPage(index: Int) {
        currentPage = index
        setTrackpad(false)
        setKeyboard(false)
        setSettings(false)
        padView.showPage(index)
        refreshTabs()
    }

    /** The active page tab only lights up while the pad itself is showing. */
    private fun refreshTabs() {
        updateModeButtons()
        val mainVisible = padView.visibility == View.VISIBLE
        pageTabs.forEachIndexed { i, tab ->
            val active = mainVisible && i == currentPage && !PadSettings.bool(PadSettings.SIDE_BY_SIDE)
            tab.background = theme.buttonBackground(density, 12f, active, muted = true)
            tab.setTextColor(if (active) theme.textPressed else theme.text)
        }
    }

    private lateinit var textPrompt: TextView
    private lateinit var textPreview: TextView
    private lateinit var textKeyboard: ExternalOnScreenKeyboardView
    private val textBuffer = StringBuilder()
    private var textMax = 12
    private var textDone: ((String) -> Unit)? = null

    /**
     * Full-body overlay for typing a short text with the same on-screen keyboard. The space above the
     * keyboard shows what has been typed so far, with Cancel and OK.
     */
    private fun buildTextEntryPanel(): LinearLayout {
        val m = (10 * density).toInt()
        textPrompt = TextView(context)
        textPreview = TextView(context).apply {
            setTextColor(theme.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = theme.typeface
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            // A long name scrolls off the left, so the end being typed stays in view.
            ellipsize = android.text.TextUtils.TruncateAt.START
            background = theme.groupBackground(density, strong = true)
            setPadding(m, 0, m, 0)
            layoutParams = LayoutParams(0, (52 * density).toInt(), 3f).apply { setMargins(m / 2, 0, m / 2, 0) }
        }
        fun actionButton(text: String, onClick: () -> Unit) = TextView(context).apply {
            this.text = text
            gravity = Gravity.CENTER
            setTextColor(theme.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = theme.typeface
            background = theme.buttonBackground(density, 12f, false, muted = true)
            layoutParams = LayoutParams(0, (52 * density).toInt(), 1f).apply { setMargins(m / 2, 0, m / 2, 0) }
            setOnClickListener { PadSettings.haptic(this); onClick() }
        }
        textKeyboard = ExternalOnScreenKeyboardView(context, xServer).apply {
            layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            capture = ExternalOnScreenKeyboardView.Capture(
                onText = { if (textBuffer.length < textMax) { textBuffer.append(it); refreshTextPreview() } },
                onBackspace = { if (textBuffer.isNotEmpty()) { textBuffer.setLength(textBuffer.length - 1); refreshTextPreview() } },
                onEnter = { finishTextEntry(true) },
            )
        }
        return LinearLayout(context).apply {
            orientation = VERTICAL
            setBackgroundColor(theme.background)
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            visibility = View.GONE
            isClickable = true
            // One compact bar: the typed text, Cancel and OK. This leaves the full keyboard on screen.
            addView(
                LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(textPreview)
                    addView(actionButton("Cancel") { finishTextEntry(false) })
                    addView(actionButton("OK") { finishTextEntry(true) })
                },
                LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(m / 2, m / 2, m / 2, 0) },
            )
            addView(View(context), LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(textKeyboard)
        }
    }

    private fun refreshTextPreview() {
        // With nothing typed yet, the box shows what the name is for.
        if (textBuffer.isEmpty()) {
            textPreview.text = textPrompt.text
            textPreview.alpha = 0.55f
        } else {
            textPreview.text = textBuffer.toString() + "▏"
            textPreview.alpha = 1f
        }
    }

    private fun openTextEntry(prompt: String, initial: String, max: Int, done: (String) -> Unit) {
        textBuffer.setLength(0)
        textBuffer.append(initial.take(max))
        textMax = max
        textDone = done
        textPrompt.text = prompt
        refreshTextPreview()
        textEntryPanel.visibility = View.VISIBLE
    }

    private fun finishTextEntry(accept: Boolean) {
        val done = textDone
        textDone = null
        textEntryPanel.visibility = View.GONE
        if (accept) done?.invoke(textBuffer.toString().trim())
    }

    /**
     * Modifier button with three states:
     * - tap: armed (lit). It lets go by itself right after the next pad button (1-9, 0, -, =).
     * - double tap: locked (lit with a bright outline). It stays held until you tap it again.
     * - tap while armed or locked: off.
     */
    private fun modifierButton(label: String, key: XKeycode): TextView {
        return TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(theme.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = theme.typeface
            contentDescription = label
            layoutParams = LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                val m = (3 * density).toInt()
                setMargins(m, 0, m, m)
            }
            fun style(active: Boolean) {
                val locked = key in lockedModifiers
                // A glow color chosen for this modifier also becomes the color of its button.
                val accent = glowSettingFor(key)?.let { PadSettings.glowColor(it) }
                background = theme.buttonBackground(density, 12f, active, emphasized = locked, muted = true, accent = accent)
                setTextColor(if (active) theme.textPressed else accent ?: theme.text)
                refreshGlow()
            }
            style(isArmed(key))
            modifierStyles.getOrPut(key) { mutableListOf() }.add { active -> style(active) }
            var lastArmedAt = 0L
            setOnClickListener {
                let { PadSettings.haptic(it) }
                val now = android.os.SystemClock.uptimeMillis()
                when {
                    key !in heldModifiers -> {
                        heldModifiers.add(key)
                        xServer.injectKeyPress(key)
                        lastArmedAt = now
                        style(true)
                    }
                    key !in lockedModifiers && now - lastArmedAt <= PadSettings.doubleTapMs -> {
                        lockedModifiers.add(key)
                        style(true)
                    }
                    else -> {
                        heldModifiers.remove(key)
                        lockedModifiers.remove(key)
                        xServer.injectKeyRelease(key)
                        style(false)
                    }
                }
            }
        }
    }

    private companion object {
        /** One wheel notch, as Windows counts it (the same value the X input layer uses). */
        const val WHEEL_DELTA = 120

        /** Every left-to-right order of Shift (S), Ctrl (C) and Alt (A). */
        val MODIFIER_ORDERS = listOf("SCA", "SAC", "CSA", "CAS", "ASC", "ACS")
    }

    /** One mouse wheel tick from the scroll strip: +1 scrolls down, -1 scrolls up. Same two paths as the buttons. */
    private fun sendWheel(direction: Int) {
        PadBridge.beginPointerSession()
        if (xServer.isRelativeMouseMovement()) {
            xServer.getWinHandler().mouseEvent(MouseEventFlags.WHEEL, 0, 0, if (direction > 0) -WHEEL_DELTA else WHEEL_DELTA)
        } else {
            val button = if (direction > 0) Pointer.Button.BUTTON_SCROLL_DOWN else Pointer.Button.BUTTON_SCROLL_UP
            xServer.injectPointerButtonPress(button)
            xServer.injectPointerButtonRelease(button)
        }
    }

    /**
     * Mouse button under the trackpad. It acts like a real button: finger down presses it, finger up
     * releases it, so you can hold and drag. Double-tap and it stays pressed after you lift your finger
     * (lit with a bright outline) until you tap it again.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun mouseButton(label: String, button: Pointer.Button, downFlag: Int, upFlag: Int): TextView {
        return GlowTextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(theme.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = theme.typeface
            contentDescription = label
            layoutParams = LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                val m = (4 * density).toInt()
                setMargins(m, 0, m, 0)
            }
            glowViews.add(this)
            var pressed = false
            var locked = false
            var lockOnRelease = false
            var ignoreUp = false
            var lastDownAt = 0L
            // Click wait: a single tap's press is held back briefly, so a double tap starts a hold without
            // sending a click first.
            var pressSent = false
            var tapPending = false
            var pendingPress: Runnable? = null
            fun cancelPending() {
                pendingPress?.let { removeCallbacks(it) }
                pendingPress = null
                tapPending = false
            }

            fun style() {
                // A held button must survive quick trackpad touches, which would otherwise count as taps.
                if (locked) lockedMouseButtons.add(label) else lockedMouseButtons.remove(label)
                trackpadView.setTapClicksSuppressed(lockedMouseButtons.isNotEmpty())
                background = theme.buttonBackground(density, 12f, pressed || locked, emphasized = locked)
                setTextColor(if (pressed || locked) theme.textPressed else theme.text)
            }
            // Always go through the X pointer, which keeps its own record of the button and forwards to the Wine
            // mouse when the game is in relative mode. Pressing in one mode and releasing in the other (the game
            // switches modes while you drag) used to leave the two out of step, so a hold could drop or stick.
            fun send(down: Boolean) {
                PadBridge.beginPointerSession()
                if (down) {
                    if (xServer.pointer.isButtonPressed(button)) xServer.injectPointerButtonRelease(button)
                    xServer.injectPointerButtonPress(button)
                } else {
                    val forwarded = xServer.pointer.isButtonPressed(button)
                    xServer.injectPointerButtonRelease(button)
                    // If the game is in relative mode and the release was not already forwarded to it, send it once.
                    if (xServer.isRelativeMouseMovement() && !forwarded) xServer.getWinHandler().mouseEvent(upFlag, 0, 0, 0)
                }
            }
            style()
            releaseMouseButtons.add {
                cancelPending()
                if (pressSent || locked) send(false)
                pressSent = false
                pressed = false; locked = false; lockOnRelease = false; ignoreUp = false
                style()
            }
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        view.let { PadSettings.haptic(it) }
                        val now = SystemClock.uptimeMillis()
                        if (locked) {
                            send(false)
                            pressSent = false
                            locked = false
                            pressed = false
                            ignoreUp = true
                        } else if (tapPending) {
                            // Second tap inside the wait: start the hold, and the first tap never clicks.
                            cancelPending()
                            send(true)
                            pressSent = true
                            pressed = true
                            lockOnRelease = true
                            lastDownAt = now
                        } else {
                            pressed = true
                            lockOnRelease = now - lastDownAt <= PadSettings.doubleTapMs
                            lastDownAt = now
                            val wait = PadSettings.int(PadSettings.CLICK_WAIT_MS)
                            if (wait > 0 && !lockOnRelease) {
                                val run = Runnable {
                                    pendingPress = null
                                    send(true)
                                    pressSent = true
                                    if (!pressed) {
                                        // The finger already lifted: finish the click now.
                                        send(false)
                                        pressSent = false
                                        tapPending = false
                                        releaseModifiers()
                                    }
                                }
                                pendingPress = run
                                postDelayed(run, wait.toLong())
                            } else {
                                send(true)
                                pressSent = true
                            }
                        }
                        style()
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        when {
                            ignoreUp -> ignoreUp = false
                            lockOnRelease && pressed -> {
                                locked = true
                                lockOnRelease = false
                            }
                            pressed && !pressSent -> tapPending = true // the wait finishes the click
                            pressed -> {
                                send(false)
                                pressSent = false
                                releaseModifiers()
                            }
                        }
                        pressed = false
                        style()
                    }
                }
                true
            }
        }
    }

    /** Lets go of the held modifiers for one button press (they stay armed on the pad). Returns the ones released. */
    /** Shift, Ctrl and Alt in the order chosen by [orderSetting], each with the setting that shows or hides it on the pad. */
    private fun modifierOrder(orderSetting: String): List<Triple<String, XKeycode, String>> {
        val all = mapOf(
            'S' to Triple("Shift", XKeycode.KEY_SHIFT_L, PadSettings.MOD_SHIFT),
            'C' to Triple("Ctrl", XKeycode.KEY_CTRL_L, PadSettings.MOD_CTRL),
            'A' to Triple("Alt", XKeycode.KEY_ALT_L, PadSettings.MOD_ALT),
        )
        val order = MODIFIER_ORDERS[PadSettings.int(orderSetting).coerceIn(0, MODIFIER_ORDERS.lastIndex)]
        return order.map { all.getValue(it) }
    }

    private fun glowSettingFor(key: XKeycode): String? = when (key) {
        XKeycode.KEY_SHIFT_L -> PadSettings.MOD_GLOW_SHIFT
        XKeycode.KEY_CTRL_L -> PadSettings.MOD_GLOW_CTRL
        XKeycode.KEY_ALT_L -> PadSettings.MOD_GLOW_ALT
        else -> null
    }

    private fun isArmed(key: XKeycode) = key in heldModifiers || key in PadBridge.triggerModifiers

    /** The triggers took or let go of a modifier: show it on the modifier buttons and the glow. */
    private fun triggerModifiersChanged() {
        modifierStyles.forEach { (key, styles) -> styles.forEach { it(isArmed(key)) } }
        refreshGlow()
    }

    /** Lights up the buttons armed modifiers apply to: the number block, Extra Buttons (if allowed) and the click buttons. */
    private fun refreshGlow() {
        if (!::padView.isInitialized) return
        // Modifiers held by the L2 / R2 triggers count too.
        val armed = (heldModifiers + PadBridge.triggerModifiers).toList()
        val color = if (armed.isEmpty() || !PadSettings.bool(PadSettings.MOD_GLOW)) null
        else mixGlow(armed.map { key -> glowSettingFor(key)?.let { PadSettings.glowColor(it) } ?: DEFAULT_GLOW })
        val level = PadSettings.int(PadSettings.MOD_GLOW_LEVEL)
        padView.setModifierGlow(color, level)
        glowViews.forEach { applyGlow(it, color, level, density) }
    }

    private fun suspendModifiers(): List<XKeycode> =
        heldModifiers.toList().also { keys -> keys.forEach { xServer.injectKeyRelease(it) } }

    /** Presses back the suspended modifiers the user is still holding (locked ones; one-shots are used up). */
    private fun resumeModifiers(keys: List<XKeycode>) {
        keys.filter { it in heldModifiers }.forEach { xServer.injectKeyPress(it) }
    }

    /** Lets go of every modifier, locked ones too, and shows them off. Used when switching tabs. */
    private fun releaseAllModifiers() {
        if (heldModifiers.isEmpty()) return
        val keys = heldModifiers.toList()
        heldModifiers.clear()
        lockedModifiers.clear()
        keys.forEach { key ->
            xServer.injectKeyRelease(key)
            modifierStyles[key]?.forEach { it(false) }
        }
    }

    /** Lets go of every armed modifier. Locked ones stay down. */
    private fun releaseModifiers() {
        heldModifiers.toList().filter { it !in lockedModifiers }.forEach { key ->
            heldModifiers.remove(key)
            xServer.injectKeyRelease(key)
            modifierStyles[key]?.forEach { it(false) }
        }
    }

    /** Never leave a modifier stuck down if the display goes away. */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Lets the controller's A / B cursor clicks release armed modifiers too.
        PadBridge.releaseModifiers = { releaseModifiers() }
        PadBridge.triggerModifiersChanged = { triggerModifiersChanged() }
        triggerModifiersChanged()
    }

    override fun onDetachedFromWindow() {
        PadBridge.releaseModifiers = null
        PadBridge.triggerModifiersChanged = null
        heldModifiers.toList().forEach { xServer.injectKeyRelease(it) }
        heldModifiers.clear()
        lockedModifiers.clear()
        releaseMouseButtons.forEach { it() }
        super.onDetachedFromWindow()
    }


    /** Trackpad and keyboard are mutually exclusive: opening one closes the other. */
    private fun setTrackpad(on: Boolean) {
        if (on != (trackpadPanel.visibility == View.VISIBLE)) releaseAllModifiers()
        if (on) {
            setKeyboard(false)
            setSettings(false)
        }
        trackpadPanel.visibility = if (on) View.VISIBLE else View.GONE
        // Closing the panel ends the pad's pointer session; the controller has the camera again.
        if (!on) {
            PadBridge.endPointerSession()
            releaseMouseButtons.forEach { it() }
        }
        padView.visibility = if (on) View.GONE else View.VISIBLE
        // A modifier armed on one view shows as armed on the other too.
        modifierStyles.forEach { (key, styles) -> styles.forEach { it(isArmed(key)) } }
        styleButton(trackpadButton, on)
        refreshTabs()
    }

    /** True while the keyboard is up only because a chat button asked for it; Enter puts it away again. */
    private var chatKeyboard = false
    private lateinit var chatBar: FrameLayout
    private lateinit var keyboardHolder: LinearLayout

    private fun setKeyboard(on: Boolean) {
        chatKeyboard = false
        chatBar.visibility = View.GONE
        if (on != (keyboardView.visibility == View.VISIBLE)) releaseAllModifiers()
        if (on) {
            finishTextEntry(false)
            setTrackpad(false)
            setSettings(false)
        }
        keyboardView.visibility = if (on) View.VISIBLE else View.GONE
        // With the keyboard up, the space above it is left blank (just the pad background).
        padView.visibility = if (on) View.GONE else View.VISIBLE
        styleButton(keyboardButton, on)
        refreshTabs()
    }

    /**
     * The settings screen replaces the pad, like the trackpad and keyboard do. Closing it rebuilds the
     * pad if any setting changed, so the new layout, look and trackpad feel apply.
     */
    private fun setSettings(on: Boolean) {
        if (on == settingsOpen) return
        releaseAllModifiers()
        if (!on) finishTextEntry(false)
        if (on) {
            setTrackpad(false)
            setKeyboard(false)
            settingsVersion = PadSettings.version
            settingsOpen = true
            settingsView.visibility = View.VISIBLE
            padView.visibility = View.GONE
            styleButton(settingsButton, true)
            refreshTabs()
        } else {
            settingsOpen = false
            if (PadSettings.version != settingsVersion) {
                buildUi()
            } else {
                settingsView.visibility = View.GONE
                padView.visibility = View.VISIBLE
                styleButton(settingsButton, false)
                refreshTabs()
            }
        }
    }

    private fun circleButton(@DrawableRes icon: Int, label: String, onClick: () -> Unit): ImageButton {
        return ImageButton(context).apply {
            // Fixed-width icon buttons; the page tabs take the rest of the header.
            layoutParams = LayoutParams((52 * density).toInt(), (44 * density).toInt()).apply {
                val m = (3 * density).toInt()
                setMargins(m, 0, m, 0)
            }
            setImageResource(icon)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val pad = (8 * density).toInt()
            setPadding(pad, pad, pad, pad)
            contentDescription = label
            setOnClickListener { onClick() }
            styleButton(this, false)
        }
    }

    private fun styleButton(button: ImageButton, active: Boolean) {
        button.background = theme.buttonBackground(density, 12f, active, muted = true)
        button.setColorFilter(if (active) theme.textPressed else theme.text)
    }
}
