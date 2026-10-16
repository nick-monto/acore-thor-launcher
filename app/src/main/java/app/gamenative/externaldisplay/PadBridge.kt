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

    fun clickFinished() {
        releaseModifiers?.invoke()
    }
}
