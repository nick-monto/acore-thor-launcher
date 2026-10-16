package app.gamenative.externaldisplay

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * Line icons for the Chat Buttons panel, drawn in code so they match the pad's gold look on any device.
 * A button shows its icon until it is renamed, and keeps it when only its key is remapped.
 */
internal class ChatIconDrawable(
    private val kind: String,
    private val normalColor: Int,
    private val pressedColor: Int,
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = normalColor
    }

    override fun isStateful() = true

    override fun onStateChange(state: IntArray): Boolean {
        val pressed = state.contains(android.R.attr.state_pressed)
        val wanted = if (pressed) pressedColor else normalColor
        if (paint.color == wanted) return false
        paint.color = wanted
        invalidateSelf()
        return true
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        // The icon is drawn on a 24 x 24 grid, centred and about half the button's short side.
        val size = (minOf(b.width(), b.height()) * 0.55f).coerceAtLeast(1f)
        val scale = size / 24f
        paint.strokeWidth = 2.4f * scale
        canvas.save()
        canvas.translate(b.exactCenterX() - size / 2f, b.exactCenterY() - size / 2f)
        canvas.scale(scale, scale)
        // Strokes are scaled with the canvas, so the width above is in grid units.
        paint.strokeWidth = 2.4f
        val path = Path()
        when (kind) {
            CHAT -> {
                path.moveTo(4f, 5f); path.lineTo(20f, 5f); path.lineTo(20f, 16f); path.lineTo(11f, 16f)
                path.lineTo(7f, 20f); path.lineTo(7f, 16f); path.lineTo(4f, 16f); path.close()
                path.moveTo(8f, 9.5f); path.lineTo(16f, 9.5f)
                path.moveTo(8f, 12.5f); path.lineTo(13f, 12.5f)
            }
            SLASH -> { path.moveTo(16f, 4f); path.lineTo(8f, 20f) }
            REPLY -> {
                path.moveTo(10f, 6f); path.lineTo(4f, 12f); path.lineTo(10f, 18f)
                path.moveTo(4f, 12f); path.lineTo(14f, 12f)
                path.arcTo(RectF(8f, 12f, 20f, 24f), 270f, 90f)
            }
            CLOSE -> {
                path.moveTo(6f, 6f); path.lineTo(18f, 18f)
                path.moveTo(18f, 6f); path.lineTo(6f, 18f)
            }
        }
        canvas.drawPath(path, paint)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Required by Drawable")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        const val CHAT = "Chat"
        const val SLASH = "Slash"
        const val REPLY = "Reply"
        const val CLOSE = "Close"
        fun has(label: String) = label == CHAT || label == SLASH || label == REPLY || label == CLOSE
    }
}
