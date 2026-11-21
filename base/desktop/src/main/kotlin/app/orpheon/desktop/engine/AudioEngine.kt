// Part of Orpheon (https://github.com/kota-zo/Orpheon), GPL-3.0.
package app.orpheon.desktop.engine

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

data class EngineState(
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val buffering: Boolean = false,
)

sealed interface EngineEvent {
    /** The loaded source played to its end: the queue moves on. */
    data object Ended : EngineEvent
    data class Failed(val message: String) : EngineEvent
}

/**
 * What the desktop player needs from a sound engine, and nothing more. [VlcEngine] is the first
 * one; an FFmpeg/PCM engine (needed to run AutoMix's SegueDeck on samples) can replace it without
 * touching the queue or the UI.
 */
interface AudioEngine : AutoCloseable {
    val state: StateFlow<EngineState>
    val events: SharedFlow<EngineEvent>

    /** [source] is a file path or an https URL. */
    fun load(source: String, startMs: Long = 0, autoplay: Boolean = true)
    fun play()
    fun pause()
    fun stop()
    fun seek(positionMs: Long)

    /** 0.0 to 1.0, as `Player.setVolume`. */
    fun setVolume(level: Double)

    /** 0.5 to 3.5, as `Player.setSpeed`; the pitch follows the app's setting. */
    fun setSpeed(speed: Double)

    /** [bandsDb]: ten gains in dB (31 Hz ... 16 kHz); null turns the equalizer off. */
    fun setEqualizer(bandsDb: List<Float>?, preampDb: Float = 0f)
}
