package app.gamenative.externaldisplay

import android.content.Context
import android.content.SharedPreferences
import android.view.HapticFeedbackConstants
import android.view.View
import org.json.JSONObject

/**
 * User settings for the second-screen pad and the controller extras, kept in one small
 * SharedPreferences file. Every value is a Boolean or an Int (decimals are stored as tenths or
 * percent), so a whole set can be copied to a profile, exported or reset as plain JSON.
 *
 * Reads fall back to the defaults until [init] has run, so callers never need a null check.
 */
object PadSettings {
    private const val PREFS = "pad_settings"
    private const val REMAP = "remap"
    private const val RENAME = "rename"
    private const val COLOR = "color"
    private const val ORDER = "order"
    private const val HIDDEN = "hidden"
    private const val PROFILE_PREFIX = "profile_"
    const val PROFILE_SLOTS = 3

    // Key names.
    const val MOD_SHIFT = "mod_shift"
    const val MOD_CTRL = "mod_ctrl"
    const val MOD_ALT = "mod_alt"
    const val SEC_NUMBERS = "sec_numbers"
    const val SWAP_SIDES = "swap_sides"
    /** Puts the Settings, Trackpad and Keyboard buttons on the left of the tab bar and the page tabs on the right. */
    const val SWAP_TABS = "swap_tabs"
    /** Width of the page area against the number block, in percent of the default split. */
    const val PAGE_WIDTH = "page_width"
    /** Height of the modifier row, in percent of its default. */
    const val MOD_HEIGHT = "mod_height"
    /** Top-to-bottom order of the number block, modifier row and Extra Buttons: an index into the six orders. */
    const val COL_ORDER = "col_order"
    /** Armed modifiers make the buttons they affect glow behind the text. Separate from the button recolor settings. */
    const val MOD_GLOW = "mod_glow"
    /** How strong that glow is, 10 to 100. */
    const val MOD_GLOW_LEVEL = "mod_glow_level"
    /** Glow color per modifier key (ARGB). 0 = the default glow, and the modifier button keeps its normal trim. */
    const val MOD_GLOW_SHIFT = "mod_glow_shift"
    const val MOD_GLOW_CTRL = "mod_glow_ctrl"
    const val MOD_GLOW_ALT = "mod_glow_alt"
    /** Shows the modifier row on the pad. The trackpad keeps its own. */
    const val MOD_ON = "mod_on"
    /** How bold the number buttons' outline is: 0 matches the other buttons, 100 is the standard number look. */
    const val NUM_OUTLINE = "num_outline"
    /** How raised the number buttons' face is: 0 matches the other buttons, 100 is the standard number look. */
    const val NUM_FACE = "num_face"
    /** The optional "Extra Buttons" panel next to the number block. */
    const val EXTRA_ON = "extra_on"
    /** How many extra buttons it shows, 1 to 5. */
    const val EXTRA_N = "extra_n"
    /** Lets an armed Shift, Ctrl or Alt apply to the Extra Buttons, as it does to the number buttons. */
    const val EXTRA_MODS = "extra_mods"
    /** Height of the Extra Buttons panel, in percent of its default. */
    const val EXTRA_HEIGHT = "extra_height"
    /** 0 = tab bar at the top, 1 = at the bottom. */
    const val TAB_POS = "tab_pos"
    const val MUTED = "muted_borders"
    const val EMPHASIS = "number_emphasis"
    const val LABEL_SCALE = "label_scale"
    const val HAPTICS = "haptics"
    const val DOUBLE_TAP_MS = "double_tap_ms"
    const val TP_SPEED = "trackpad_speed"
    const val TP_ACCEL = "trackpad_accel"
    const val TP_TAP = "trackpad_tap"
    const val SCROLLBAR_ON = "scrollbar_on"
    const val SCROLLBAR_SIDE = "scrollbar_side"
    const val SCROLLBAR_SPEED = "scrollbar_speed"
    const val SCROLLBAR_INVERT = "scrollbar_invert"
    const val STICK_BASE = "stick_base"
    const val STICK_MAX = "stick_max"
    const val STICK_RAMP = "stick_ramp"
    const val STICK_DEADZONE = "stick_deadzone"
    const val AB_MODE = "ab_mode"
    const val R3_TOGGLE = "r3_toggle"
    const val SCROLL_KEYS = "scroll_keys"
    /** In cursor mode, double-tapping A or B holds that click until the button is pressed again. */
    const val CLICK_HOLD = "click_hold"
    /** How quickly the second A / B tap must follow the first to count as a double tap, in ms. */
    const val CLICK_HOLD_MS = "click_hold_ms"
    /** Short wait before a single click is sent, so a double tap can start a hold without a click first. 0 = off. */
    const val CLICK_WAIT_MS = "click_wait_ms"
    const val SCROLL_SPEED = "scroll_speed"
    const val SCROLL_INVERT = "scroll_invert"
    const val MODE_MESSAGE = "mode_message"

