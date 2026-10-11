package app.gamenative.externaldisplay

import android.graphics.Color
import android.widget.TextView

/** The glow behind a button's text while an armed modifier applies to it. A null [color] clears it. */
internal fun applyGlow(view: TextView, color: Int?, intensity: Int, density: Float) {
    if (color == null) {
        view.setShadowLayer(0f, 0f, 0f, 0)
        (view as? GlowTextView)?.glowPasses = 1
        return
    }
    val percent = intensity.coerceIn(10, 100)
    val level = percent / 100f
    val alpha = (255 * (0.45f + 0.55f * level)).toInt()
    val radius = (4f + 18f * level) * density
    // Android draws one shadow per pass; extra passes stack them, so a high setting is much stronger.
    (view as? GlowTextView)?.glowPasses = if (percent >= 75) 3 else if (percent >= 35) 2 else 1
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

/** A text view that can draw its text, and so its glow, more than once for a stronger halo. */
internal class GlowTextView(context: android.content.Context) : TextView(context) {
    var glowPasses = 1
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    override fun onDraw(canvas: android.graphics.Canvas) {
        repeat(glowPasses.coerceAtLeast(1)) { super.onDraw(canvas) }
    }
}
