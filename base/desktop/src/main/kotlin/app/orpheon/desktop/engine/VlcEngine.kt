// Part of Orpheon (https://github.com/kota-zo/Orpheon), GPL-3.0.
package app.orpheon.desktop.engine

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import java.io.File

/** libVLC through vlcj. Audio only: the factory starts without video output. */
class VlcEngine private constructor(
    private val factory: MediaPlayerFactory,
) : AudioEngine {

    private val player: MediaPlayer = factory.mediaPlayers().newMediaPlayer()
    private val _state = MutableStateFlow(EngineState())
    private val _events = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 8)
    private var pendingStartMs = 0L

    override val state: StateFlow<EngineState> = _state.asStateFlow()
    override val events: SharedFlow<EngineEvent> = _events.asSharedFlow()

    init {
        player.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun playing(mp: MediaPlayer) {
                if (pendingStartMs > 0) { mp.controls().setTime(pendingStartMs); pendingStartMs = 0 }
                _state.update { it.copy(playing = true, buffering = false) }
            }
            override fun paused(mp: MediaPlayer) { _state.update { it.copy(playing = false) } }
            override fun stopped(mp: MediaPlayer) { _state.update { it.copy(playing = false, positionMs = 0) } }
            override fun buffering(mp: MediaPlayer, newCache: Float) {
                _state.update { it.copy(buffering = newCache < 100f) }
            }
            override fun lengthChanged(mp: MediaPlayer, newLength: Long) { _state.update { it.copy(durationMs = newLength) } }
            override fun timeChanged(mp: MediaPlayer, newTime: Long) { _state.update { it.copy(positionMs = newTime) } }
            override fun finished(mp: MediaPlayer) {
                _state.update { it.copy(playing = false) }
                _events.tryEmit(EngineEvent.Ended)
            }
            override fun error(mp: MediaPlayer) {
                _state.update { it.copy(playing = false, buffering = false) }
                _events.tryEmit(EngineEvent.Failed("libVLC could not play this source (run from a terminal to see its log)"))
            }
        })
    }

    override fun load(source: String, startMs: Long, autoplay: Boolean) {
        pendingStartMs = startMs
        _state.value = EngineState(buffering = true)
        // vlcj's callbacks run on a libVLC thread; starting media from one can deadlock, so hand it off.
        // A plain path for local files: libVLC then builds the right file:/// address on every OS.
        val mrl = if (source.startsWith("http", ignoreCase = true)) source else File(source).absolutePath
        if (autoplay) player.submit { player.media().play(mrl) } else player.submit { player.media().startPaused(mrl) }
    }

    override fun play() = player.controls().play()
    override fun pause() = player.controls().setPause(true)
    override fun stop() = player.controls().stop()
    override fun seek(positionMs: Long) = player.controls().setTime(positionMs)

    // libVLC volume is 0..200 (100 = unity); the app's 0..1 maps to 0..100 so the UI never boosts.
    override fun setVolume(level: Double) { player.audio().setVolume((level.coerceIn(0.0, 1.0) * 100).toInt()) }

    override fun setSpeed(speed: Double) { player.controls().setRate(speed.coerceIn(0.5, 3.5).toFloat()) }

    override fun setEqualizer(bandsDb: List<Float>?, preampDb: Float) {
        if (bandsDb == null) { player.audio().setEqualizer(null); return }
        val eq = factory.equalizer().newEqualizer()
        eq.setPreamp(preampDb)
        bandsDb.take(BANDS).forEachIndexed { i, db -> eq.setAmp(i, db) }
        player.audio().setEqualizer(eq)
    }

    override fun close() {
        player.release()
        factory.release()
    }

    companion object {
        /** libVLC's equalizer always has ten bands (31 Hz to 16 kHz). */
        private const val BANDS = 10

        /** null when libVLC is not installed: the UI then says how to get it instead of failing silently. */
        fun createOrNull(): VlcEngine? {
            if (!NativeDiscovery().discover()) return null
            return VlcEngine(MediaPlayerFactory("--no-video", "--no-metadata-network-access", "--network-caching=3000", "--verbose=1"))
        }
    }
}
