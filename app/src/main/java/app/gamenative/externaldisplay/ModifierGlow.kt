package app.gamenative.externaldisplay

import android.graphics.Color
import android.widget.TextView

/** The glow behind a button's text while an armed modifier applies to it. A null [color] clears it. */
internal fun applyGlow(view: TextView, color: Int?, intensity: Int, density: Float) {
    if (color == null) {
        view.setShadowLayer(0f, 0f, 0f, 0)
        return
    }
    val level = intensity.coerceIn(10, 100) / 100f
    val alpha = (255 * (0.35f + 0.65f * level)).toInt()
    val radius = (3f + 11f * level) * density
    view.setShadowLayer(radius, 0f, 0f, Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color)))
}

/** The default glow, a warm gold-white. */
internal const val DEFAULT_GLOW = 0xFFFFE08A.toInt()

/** The average of several glow colors, for when more than one modifier is armed. */
internal fun mixGlow(colors: List<Int>): Int {
    if (colors.size == 1) return colors[0]
    return Color.rgb(
        colors.sumOf { Color.red(it) } / colors.size,
        colors.sumOf { Color.green(it) } / colors.size,
        colors.sumOf { Color.blue(it) } / colors.size,
    )
}