    /** Defaults. Decimals are stored as tenths (speeds) or percent (ramp, deadzone, emphasis). */
    private val DEFAULTS: Map<String, Any> = linkedMapOf<String, Any>(
        MOD_SHIFT to true,
        MOD_CTRL to true,
        MOD_ALT to true,
        SEC_NUMBERS to true,
        SWAP_SIDES to false,
        SWAP_TABS to false,
        PAGE_WIDTH to 100,
        MOD_HEIGHT to 100,
        COL_ORDER to 0,
        MOD_ON to true,
        MOD_GLOW to true,
        MOD_GLOW_LEVEL to 25,
        MOD_GLOW_SHIFT to 0,
        MOD_GLOW_CTRL to 0,
        MOD_GLOW_ALT to 0,
        NUM_OUTLINE to 100,
        NUM_FACE to 100,
        EXTRA_ON to false,
        EXTRA_N to 3,
        EXTRA_MODS to false,
        EXTRA_HEIGHT to 100,
        TAB_POS to 0,
        MUTED to 55,
        EMPHASIS to 100,
        LABEL_SCALE to 100,
        HAPTICS to 2,
        DOUBLE_TAP_MS to 350,
        TP_SPEED to 7,
        TP_ACCEL to 10,
        TP_TAP to true,
        SCROLLBAR_ON to true,
        SCROLLBAR_SIDE to 0,
        SCROLLBAR_SPEED to 5,
        SCROLLBAR_INVERT to false,
        STICK_BASE to 10,
        STICK_MAX to 25,
        STICK_RAMP to 90,
        STICK_DEADZONE to 15,
        AB_MODE to 1,
        R3_TOGGLE to true,
        SCROLL_KEYS to true,
        CLICK_HOLD to true,
        CLICK_HOLD_MS to 350,
        CLICK_WAIT_MS to 120,
        SCROLL_SPEED to 5,
        SCROLL_INVERT to false,
        MODE_MESSAGE to true,
    )

    private var prefs: SharedPreferences? = null

    /** Bumped on every change, so a screen can tell whether anything changed while it was away. */
    @Volatile
    var version = 0
        private set

