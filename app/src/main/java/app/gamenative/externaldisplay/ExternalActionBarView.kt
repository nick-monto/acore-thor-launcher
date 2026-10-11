package app.gamenative.externaldisplay

import android.annotation.SuppressLint
import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.winlator.xserver.XKeycode
import com.winlator.xserver.XServer

/**
 * The main second-screen pad.
 *
 * Left half: a column of window shortcuts (Map, Character, Spellbook, Talents, Skills, Quest Log,
 * Social, System).
 * Right half, top to bottom: the modifier buttons (see [setModifierRow]), the 12 action buttons
 * (4 rows of 3), and F1-F12 in two rows.
 *
 * Every button sends WoW's default key, so the pad works with the stock bindings and needs no addon.
 * To use different keys, change the lists in the companion object.
 *
 * One touch is exactly one key press and one key release. There are no macros, sequences or timed
 * repeats here: anything multi-step belongs in WoW's own macro system.
 */
class ExternalActionBarView(
    context: Context,
    private val xServer: XServer,
    private val theme: PadTheme,
    /** Called after a button's key has been released, e.g. so one-shot modifiers can let go. */
    private val onKeyTapped: () -> Unit = {},
    /** Lets go of the Shift, Ctrl and Alt the user is holding on the pad, and returns which ones were down. */
    private val onSuspendModifiers: () -> List<XKeycode> = { emptyList() },
    /** Presses back the modifiers [onSuspendModifiers] returned, those the user is still holding. */
    private val onResumeModifiers: (List<XKeycode>) -> Unit = {},
    /** The page shown first (index into the pages). */
    initialPage: Int = 0,
) : LinearLayout(context) {

    /**
     * [mods] are held around [key] (Shift+Tab). An [isolated] button ignores any modifier the user is
     * holding on the pad, so only its own modifiers reach the game.
     */
    private data class Slot(val label: String, val key: XKeycode, val mods: List<XKeycode> = emptyList(), val isolated: Boolean = false)

    private class Down(val combo: KeyCombo, val isolated: Boolean)

    private val downKeys = mutableMapOf<XKeycode, Down>()
    private var isolatedDown = 0
    private var suspendedModifiers: List<XKeycode> = emptyList()
    private val rightColumn: LinearLayout
    private val modifierGroup: LinearLayout

    /** A backing panel that hides itself while it has nothing in it (e.g. the modifiers moved to the trackpad). */
    private class Group(context: Context) : LinearLayout(context) {
        override fun onViewAdded(child: View?) {
            super.onViewAdded(child)
            visibility = VISIBLE
        }

        override fun onViewRemoved(child: View?) {
            super.onViewRemoved(child)
            if (childCount == 0) visibility = GONE
        }
    }

    private val pageHost: LinearLayout
    private var pageIndex = initialPage.coerceIn(PAGES.indices)

    init {
        orientation = HORIZONTAL
        isMotionEventSplittingEnabled = true
        setBackgroundColor(theme.background)
        val pad = dp(8)
        setPadding(pad, pad, pad, pad)

        // Each group of buttons sits on its own faint backing; the number block's is the strongest.
        // The page area shows one page of buttons at a time (see [showPage]).
        pageHost = LinearLayout(context).apply {
            orientation = HORIZONTAL
            isMotionEventSplittingEnabled = true
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, LEFT_WEIGHT * pageWidthScale())
        }
        populatePage()
        modifierGroup = group(vertical = false, strong = false).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 0.7f * PadSettings.int(PadSettings.MOD_HEIGHT) / 100f).apply { setMargins(0, dp(3), 0, dp(3)) }
            visibility = GONE
        }
        val numberGroup = group(vertical = true, strong = true).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 4f).apply { setMargins(0, dp(3), 0, dp(3)) }
            // A hidden number keeps its place as a gap, so the others stay where the thumb expects them.
            orderedSlots(GROUP_NUMBERS, ACTION_SLOTS).chunked(3).forEach {
                addView(keyRow(it, textSp = 26f, weight = 1f, raised = PadSettings.emphasis > 0f))
            }
            if (!PadSettings.bool(PadSettings.SEC_NUMBERS)) visibility = GONE
        }
        // The optional Extra Buttons panel: up to five buttons in one row.
        val extraGroup: LinearLayout? = if (PadSettings.bool(PadSettings.EXTRA_ON)) {
            val slots = orderedSlots(GROUP_EXTRAS, EXTRA_SLOTS).take(PadSettings.int(PadSettings.EXTRA_N).coerceIn(1, 5))
            group(vertical = true, strong = false).apply {
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 0.7f * PadSettings.int(PadSettings.EXTRA_HEIGHT) / 100f).apply { setMargins(0, dp(3), 0, dp(3)) }
                addView(keyRow(slots, textSp = 14f, weight = 1f, muted = true, wrap = true))
            }
        } else null
        // The three panels stack in the order chosen in settings (see [COLUMN_ORDERS]).
        rightColumn = column(RIGHT_WEIGHT).apply {
            COLUMN_ORDERS[PadSettings.int(PadSettings.COL_ORDER).coerceIn(0, COLUMN_ORDERS.lastIndex)].forEach { panel ->
                when (panel) {
                    'N' -> addView(numberGroup)
                    'M' -> addView(modifierGroup)
                    else -> extraGroup?.let { addView(it) }
                }
            }
        }
        // "Swap sides" puts the page area on the right.
        if (PadSettings.bool(PadSettings.SWAP_SIDES)) {
            addView(rightColumn)
            addView(pageHost)
        } else {
            addView(pageHost)
            addView(rightColumn)
        }
    }

    /** Shows page [index] of [PAGES]. Lets go of any pressed button first, since its view is about to go. */
    fun showPage(index: Int) {
        releaseAllKeys()
        pageIndex = index.coerceIn(PAGES.indices)
        populatePage()
    }

    /** Fills the page area: one column of buttons, or two when the page has more than [SINGLE_COLUMN_MAX]. */
    private fun populatePage() {
        pageHost.removeAllViews()
        val page = PAGES[pageIndex]
        val slots = orderedSlots(page.id, page.slots).filter { !PadSettings.isHidden(it.label) }
        val columns = if (slots.size > SINGLE_COLUMN_MAX) slots.chunked((slots.size + 1) / 2) else listOf(slots)
        val split = columns.size > 1
        columns.forEach { column ->
            pageHost.addView(
                group(vertical = true, strong = false).apply {
                    layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }
                    column.forEach { addView(keyRow(listOf(it), textSp = if (split) 13f else 15f, weight = 1f, muted = true, wrap = split)) }
                },
            )
        }
        (pageHost.layoutParams as LayoutParams).weight = LEFT_WEIGHT * pageWidthScale()
        pageHost.requestLayout()
    }

    private fun pageWidthScale() = PadSettings.int(PadSettings.PAGE_WIDTH) / 100f

    /** Puts the modifier buttons above the action buttons. */
    fun setModifierRow(row: View) {
        (row.parent as? android.view.ViewGroup)?.removeView(row)
        row.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        modifierGroup.addView(row)
    }

    private fun group(vertical: Boolean, strong: Boolean): LinearLayout = Group(context).apply {
        orientation = if (vertical) VERTICAL else HORIZONTAL
        isMotionEventSplittingEnabled = true
        background = theme.groupBackground(resources.displayMetrics.density, strong)
        setPadding(dp(4), dp(4), dp(4), dp(4))
    }

    private fun column(weight: Float) = LinearLayout(context).apply {
        orientation = VERTICAL
        isMotionEventSplittingEnabled = true
        layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, weight)
    }

    /** [muted] dims the button trim; the action buttons (1-9, 0, -, =) are left at full strength. */
    private fun keyRow(
        slots: List<Slot>,
        textSp: Float,
        weight: Float,
        muted: Boolean = false,
        raised: Boolean = false,
        wrap: Boolean = false,
    ): LinearLayout {
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            isMotionEventSplittingEnabled = true
            // Rows share the column's height by weight, so everything always fits.
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, weight)
            slots.forEach { slot ->
                val button = createButton(slot, textSp, muted, raised, wrap)
                if (PadSettings.isHidden(slot.label)) {
                    button.visibility = INVISIBLE
                    button.isEnabled = false
                    button.setOnTouchListener(null)
                }
                addView(button)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createButton(slot: Slot, textSp: Float, muted: Boolean, raised: Boolean, wrap: Boolean = false): View {
        return TextView(context).apply {
            text = PadSettings.displayName(slot.label)
            contentDescription = slot.label
            gravity = Gravity.CENTER
            setTextColor(theme.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp * PadSettings.labelScale)
            typeface = theme.typeface
            if (wrap) {
                // Narrow two-column buttons may wrap a label onto a second line, shrinking only if it still will not fit.
                maxLines = 2
                setAutoSizeTextTypeUniformWithConfiguration(8, (textSp * PadSettings.labelScale).toInt().coerceAtLeast(9), 1, TypedValue.COMPLEX_UNIT_SP)
            } else {
                maxLines = 1
            }
            val accent = PadSettings.colorOf(slot.label)
            background = createKeyBackground(muted, raised, accent)
            if (accent != null) setTextColor(accent)
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply {
                val margin = dp(3)
                setMargins(margin, margin, margin, margin)
            }
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        view.isPressed = true
                        PadSettings.haptic(view)
                        pressKey(comboFor(slot), isolated = slot.isolated)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        view.isPressed = false
                        releaseKey(comboFor(slot).key)
                    }
                }
                true
            }
        }
    }

    /** What a button sends: the user's remap if there is one, otherwise the default. */
    private fun comboFor(slot: Slot): KeyCombo =
        PadSettings.remapped(slot.label)?.let { KeyCombo.parse(it) } ?: KeyCombo(slot.key, slot.mods)

    private fun pressKey(combo: KeyCombo, isolated: Boolean = false) {
        if (downKeys.containsKey(combo.key)) return
        // A button with its own modifiers, or an isolated one, must not pick up Shift, Ctrl or Alt held on the pad.
        val isolate = isolated || combo.mods.isNotEmpty()
        downKeys[combo.key] = Down(combo, isolate)
        if (isolate && isolatedDown++ == 0) suspendedModifiers = onSuspendModifiers()
        combo.mods.forEach { xServer.injectKeyPress(it) }
        xServer.injectKeyPress(combo.key)
    }

    private fun releaseKey(key: XKeycode) {
        val down = downKeys.remove(key) ?: return
        xServer.injectKeyRelease(key)
        down.combo.mods.asReversed().forEach { xServer.injectKeyRelease(it) }
        onKeyTapped()
        if (down.isolated && --isolatedDown == 0) {
            onResumeModifiers(suspendedModifiers)
            suspendedModifiers = emptyList()
        }
    }

    private fun createKeyBackground(muted: Boolean, raised: Boolean, accent: Int? = null) =
        theme.buttonStates(resources.displayMetrics.density, 10f, muted, raised, accent)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** Lets go of every pressed button, and gives back any modifier held aside for an isolated one. */
    private fun releaseAllKeys() {
        downKeys.values.toList().forEach { d ->
            xServer.injectKeyRelease(d.combo.key)
            d.combo.mods.forEach { xServer.injectKeyRelease(it) }
        }
        downKeys.clear()
        if (isolatedDown > 0) onResumeModifiers(suspendedModifiers)
        isolatedDown = 0
        suspendedModifiers = emptyList()
    }

    /** Never leave a key stuck down if the display goes away or the game closes mid-press. */
    override fun onDetachedFromWindow() {
        releaseAllKeys()
        super.onDetachedFromWindow()
    }

    private fun orderedSlots(group: String, defaults: List<Slot>): List<Slot> {
        val byLabel = defaults.associateBy { it.label }
        return PadSettings.ordered(group, defaults.map { it.label }).mapNotNull { byLabel[it] }
    }

    companion object {
        const val GROUP_NUMBERS = "numbers"
        const val GROUP_EXTRAS = "extras"

        /** Every top-to-bottom order of the right column: N = numbers, M = modifier keys, E = Extra Buttons. */
        val COLUMN_ORDERS = listOf("NME", "NEM", "MNE", "ENM", "MEN", "EMN")

        /** [COLUMN_ORDERS] worded for the settings button. */
        val COLUMN_ORDER_NAMES: List<String> = COLUMN_ORDERS.map { order ->
            order.map { when (it) { 'N' -> "Numbers"; 'M' -> "Modifiers"; else -> "Extras" } }.joinToString(", ")
        }

        /** The page ids, in tab order: "interface", "target", "utility". */
        val PAGE_IDS: List<String> get() = PAGES.map { it.id }

        /** The page tab titles, in tab order. */
        val PAGE_TITLES: List<String> get() = PAGES.map { it.title }

        /** A group's name for the settings: a page's title, or the number block. */
        fun groupTitle(group: String): String = PAGES.firstOrNull { it.id == group }?.title?.let { "the $it page" } ?: if (group == GROUP_EXTRAS) "the extra buttons" else "the number block"

        /** The group a pad button belongs to (a page id or the number block), or null for a label that is not a pad button. */
        fun groupOf(label: String): String? =
            PAGES.firstOrNull { page -> page.slots.any { it.label == label } }?.id
                ?: GROUP_NUMBERS.takeIf { ACTION_SLOTS.any { it.label == label } }
                ?: GROUP_EXTRAS.takeIf { EXTRA_SLOTS.any { it.label == label } }

        /** A group's button labels in the default order. */
        fun defaultLabels(group: String): List<String> =
            (PAGES.firstOrNull { it.id == group }?.slots ?: if (group == GROUP_EXTRAS) EXTRA_SLOTS else ACTION_SLOTS).map { it.label }

        /** Every remappable pad button: its label and what it sends by default. */
        fun remappableButtons(): List<Pair<String, KeyCombo>> =
            (PAGES.flatMap { it.slots } + ACTION_SLOTS + EXTRA_SLOTS).map { it.label to KeyCombo(it.key, it.mods) }

        // Width split between the page area and the number/modifier block.
        // A smaller RIGHT_WEIGHT squeezes that block toward the right edge, within reach of a right thumb.
        private const val LEFT_WEIGHT = 1f
        private const val RIGHT_WEIGHT = 1f

        // Up to this many buttons on a page stay in one column; more split it in two, a little wider overall.
        private const val SINGLE_COLUMN_MAX = 8

        /** A page button. It sends its own modifiers and ignores any the user holds on the pad. */
        private fun page(label: String, key: XKeycode, vararg mods: XKeycode) = Slot(label, key, mods.toList(), isolated = true)

        private class Page(val id: String, val title: String, val slots: List<Slot>)

        // WoW's default bindings. A page holds at most 12 buttons.
        private val PAGES = listOf(
            Page(
                "interface", "Interface",
                listOf(
                    page("Character", XKeycode.KEY_C),
                    page("Spellbook", XKeycode.KEY_P),
                    page("Talents", XKeycode.KEY_N),
                    page("Quest Log", XKeycode.KEY_L),
                    page("Map", XKeycode.KEY_M),
                    page("Social", XKeycode.KEY_O),
                    page("Guild", XKeycode.KEY_J),
                    page("Group Finder", XKeycode.KEY_I),
                    page("Achievements", XKeycode.KEY_Y),
                    page("Professions", XKeycode.KEY_K),
                    page("Bags", XKeycode.KEY_B),
                    // Escape opens the game menu ("System") when nothing else is open.
                    page("System", XKeycode.KEY_ESC),
                ),
            ),
            Page(
                "target", "Target",
                listOf(
                    page("Self", XKeycode.KEY_F1),
                    page("Party 1", XKeycode.KEY_F2),
                    page("Party 2", XKeycode.KEY_F3),
                    page("Party 3", XKeycode.KEY_F4),
                    page("Party 4", XKeycode.KEY_F5),
                    page("Next enemy", XKeycode.KEY_TAB),
                    page("Previous enemy", XKeycode.KEY_TAB, XKeycode.KEY_SHIFT_L),
                ),
            ),
            Page(
                "utility", "Utility",
                listOf(
                    page("Sheathe weapons", XKeycode.KEY_Z),
                    page("Auto-run", XKeycode.KEY_NUM_LOCK),
                    page("Walk / run", XKeycode.KEY_KP_DIVIDE),
                    page("Sit / stand", XKeycode.KEY_X),
                    page("Hide UI", XKeycode.KEY_Z, XKeycode.KEY_ALT_L),
                    page("Enemy nameplates", XKeycode.KEY_V),
                    page("Friendly nameplates", XKeycode.KEY_V, XKeycode.KEY_SHIFT_L),
                    page("Chat page up", XKeycode.KEY_PRIOR),
                    page("Chat page down", XKeycode.KEY_NEXT),
                    page("Screenshot", XKeycode.KEY_PRTSCN),
                ),
            ),
        )

        // The Extra Buttons panel. They start on F8 to F12; remap them to whatever is needed.
        private val EXTRA_SLOTS = listOf(
            Slot("Extra 1", XKeycode.KEY_F8),
            Slot("Extra 2", XKeycode.KEY_F9),
            Slot("Extra 3", XKeycode.KEY_F10),
            Slot("Extra 4", XKeycode.KEY_F11),
            Slot("Extra 5", XKeycode.KEY_F12),
        )

        // WoW's default bindings for Action Bar 1, slots 1 to 12.
        private val ACTION_SLOTS = listOf(
            Slot("1", XKeycode.KEY_1),
            Slot("2", XKeycode.KEY_2),
            Slot("3", XKeycode.KEY_3),
            Slot("4", XKeycode.KEY_4),
            Slot("5", XKeycode.KEY_5),
            Slot("6", XKeycode.KEY_6),
            Slot("7", XKeycode.KEY_7),
            Slot("8", XKeycode.KEY_8),
            Slot("9", XKeycode.KEY_9),
            Slot("0", XKeycode.KEY_0),
            Slot("-", XKeycode.KEY_MINUS),
            Slot("=", XKeycode.KEY_EQUAL),
        )
    }
}
