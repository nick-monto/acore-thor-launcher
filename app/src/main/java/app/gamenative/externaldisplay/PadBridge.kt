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
     * True while the pad's trackpad is up, i.e. the pad itself is the mouse. Set by the pad.
     *
     * The trackpad is a mouse mode of its own: it moves the pointer, so the pad's click shortcuts (A / B,
     * and L1 / R1 when enabled) and the cursor ring have to work there even when R3 cursor mode is off.
     * The local realm runs with it off, because ConsolePort drives the right stick, and tying the clicks
     * to that mode left A / B sending ConsolePort's buttons - A jumps - whenever the trackpad was open.
     */
    @Volatile
    var trackpadOpen = false
        private set

    /** Set by the controller code: a click it holds must not outlive the trackpad closing under it. */
    @Volatile
    var releaseClicks: (() -> Unit)? = null

    fun setTrackpadOpen(open: Boolean) {
        if (trackpadOpen == open) return
        trackpadOpen = open
        if (!open) releaseClicks?.invoke()
    }

    fun isCursorRingShown(): Boolean = (cursorMode || trackpadOpen) && PadSettings.bool(PadSettings.CURSOR_RING)

    fun ringColor(): Int = PadSettings.RING_COLORS[PadSettings.int(PadSettings.RING_COLOR).coerceIn(0, PadSettings.RING_COLORS.size - 1)].second

    fun setCursorMode(on: Boolean) {
        if (cursorMode == on) return
        cursorMode = on
    }

    fun clickFinished() {
        releaseModifiers?.invoke()
    }
}