    fun init(context: Context) {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun bool(key: String): Boolean {
        val def = DEFAULTS[key] as? Boolean ?: false
        return prefs?.getBoolean(key, def) ?: def
    }

    fun int(key: String): Int {
        val def = DEFAULTS[key] as? Int ?: 0
        return prefs?.getInt(key, def) ?: def
    }

    fun set(key: String, value: Boolean) {
        prefs?.edit()?.putBoolean(key, value)?.apply()
        version++
    }

    fun set(key: String, value: Int) {
        prefs?.edit()?.putInt(key, value)?.apply()
        version++
    }

    /** The chosen glow color for a modifier setting key, or null for the default. */
    fun glowColor(key: String): Int? = int(key).takeIf { it != 0 }

    // Convenient typed reads.
    val mutedAmount: Float get() = int(MUTED) / 100f
    val emphasis: Float get() = int(EMPHASIS) / 100f
    val labelScale: Float get() = int(LABEL_SCALE) / 100f
    val doubleTapMs: Long get() = int(DOUBLE_TAP_MS).toLong()
    val trackpadSensitivity: Float get() = int(TP_SPEED) / 10f
    val trackpadAcceleration: Float get() = int(TP_ACCEL) / 10f
    val stickBaseSpeed: Float get() = int(STICK_BASE) / 10f
    val stickMaxSpeed: Float get() = int(STICK_MAX) / 10f
    val stickRampStart: Float get() = int(STICK_RAMP) / 100f
    val stickDeadzone: Float get() = int(STICK_DEADZONE) / 100f

    /** Haptics level: 0 off, 1 light, 2 normal, 3 strong. */
    fun haptic(view: View) {
        when (int(HAPTICS)) {
            1 -> view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            2 -> view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            3 -> view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    // Button remapping: pad button label -> XKeycode name.

    private fun remapJson(): JSONObject =
        runCatching { JSONObject(prefs?.getString(REMAP, null) ?: "{}") }.getOrDefault(JSONObject())

    fun remapped(label: String): String? = remapJson().optString(label).takeIf { it.isNotEmpty() }

    fun setRemap(label: String, keyName: String?) {
        val json = remapJson()
        if (keyName == null) json.remove(label) else json.put(label, keyName)
        prefs?.edit()?.putString(REMAP, json.toString())?.apply()
        version++
    }

    fun clearRemaps() {
        prefs?.edit()?.remove(REMAP)?.apply()
        version++
    }

    // Button renaming: pad button label -> the text shown on it. The label stays the button's id.

    private fun renameJson(): JSONObject =
        runCatching { JSONObject(prefs?.getString(RENAME, null) ?: "{}") }.getOrDefault(JSONObject())

    fun renamed(label: String): String? = renameJson().optString(label).takeIf { it.isNotEmpty() }

    /** What the button shows: its custom name, or the original label. */
    fun displayName(label: String): String = renamed(label) ?: label

    fun setRename(label: String, name: String?) {
        val json = renameJson()
        if (name.isNullOrBlank()) json.remove(label) else json.put(label, name.trim())
        prefs?.edit()?.putString(RENAME, json.toString())?.apply()
        version++
    }

    fun clearRenames() {
        prefs?.edit()?.remove(RENAME)?.apply()
        version++
    }

    // Button colors: pad button label -> ARGB trim color. No entry means the theme's gold.

    private fun colorJson(): JSONObject =
        runCatching { JSONObject(prefs?.getString(COLOR, null) ?: "{}") }.getOrDefault(JSONObject())

    fun colorOf(label: String): Int? = colorJson().takeIf { it.has(label) }?.optInt(label)

    fun setColor(labels: List<String>, color: Int?) {
        val json = colorJson()
        labels.forEach { if (color == null) json.remove(it) else json.put(it, color) }
        prefs?.edit()?.putString(COLOR, json.toString())?.apply()
        version++
    }

    fun clearColors() {
        prefs?.edit()?.remove(COLOR)?.apply()
        version++
    }

    // Hidden buttons: label -> true. A hidden button is left off the pad.

    private fun hiddenJson(): JSONObject =
        runCatching { JSONObject(prefs?.getString(HIDDEN, null) ?: "{}") }.getOrDefault(JSONObject())

    fun isHidden(label: String): Boolean = hiddenJson().optBoolean(label, false)

    fun setHidden(label: String, hidden: Boolean) {
        val json = hiddenJson()
        if (hidden) json.put(label, true) else json.remove(label)
        prefs?.edit()?.putString(HIDDEN, json.toString())?.apply()
        version++
    }

    // Button order inside a group: group name -> labels in the order shown.

    private fun orderJson(): JSONObject =
        runCatching { JSONObject(prefs?.getString(ORDER, null) ?: "{}") }.getOrDefault(JSONObject())

    /** [defaults] in the user's saved order. Labels not in the saved list keep their default place after it. */
    fun ordered(group: String, defaults: List<String>): List<String> {
        val arr = orderJson().optJSONArray(group) ?: return defaults
        val saved = (0 until arr.length()).map { arr.optString(it) }.filter { it in defaults }.distinct()
        return saved + defaults.filter { it !in saved }
    }

    /** Moves [label] up (-1) or down (+1) inside its group. */
    fun move(group: String, defaults: List<String>, label: String, delta: Int) {
        val list = ordered(group, defaults).toMutableList()
        val from = list.indexOf(label)
        val to = from + delta
        if (from < 0 || to !in list.indices) return
        list.removeAt(from)
        list.add(to, label)
        val json = orderJson().put(group, org.json.JSONArray(list))
        prefs?.edit()?.putString(ORDER, json.toString())?.apply()
        version++
    }

    fun clearOrder() {
        prefs?.edit()?.remove(ORDER)?.apply()
        version++
    }

    // Layout export / import: just the button arrangement (keys, names, colors, order).

    fun layout(): JSONObject = JSONObject().apply {
        put(REMAP, remapJson())
        put(RENAME, renameJson())
        put(COLOR, colorJson())
        put(ORDER, orderJson())
        put(HIDDEN, hiddenJson())
    }

    /** Applies a [layout]. Returns false if it has none of the layout sections. */
    fun applyLayout(json: JSONObject): Boolean {
        val keys = listOf(REMAP, RENAME, COLOR, ORDER, HIDDEN)
        if (keys.none { json.optJSONObject(it) != null }) return false
        val editor = prefs?.edit() ?: return false
        keys.forEach { k -> editor.putString(k, (json.optJSONObject(k) ?: JSONObject()).toString()) }
        editor.apply()
        version++
        return true
    }

    // Reset, profiles, export and import.

    /** Settings the launcher sets per game at launch. Profiles and resets leave them alone during play. */
    private val LAUNCH_ONLY = setOf(R3_TOGGLE)

    fun resetAll() {
        prefs?.edit()?.apply {
            DEFAULTS.keys.filter { it !in LAUNCH_ONLY }.forEach { remove(it) }
            remove(REMAP)
            remove(RENAME)
            remove(COLOR)
            remove(ORDER)
            remove(HIDDEN)
        }?.apply()
        version++
    }

    /** Every setting and the remap list as one JSON object. */
    fun snapshot(): JSONObject = JSONObject().apply {
        DEFAULTS.keys.filter { it !in LAUNCH_ONLY }.forEach { key ->
            if (DEFAULTS[key] is Boolean) put(key, bool(key)) else put(key, int(key))
        }
        put(REMAP, remapJson())
        put(RENAME, renameJson())
        put(COLOR, colorJson())
        put(ORDER, orderJson())
        put(HIDDEN, hiddenJson())
    }

    /** Applies a [snapshot]. Unknown or mistyped entries are ignored. */
    fun applySnapshot(json: JSONObject) {
        val editor = prefs?.edit() ?: return
        DEFAULTS.forEach { (key, def) ->
            if (key in LAUNCH_ONLY || !json.has(key)) return@forEach
            if (def is Boolean) editor.putBoolean(key, json.optBoolean(key, def))
            else editor.putInt(key, json.optInt(key, def as Int))
        }
        json.optJSONObject(REMAP)?.let { editor.putString(REMAP, it.toString()) }
        json.optJSONObject(RENAME)?.let { editor.putString(RENAME, it.toString()) }
        json.optJSONObject(COLOR)?.let { editor.putString(COLOR, it.toString()) }
        json.optJSONObject(ORDER)?.let { editor.putString(ORDER, it.toString()) }
        json.optJSONObject(HIDDEN)?.let { editor.putString(HIDDEN, it.toString()) }
        editor.apply()
        version++
    }

    fun saveProfile(slot: Int) {
        prefs?.edit()?.putString(PROFILE_PREFIX + slot, snapshot().toString())?.apply()
    }

    /** Which saved profile a game loads at launch: 0 = none (keep the pad as it is), else a slot. Kept per game, outside profiles. */
    fun launchProfile(game: String): Int = prefs?.getInt("launch_profile_$game", 0) ?: 0

    fun setLaunchProfile(game: String, slot: Int) {
        prefs?.edit()?.putInt("launch_profile_$game", slot)?.apply()
    }

    fun hasProfile(slot: Int): Boolean = prefs?.contains(PROFILE_PREFIX + slot) == true

    fun loadProfile(slot: Int): Boolean {
        val text = prefs?.getString(PROFILE_PREFIX + slot, null) ?: return false
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return false
        applySnapshot(json)
        return true
    }
}
