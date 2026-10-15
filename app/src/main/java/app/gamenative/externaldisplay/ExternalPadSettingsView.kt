package app.gamenative.externaldisplay

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import app.gamenative.ui.screen.wow.GamePath
import app.gamenative.ui.screen.wow.WowFlavor
import com.winlator.xserver.XKeycode
import org.json.JSONObject
import java.io.File

/**
 * The settings screen of the second-screen pad. Everything is written to [PadSettings] as soon as it
 * changes. Layout and look changes show up on the pad itself when this screen is closed.
 *
 * Built in code like the rest of the pad, with the same gold-on-dark look.
 */
class ExternalPadSettingsView(
    context: Context,
    private val theme: PadTheme,
) : ScrollView(context) {

    /** Set by the pad: shows the on-screen keyboard to type text (prompt, starting text, max length, result). */
    var onRequestText: ((String, String, Int, (String) -> Unit) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    init {
        setBackgroundColor(theme.background)
        isFillViewport = false
        val side = dp(8)
        content.setPadding(side, dp(4), side, side)
        addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        showMain()
    }

    // Screens

    private fun showMain() {
        content.removeAllViews()
        scrollTo(0, 0)

        section("Pad layout", collapsible = true) {
            addView(cycleRow("Tab bar", PadSettings.TAB_POS, listOf("Top", "Bottom")))
            addView(toggleRow(listOf("Swap sides (pages on the right)" to PadSettings.SWAP_SIDES)))
            addView(toggleRow(listOf("Swap sides (tabs on the right)" to PadSettings.SWAP_TABS)))
            addView(sliderRow("Page area width", PadSettings.PAGE_WIDTH, 60, 160) { "$it%" })
            addView(note("Order of the right-hand column, top to bottom:"))
            addView(cycleRow("Order", PadSettings.COL_ORDER, ExternalActionBarView.COLUMN_ORDER_NAMES))
        }
        section("Number block", collapsible = true) {
            addView(toggleRow(listOf("Show 1-9 0 - =" to PadSettings.SEC_NUMBERS)))
            addView(sliderRow("Number emphasis", PadSettings.EMPHASIS, 0, 200) { "$it%" })
            addView(note("Number buttons: 0% looks like the other buttons, 100% is the standard bold number look."))
            addView(sliderRow("Outline", PadSettings.NUM_OUTLINE, 0, 100) { "$it%" })
            addView(sliderRow("Face", PadSettings.NUM_FACE, 0, 100) { "$it%" })
        }
        section("Modifier keys", collapsible = true) {
            addView(toggleRow(listOf("Show modifier row on the pad" to PadSettings.MOD_ON)))
            val orders = listOf("Shift, Ctrl, Alt", "Shift, Alt, Ctrl", "Ctrl, Shift, Alt", "Ctrl, Alt, Shift", "Alt, Shift, Ctrl", "Alt, Ctrl, Shift")
            addView(cycleRow("Pad order", PadSettings.MOD_ORDER, orders))
            addView(cycleRow("Trackpad order", PadSettings.MOD_ORDER_TP, orders))
            addView(note("Keys shown in the pad row (the trackpad always has all three):"))
            addView(toggleRow(listOf("Shift" to PadSettings.MOD_SHIFT, "Ctrl" to PadSettings.MOD_CTRL, "Alt" to PadSettings.MOD_ALT)))
            addView(sliderRow("Row height", PadSettings.MOD_HEIGHT, 50, 200) { "$it%" })
            addView(toggleRow(listOf("Glow on buttons a modifier affects" to PadSettings.MOD_GLOW)))
            addView(sliderRow("Glow intensity", PadSettings.MOD_GLOW_LEVEL, 10, 100) { "$it%" })
            addView(note("Glow color for each modifier. A color also becomes that modifier button's color. Default keeps the usual look."))
            listOf("Shift" to PadSettings.MOD_GLOW_SHIFT, "Ctrl" to PadSettings.MOD_GLOW_CTRL, "Alt" to PadSettings.MOD_GLOW_ALT)
                .forEach { (name, key) -> addView(glowColorRow(name, key)) }
            addView(sliderRow("Double-tap lock", PadSettings.DOUBLE_TAP_MS, 200, 600) { "$it ms" })
        }
        section("Extra Buttons", collapsible = true) {
            addView(toggleRow(listOf("Show Extra Buttons panel" to PadSettings.EXTRA_ON)))
            addView(toggleRow(listOf("Modifier keys affect Extra Buttons" to PadSettings.EXTRA_MODS)))
            addView(sliderRow("Buttons shown", PadSettings.EXTRA_N, 1, 5) { "$it" })
            addView(sliderRow("Panel height", PadSettings.EXTRA_HEIGHT, 50, 250) { "$it%" })
            addView(note("Ex1 to Ex5 start on F8 to F12. Remap them under \"Remap, rename, and recolor buttons\"."))
        }
        section("Buttons shown", collapsible = true) {
            addView(note("Tap a name to hide or show that button. Use the arrows to move it up or down inside its group. Hidden numbers leave a gap."))
            (ExternalActionBarView.PAGE_IDS + ExternalActionBarView.GROUP_EXTRAS + ExternalActionBarView.GROUP_NUMBERS).forEach { group ->
                addView(note(ExternalActionBarView.groupTitle(group).replaceFirstChar { it.uppercase() }))
                val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                addView(list)
                fillButtonList(list, group)
            }
            addView(rowOf(button("Reset button order") {
                PadSettings.clearOrder()
                showMain()
            }, filler = 2))
        }
        section("Look and feel", collapsible = true) {
            addView(sliderRow("Muted borders", PadSettings.MUTED, 0, 100) { "$it" })
            addView(sliderRow("Backing panels", PadSettings.PANEL_LEVEL, 0, 200) { "$it%" })
            addView(sliderRow("Label size", PadSettings.LABEL_SCALE, 80, 140) { "$it%" })
            addView(cycleRow("Haptics", PadSettings.HAPTICS, listOf("Off", "Light", "Normal", "Strong")))
        }
        section("Keyboard", collapsible = true) {
            addView(toggleRow(listOf("Pop-up when a key is pressed" to PadSettings.KB_POPUP)))
            addView(sliderRow("Key height", PadSettings.KB_HEIGHT, 70, 110) { "$it%" })
            addView(note("100% is the standard key height. The keyboard takes effect the next time it opens."))
        }
        section("Trackpad", collapsible = true) {
            addView(sliderRow("Speed", PadSettings.TP_SPEED, 1, 20) { tenths(it) })
            addView(sliderRow("Acceleration", PadSettings.TP_ACCEL, 10, 30) { tenths(it) })
            addView(toggleRow(listOf("Tap to click" to PadSettings.TP_TAP)))
            addView(sliderRow("Click wait for double tap", PadSettings.CLICK_WAIT_MS, 0, 300) { if (it == 0) "Off" else "$it ms" })
            addView(toggleRow(listOf("Scroll area" to PadSettings.SCROLLBAR_ON, "Invert scroll" to PadSettings.SCROLLBAR_INVERT)))
            addView(cycleRow("Scroll area side", PadSettings.SCROLLBAR_SIDE, listOf("Right", "Left")))
            addView(sliderRow("Scroll area speed", PadSettings.SCROLLBAR_SPEED, 1, 10) { "$it" })
        }
        // Only shown when R3 mouse/camera toggling was switched on at launch (main screen).
        if (PadSettings.bool(PadSettings.R3_TOGGLE)) {
            section("Right-stick cursor (R3)", collapsible = true) {
                addView(sliderRow("Base speed", PadSettings.STICK_BASE, 5, 30) { tenths(it) })
                addView(sliderRow("Max speed", PadSettings.STICK_MAX, 10, 50) { tenths(it) })
                addView(sliderRow("Ramp starts at", PadSettings.STICK_RAMP, 50, 99) { "$it%" })
                addView(sliderRow("Stick deadzone", PadSettings.STICK_DEADZONE, 5, 40) { "$it%" })
                addView(cycleRow("A / B in cursor mode", PadSettings.AB_MODE, listOf("Off", "A left, B right", "A right, B left")))
                addView(toggleRow(listOf("Double-tap A / B holds the click" to PadSettings.CLICK_HOLD)))
                addView(sliderRow("Double-click speed", PadSettings.CLICK_HOLD_MS, 150, 600) { "$it ms" })
                addView(toggleRow(listOf("Y / X scroll in cursor mode" to PadSettings.SCROLL_KEYS, "Invert Y / X scroll" to PadSettings.SCROLL_INVERT)))
                addView(sliderRow("Scroll speed", PadSettings.SCROLL_SPEED, 1, 10) { "$it" })
                addView(toggleRow(listOf("D-pad moves the cursor too" to PadSettings.DPAD_CURSOR)))
                addView(sliderRow("D-pad base speed", PadSettings.DPAD_BASE, 5, 30) { tenths(it) })
                addView(sliderRow("D-pad max speed", PadSettings.DPAD_MAX, 10, 50) { tenths(it) })
                addView(sliderRow("D-pad ramp time", PadSettings.DPAD_RAMP, 1, 20) { tenths(it) + " s" })
                addView(toggleRow(listOf("Show mode message" to PadSettings.MODE_MESSAGE)))
            }
        }
        section("Remap, rename, and recolor buttons", collapsible = true) {
            addView(rowOf(button("Choose a button to rename, remap, or recolor") { showRemap() }))
        }
        section("Profiles", collapsible = true) {
            val status = note("")
            addView(launchProfileRow())
            for (slot in 1..PadSettings.PROFILE_SLOTS) {
                addView(profileRow(slot, status))
            }
            addView(status)
        }
        section("Backup", collapsible = true) {
            val status = note("")
            addView(
                rowOf(
                    button("Copy settings") {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("pad settings", PadSettings.snapshot().toString()))
                        status.text = "Settings copied to the clipboard"
                    },
                    button("Paste settings") {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
                        val json = text?.let { runCatching { JSONObject(it) }.getOrNull() }
                        if (json == null) {
                            status.text = "The clipboard has no copied settings"
                        } else {
                            PadSettings.applySnapshot(json)
                            showMain()
                        }
                    },
                ),
            )
            addView(
                rowOf(
                    button("Export to file") {
                        status.text = runCatching {
                            val file = backupFile()
                            file.writeText(PadSettings.fullBackup().toString(2))
                            "Saved ${file.name} (settings and all profiles) in your WoW folder"
                        }.getOrElse { "Could not save the backup: ${it.message}" }
                    },
                    button("Import from file") {
                        val file = backupFile()
                        val json = runCatching { JSONObject(file.readText()) }.getOrNull()
                        if (json == null || !PadSettings.restoreFullBackup(json)) {
                            status.text = "No backup found. Looking for ${file.name} in your WoW folder"
                        } else {
                            showMain()
                        }
                    },
                ),
            )
            addView(note("Export saves the current settings, the button layout and all profile slots to one file in your WoW folder. Keep that folder outside the app's own storage so it survives a reinstall."))
            addView(status)
        }
        section("Reset", collapsible = true) {
            addView(
                rowOf(
                    button("Reset to defaults") {
                        PadSettings.resetAll()
                        showMain()
                    },
                ),
            )
        }
    }

    /** A modifier's name and a row of glow color choices. Default keeps the standard glow and button color. */
    private fun glowColorRow(name: String, key: String): LinearLayout {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun fill() {
            row.removeAllViews()
            row.addView(label(name, 44))
            COLOR_CHOICES.forEach { (_, color) ->
                row.addView(swatch(color, selected = PadSettings.glowColor(key) == color) { PadSettings.set(key, color); fill() })
            }
            row.addView(swatch(null, selected = PadSettings.glowColor(key) == null) { PadSettings.set(key, 0); fill() })
        }
        fill()
        return row
    }

    /** One row per button of [group], in its current order: a show/hide toggle and Up / Down arrows. Rebuilds itself on a move. */
    private fun fillButtonList(list: LinearLayout, group: String) {
        list.removeAllViews()
        val defaults = ExternalActionBarView.defaultLabels(group)
        val labels = PadSettings.ordered(group, defaults)
        fun arrow(text: String, delta: Int) = button(text) {
            PadSettings.move(group, defaults, it.tag as String, delta)
            fillButtonList(list, group)
        }.apply { layoutParams = LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) } }
        labels.forEach { label ->
            val toggle = button(PadSettings.displayName(label), active = !PadSettings.isHidden(label)) { b ->
                val show = PadSettings.isHidden(label)
                PadSettings.setHidden(label, !show)
                style(b, show)
            }
            val up = arrow("\u25B2", -1).also { it.tag = label }
            val down = arrow("\u25BC", 1).also { it.tag = label }
            list.addView(rowOf(toggle, up, down))
        }
    }

    private fun showRemap() {
        content.removeAllViews()
        scrollTo(0, 0)
        section("Remap, rename, and recolor buttons") {
            addView(note("Tap a pad button, then choose the key it should send, give it a new name, or change its color."))
            addView(
                rowOf(
                    button("Back") { showMain() },
                    button("Reset all remaps") {
                        PadSettings.clearRemaps()
                        showRemap()
                    },
                    button("Reset all names") {
                        PadSettings.clearRenames()
                        showRemap()
                    },
                ),
            )
            addView(
                rowOf(
                    button("Reset all colors") {
                        PadSettings.clearColors()
                        showRemap()
                    },
                    filler = 2,
                ),
            )
        }
        val buttons = ExternalActionBarView.remappableButtons()
        section("Pad buttons") {
            buttons.chunked(4).forEach { chunk ->
                addView(
                    rowOf(
                        *chunk.map { (label, default) ->
                            val mapped = PadSettings.remapped(label)
                            val shown = PadSettings.displayName(label)
                            button(if (mapped == null) shown else "$shown → ${comboLabel(mapped)}", active = mapped != null || PadSettings.renamed(label) != null || PadSettings.colorOf(label) != null) {
                                showKeyPicker(label, default)
                            }
                        }.toTypedArray(),
                        filler = 4 - chunk.size,
                    ),
                )
            }
        }
    }

    /** Shift, Ctrl and Alt picked for the key about to be chosen; starts from the button's current remap. */
    private val pickerMods = mutableSetOf<String>()

    private fun showKeyPicker(label: String, default: KeyCombo, keepMods: Boolean = false) {
        if (!keepMods) {
            pickerMods.clear()
            pickerMods.addAll(KeyCombo.modNames(PadSettings.remapped(label)))
        }
        content.removeAllViews()
        scrollTo(0, 0)
        section("Key for \"${PadSettings.displayName(label)}\"") {
            addView(
                rowOf(
                    button("Back") { showRemap() },
                    button("Rename", active = PadSettings.renamed(label) != null) {
                        onRequestText?.invoke("Name for \"$label\"", PadSettings.renamed(label) ?: "", MAX_NAME) { text ->
                            PadSettings.setRename(label, text)
                            showKeyPicker(label, default)
                        }
                    },
                    button("Original name") {
                        PadSettings.setRename(label, null)
                        showKeyPicker(label, default)
                    },
                    button("Default (${comboLabel(defaultValue(default))})") {
                        PadSettings.setRemap(label, null)
                        showRemap()
                    },
                ),
            )
        }
        section("Color") {
            val group = ExternalActionBarView.groupOf(label)
            addView(
                rowOf(
                    *COLOR_CHOICES.map { (_, color) ->
                        swatch(color, selected = PadSettings.colorOf(label) == color) {
                            PadSettings.setColor(listOf(label), color)
                            showKeyPicker(label, default)
                        }
                    }.toTypedArray(),
                    swatch(null, selected = PadSettings.colorOf(label) == null) {
                        PadSettings.setColor(listOf(label), null)
                        showKeyPicker(label, default)
                    },
                ),
            )
            if (group != null) {
                addView(
                    rowOf(
                        button("Apply to whole group") {
                            PadSettings.setColor(ExternalActionBarView.defaultLabels(group), PadSettings.colorOf(label))
                            showKeyPicker(label, default)
                        },
                    ),
                )
            }
        }
        section("Keys") {
            addView(note("Optional: hold Shift, Ctrl or Alt with the key. Pick them first, then the key."))
            addView(
                rowOf(
                    *KeyCombo.MODIFIERS.map { (name, _) ->
                        button(name.lowercase().replaceFirstChar { it.uppercase() }, active = name in pickerMods) {
                            if (!pickerMods.add(name)) pickerMods.remove(name)
                            showKeyPicker(label, default, keepMods = true)
                        }
                    }.toTypedArray(),
                    filler = 3,
                ),
            )
            val current = PadSettings.remapped(label)
            PICKER_KEYS.chunked(6).forEach { chunk ->
                addView(
                    rowOf(
                        *chunk.map { key ->
                            button(keyName(key.name), active = current != null && KeyCombo.keyName(current) == key.name) {
                                PadSettings.setRemap(label, KeyCombo.encode(key.name, pickerMods))
                                showRemap()
                            }
                        }.toTypedArray(),
                        filler = 6 - chunk.size,
                    ),
                )
            }
        }
    }

    /** A color square for the picker; null is the default gold. A white ring marks the current choice. */
    private fun swatch(color: Int?, selected: Boolean, onClick: () -> Unit): Button = Button(context).apply {
        text = if (color == null) "Default" else ""
        setAllCaps(false)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        setTextColor(theme.text)
        typeface = theme.typeface
        minHeight = dp(36)
        minimumHeight = dp(36)
        setPadding(0, 0, 0, 0)
        background = GradientDrawable().apply {
            cornerRadius = 8 * density
            setColor(color ?: 0xFF28201A.toInt())
            setStroke((if (selected) 3 else 1) * density.toInt().coerceAtLeast(1), if (selected) 0xFFFFFFFF.toInt() else theme.border)
        }
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }
        setOnClickListener {
            PadSettings.haptic(this)
            onClick()
        }
    }

    // Building blocks

    private fun dp(value: Int): Int = (value * density).toInt()

    private fun section(title: String, collapsible: Boolean = false, build: LinearLayout.() -> Unit) {
        val group = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = theme.groupBackground(density, strong = false)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, 0, 0, dp(6)) }
        }
        val header = TextView(context).apply {
            text = if (collapsible) "\u25B8  ${title.uppercase()}" else title.uppercase()
            setTextColor(theme.border)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (collapsible) 13f else 11f)
            typeface = theme.typeface
            setPadding(dp(8), dp(if (collapsible) 8 else 2), dp(8), dp(if (collapsible) 8 else 2))
        }
        group.addView(header)
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        group.addView(body)
        body.build()
        if (collapsible) {
            // Closed by default; tap the title to open or close.
            body.visibility = View.GONE
            header.setOnClickListener {
                val open = body.visibility != View.VISIBLE
                body.visibility = if (open) View.VISIBLE else View.GONE
                header.text = (if (open) "\u25BE  " else "\u25B8  ") + title.uppercase()
            }
        }
        content.addView(group)
    }

    private fun note(text: String) = TextView(context).apply {
        this.text = text
        setTextColor(theme.text)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        typeface = theme.typeface
        setPadding(dp(8), dp(2), dp(8), dp(2))
    }

    private fun label(text: String, widthDp: Int = 120) = TextView(context).apply {
        this.text = text
        setTextColor(theme.text)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        typeface = theme.typeface
        layoutParams = LinearLayout.LayoutParams(dp(widthDp), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun style(button: Button, active: Boolean) {
        button.background = theme.buttonBackground(density, 8f, active, muted = true)
        button.setTextColor(if (active) theme.textPressed else theme.text)
    }

    private fun button(text: String, active: Boolean = false, onClick: (Button) -> Unit): Button = Button(context).apply {
        this.text = text
        setAllCaps(false)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        typeface = theme.typeface
        minHeight = dp(36)
        minimumHeight = dp(36)
        setPadding(dp(6), dp(2), dp(6), dp(2))
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }
        style(this, active)
        setOnClickListener {
            PadSettings.haptic(this)
            onClick(this)
        }
    }

    /** A row of equal-width views. [filler] adds empty cells so a short last row keeps the grid widths. */
    private fun rowOf(vararg views: View, filler: Int = 0): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it) }
        repeat(filler) {
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
        }
    }

    private fun toggleRow(items: List<Pair<String, String>>): LinearLayout = rowOf(
        *items.map { (text, key) ->
            button(text, active = PadSettings.bool(key)) { b ->
                val on = !PadSettings.bool(key)
                PadSettings.set(key, on)
                style(b, on)
            }
        }.toTypedArray(),
    )

    private fun cycleRow(title: String, key: String, options: List<String>): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), 0, 0, 0)
        addView(label(title))
        addView(
            button(options[PadSettings.int(key).coerceIn(0, options.lastIndex)]) { b ->
                val next = (PadSettings.int(key) + 1) % options.size
                PadSettings.set(key, next)
                b.text = options[next]
            },
        )
    }

    private fun sliderRow(title: String, key: String, min: Int, max: Int, format: (Int) -> String): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
            addView(label(title))
            val valueText = TextView(context).apply {
                setTextColor(theme.text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = theme.typeface
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val current = PadSettings.int(key).coerceIn(min, max)
            valueText.text = format(current)
            val bar = SeekBar(context).apply {
                this.max = max - min
                progress = current - min
                progressTintList = ColorStateList.valueOf(theme.border)
                thumbTintList = ColorStateList.valueOf(theme.borderBright)
                layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                        valueText.text = format(min + progress)
                        if (fromUser) PadSettings.set(key, min + progress)
                    }

                    override fun onStartTrackingTouch(bar: SeekBar?) {}
                    override fun onStopTrackingTouch(bar: SeekBar?) {}
                })
            }
            addView(bar)
            addView(valueText)
        }

    /** Picks the profile this game loads at launch (None keeps the pad as it is). */
    private fun launchProfileRow(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), 0, 0, 0)
        val game = WowFlavor.current
        fun text(slot: Int) = if (slot == 0) "None" else "Profile $slot"
        addView(label("Loads at launch for ${game.label}", widthDp = 150))
        addView(
            button(text(PadSettings.launchProfile(game.name))) { b ->
                val next = (PadSettings.launchProfile(game.name) + 1) % (PadSettings.PROFILE_SLOTS + 1)
                PadSettings.setLaunchProfile(game.name, next)
                b.text = text(next)
            },
        )
    }

    private fun profileRow(slot: Int, status: TextView): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), 0, 0, 0)
        val name = label("", widthDp = 150)
        addView(name)
        lateinit var saveButton: Button
        // "Save" for an empty slot, "Overwrite" once a profile is stored there.
        fun refresh() {
            val used = PadSettings.hasProfile(slot)
            name.text = if (used) "Profile $slot (saved)" else "Profile $slot (empty)"
            saveButton.text = if (used) "Overwrite" else "Save"
        }
        saveButton = button("Save") {
            val replaced = PadSettings.hasProfile(slot)
            PadSettings.saveProfile(slot)
            status.text = if (replaced) "Profile $slot overwritten" else "Saved to profile $slot"
            refresh()
        }
        addView(saveButton)
        addView(
            button("Load") {
                if (PadSettings.loadProfile(slot)) showMain() else status.text = "Profile $slot is empty"
            },
        )
        refresh()
    }

    /** The layout file sits next to the game data, so it survives reinstalling the app. */
    private fun backupFile() = File(File(GamePath.load(context)), "WoWPad-backup.json")

    // Helpers

    private fun tenths(value: Int) = "${value / 10}.${value % 10}"

    /** A saved key or combo as shown on a button, e.g. "Shift+V". */
    private fun comboLabel(value: String): String =
        (KeyCombo.modNames(value).toList().sortedBy { n -> KeyCombo.MODIFIERS.indexOfFirst { it.first == n } }
            .map { it.lowercase().replaceFirstChar { c -> c.uppercase() } } + keyName(KeyCombo.keyName(value))).joinToString("+")

    private fun defaultValue(combo: KeyCombo): String =
        KeyCombo.encode(combo.key.name, combo.mods.mapNotNull { m -> KeyCombo.MODIFIERS.firstOrNull { it.second == m }?.first }.toSet())

    private fun keyName(enumName: String): String = when (val n = enumName.removePrefix("KEY_")) {
        "PRIOR" -> "PgUp"
        "NEXT" -> "PgDn"
        "BKSP" -> "Bksp"
        "BRACKET_LEFT" -> "["
        "BRACKET_RIGHT" -> "]"
        "SEMICOLON" -> ";"
        "APOSTROPHE" -> "'"
        "COMMA" -> ","
        "PERIOD" -> "."
        "SLASH" -> "/"
        "BACKSLASH" -> "\\"
        "GRAVE" -> "`"
        "MINUS" -> "-"
        "EQUAL" -> "="
        else -> n.lowercase().replaceFirstChar { it.uppercase() }.takeIf { n.length > 1 } ?: n
    }

    private companion object {
        private const val MAX_NAME = 12

        /** Trim colors offered per button; "Default" keeps the gold. */
        private val COLOR_CHOICES = listOf(
            "Red" to 0xFFE53935.toInt(),
            "Orange" to 0xFFFB8C00.toInt(),
            "Yellow" to 0xFFFDD835.toInt(),
            "Green" to 0xFF43A047.toInt(),
            "Blue" to 0xFF1E88E5.toInt(),
            "Purple" to 0xFF8E24AA.toInt(),
            "White" to 0xFFEEEEEE.toInt(),
        )

        val PICKER_KEYS: List<XKeycode> = buildList {
            val names = ('A'..'Z').map { "KEY_$it" } + (0..9).map { "KEY_$it" } + (1..12).map { "KEY_F$it" } +
                listOf(
                    "KEY_ESC", "KEY_TAB", "KEY_SPACE", "KEY_ENTER", "KEY_BKSP", "KEY_DEL", "KEY_INSERT", "KEY_HOME",
                    "KEY_END", "KEY_PRIOR", "KEY_NEXT", "KEY_UP", "KEY_DOWN", "KEY_LEFT", "KEY_RIGHT", "KEY_MINUS",
                    "KEY_EQUAL", "KEY_BRACKET_LEFT", "KEY_BRACKET_RIGHT", "KEY_SEMICOLON", "KEY_APOSTROPHE", "KEY_COMMA",
                    "KEY_PERIOD", "KEY_SLASH", "KEY_BACKSLASH", "KEY_GRAVE",
                )
            names.forEach { name -> runCatching { XKeycode.valueOf(name) }.getOrNull()?.let { add(it) } }
        }
    }
}
