package com.music.spotui.ui.immersive

/** Monotonic milliseconds (Android: SystemClock.uptimeMillis). */
expect fun uptimeMillis(): Long

/** Where the Immersive code's diagnostics go; the host app points this at its own log. */
object ImmersiveLog {
    var sink: (String) -> Unit = {}
    fun log(message: String) = sink(message)
}
