package app.gamenative.externaldisplay

import com.winlator.xserver.XKeycode

/**
 * What a pad button sends: one key, optionally with Shift, Ctrl and/or Alt held around it.
 *
 * Saved as text: the modifier names in Shift, Ctrl, Alt order, then the key, joined with "+"
 * (for example "SHIFT+KEY_V"). A plain key name such as "KEY_V" is a combo with no modifiers, so
 * remaps saved before combos existed keep working.
 */
data class KeyCombo(val key: XKeycode, val mods: List<XKeycode> = emptyList()) {
    companion object {
        /** Modifier names as saved, with the key each one presses. */
        val MODIFIERS: List<Pair<String, XKeycode>> = listOf(
            "SHIFT" to XKeycode.KEY_SHIFT_L,
            "CTRL" to XKeycode.KEY_CTRL_L,
            "ALT" to XKeycode.KEY_ALT_L,
        )

        fun parse(value: String): KeyCombo? {
            val parts = value.split('+')
            val key = runCatching { XKeycode.valueOf(parts.last()) }.getOrNull() ?: return null
            val mods = parts.dropLast(1).mapNotNull { name -> MODIFIERS.firstOrNull { it.first == name }?.second }
            return KeyCombo(key, mods)
        }

        /** The saved text for [keyName] with the modifiers named in [mods] ("SHIFT", "CTRL", "ALT"). */
        fun encode(keyName: String, mods: Set<String>): String =
            (MODIFIERS.map { it.first }.filter { it in mods } + keyName).joinToString("+")

        /** The modifier names in a saved value, for showing the current choice. */
        fun modNames(value: String?): Set<String> =
            value?.split('+')?.dropLast(1)?.filter { n -> MODIFIERS.any { it.first == n } }?.toSet() ?: emptySet()

        /** The key part of a saved value ("KEY_V"), without any modifiers. */
        fun keyName(value: String): String = value.substringAfterLast('+')
    }
}
