package app.gamenative.externaldisplay

/**
 * A small link from the controller code to the second-screen pad. Armed modifier buttons live on the pad,
 * but the controller's A / B clicks in cursor mode are handled elsewhere; this lets a finished click let go
 * of them, the same as a click from the pad's own buttons.
 */
object PadBridge {
    /** Set by the pad while it is on screen. Always called on the main thread. */
    @Volatile
    var releaseModifiers: (() -> Unit)? = null

    /** Modifiers held by the L2 / R2 triggers (when they act as modifier keys). Set by the controller code. */
    @Volatile
    var triggerModifiers: Set<com.winlator.xserver.XKeycode> = emptySet()
        private set

    /** Set by the pad: lights the modifier buttons and the glow when [triggerModifiers] changes. */
    @Volatile
    var triggerModifiersChanged: (() -> Unit)? = null

    fun setTriggerModifiers(keys: Set<com.winlator.xserver.XKeycode>) {
        if (keys == triggerModifiers) return
        triggerModifiers = keys.toSet()
        triggerModifiersChanged?.invoke()
    }

    /** True while the right stick is the mouse pointer (R3 cursor mode). Read by the renderer to draw the ring. */
    @Volatile
    private var cursorMode = false

    /**
     * True while the pad is the pointer: the trackpad is being used, or one of its click buttons.
     *
     * This is the second mouse source, and it is independent of R3 cursor mode - the local realm leaves the
     * right stick to ConsolePort, so handing that stick to the pointer would take the camera away. Instead the
     * pad takes the pointer only for as long as it is in use: [beginPointerSession] lets the controller code
     * let go of the camera stick, and the next controller input ends the session and hands the camera back.
     */
    @Volatile
    var pointerSession = false
        private set

    /** Set by the controller code: run when a session starts, so a deflected stick stops driving the camera. */
    @Volatile
    var pointerSessionStarted: (() -> Unit)? = null

    fun beginPointerSession() {
        if (pointerSession) return
        pointerSession = true
        pointerSessionStarted?.invoke()
    }

    fun endPointerSession() {
        if (!pointerSession) return
        pointerSession = false
    }

    fun isCursorRingShown(): Boolean = (cursorMode || pointerSession) && PadSettings.bool(PadSettings.CURSOR_RING)

    fun ringColor(): Int = PadSettings.RING_COLORS[PadSettings.int(PadSettings.RING_COLOR).coerceIn(0, PadSettings.RING_COLORS.size - 1)].second

    fun setCursorMode(on: Boolean) {
        if (cursorMode == on) return
        cursorMode = on
    }

    fun clickFinished() {
        releaseModifiers?.invoke()
    }
}
