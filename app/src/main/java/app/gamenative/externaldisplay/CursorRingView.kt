package app.gamenative.externaldisplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import com.winlator.xserver.XServer

/**
 * A transparent layer over the game that draws a ring around the pointer while the right stick is the
 * cursor (R3 cursor mode). It is an ordinary Android view, so it works with every renderer, and it never
 * takes touches. [gameView] is the view the game is drawn in; the ring is placed over the pointer's spot in it.
 */
class CursorRingView(
    context: Context,
    private val xServer: XServer,
    private val gameView: () -> View?,
) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val loc = IntArray(2)
    private val gameLoc = IntArray(2)
    private var drawn = false
    private var attached = false

    private val tick = object : Runnable {
        override fun run() {
            if (!attached) return
            val show = PadBridge.isCursorRingShown()
            if (show || drawn) invalidate()
            postOnAnimation(this)
        }
    }

    init {
        isClickable = false
        isFocusable = false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        postOnAnimation(tick)
    }

    override fun onDetachedFromWindow() {
        attached = false
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    override fun onTouchEvent(event: android.view.MotionEvent?) = false

    override fun onDraw(canvas: Canvas) {
        drawn = false
        if (!PadBridge.isCursorRingShown()) return
        val game = gameView() ?: return
        val sw = xServer.screenInfo.width.toFloat()
        val sh = xServer.screenInfo.height.toFloat()
        if (sw <= 0f || sh <= 0f || game.width == 0 || game.height == 0) return
        val renderer = (game as? com.winlator.widget.XServerRendererView)?.renderer
        val stretch = renderer?.isFullscreen == true
        val scaleX: Float
        val scaleY: Float
        val offX: Float
        val offY: Float
        if (stretch) {
            scaleX = game.width / sw; scaleY = game.height / sh; offX = 0f; offY = 0f
        } else {
            val aspect = minOf(game.width / sw, game.height / sh)
            scaleX = aspect; scaleY = aspect
            offX = (game.width - sw * aspect) / 2f
            offY = (game.height - sh * aspect) / 2f
        }
        getLocationOnScreen(loc)
        game.getLocationOnScreen(gameLoc)
        val px = gameLoc[0] - loc[0] + offX + xServer.pointer.clampedX * scaleX
        val py = gameLoc[1] - loc[1] + offY + xServer.pointer.clampedY * scaleY

        val size = (game.height * PadSettings.int(PadSettings.RING_SIZE) / 1000f).coerceAtLeast(24f)
        val stroke = (size * 0.07f * PadSettings.int(PadSettings.RING_THICK) / 100f).coerceAtLeast(2f)
        val radius = size / 2f - stroke * 1.5f
        // Dark edge first so the bright ring reads on both light and dark scenes.
        paint.color = 0xFF000000.toInt()
        paint.strokeWidth = stroke + 3f
        canvas.drawCircle(px, py, radius, paint)
        paint.color = PadBridge.ringColor()
        paint.strokeWidth = stroke
        canvas.drawCircle(px, py, radius, paint)
        drawn = true
    }
}
