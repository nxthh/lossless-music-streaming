// Part of Orpheon (https://github.com/kota-zo/Orpheon), GPL-3.0.
package app.orpheon.desktop.player

import app.myspoti.api.Catalog
import app.myspoti.api.ItemKind
import app.myspoti.api.NotAvailable
import app.myspoti.api.NowPlaying
import app.myspoti.api.Page
import app.myspoti.api.Player
import app.myspoti.api.PlayerRestrictions
import app.myspoti.api.PlayerState
import app.myspoti.api.Playlists
import app.myspoti.api.Queue
import app.myspoti.api.Repeat
import app.myspoti.api.Track
import app.myspoti.api.Uri
import app.myspoti.api.best
import app.myspoti.api.AlbumRef
import app.orpheon.desktop.engine.AudioEngine
import app.orpheon.desktop.engine.EngineEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The desktop's implementation of the app's [Player]: the queue, shuffle, repeat and skipping of
 * `:api`, over an [AudioEngine]. Everything that changes the queue runs on one thread, so the
 * UI, the engine's end-of-song event and the extension calls never interleave mid-change.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopPlayer(
    private val engine: AudioEngine,
    private val catalog: () -> Catalog,
    private val playlists: () -> Playlists,
    private val resolver: AudioResolver,
    scope: CoroutineScope,
) : Player {

    private val serial = Dispatchers.Default.limitedParallelism(1)

    private var base: List<Track> = emptyList()      // the context, in its own order
    private var context: List<Track> = emptyList()   // the order being played (shuffled when shuffle is on)
    private var index = -1                           // position in [context] of the last context song started
    private val userQueue = ArrayDeque<Track>()     // "Play next" / "Add to queue": before the rest of the context
    private var current: Track? = null
    private var currentFromQueue = false
    private var contextUri: Uri? = null
    private var contextName: String? = null
    private var shuffle = false
    private var repeat = Repeat.OFF
    private var speed = 1.0
    private var level = 0.8
    private var loading = false
    private var generation = 0
    private var failures = 0
    private var skipOnFail = false
    private var quality: String? = null
    private var provider: String? = null

    private val _state = MutableStateFlow<PlayerState?>(null)
    private val _queue = MutableStateFlow<Queue?>(null)
    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 16)

    override val state: StateFlow<PlayerState?> = _state.asStateFlow()
    override val queue: StateFlow<Queue?> = _queue.asStateFlow()

    /** Things the user should be told (no audio for a song, a source that failed). */
    val notices: SharedFlow<String> = _notices.asSharedFlow()

    /** The song now playing, for the extensions' `nowPlaying` and the UI. */
    val nowPlayingTrack: Track? get() = current

    init {
        engine.setVolume(level)
        scope.launch(serial) { engine.state.collect { publish() } }
        scope.launch(serial) {
            engine.events.collect { e ->
                when (e) {
                    EngineEvent.Ended -> advance(auto = true)
                    is EngineEvent.Failed -> { notice("${current?.name ?: "This song"}: ${e.message}"); failedCurrent() }
                }
            }
        }
    }

    // ---- Player ----

    override suspend fun play(contextUri: Uri, trackUri: Uri?, index: Int?, positionMs: Long?, order: List<Uri>?) = withContext(serial) {
        val loaded = loadContext(contextUri)
        val ordered = order?.let { o -> val by = loaded.associateBy { it.uri }; o.mapNotNull { by[it] }.ifEmpty { loaded } } ?: loaded
        if (ordered.isEmpty()) throw NotAvailable("nothing to play in $contextUri")
        val start = trackUri?.let { u -> ordered.indexOfFirst { it.uri == u } }?.takeIf { it >= 0 } ?: (index ?: 0).coerceIn(0, ordered.lastIndex)
        startContext(ordered, start, contextUri, contextName = null, positionMs = positionMs ?: 0)
    }

    /** What the UI uses to play a list it already has (search results, an album's rows). */
    suspend fun playTracks(tracks: List<Track>, startIndex: Int, name: String?, contextUri: Uri? = null) = withContext(serial) {
        if (tracks.isEmpty()) return@withContext
        startContext(tracks, startIndex.coerceIn(0, tracks.lastIndex), contextUri, name, 0)
    }

    override suspend fun pause() = withContext(serial) { engine.pause(); publish() }

    override suspend fun resume() = withContext(serial) {
        val t = current ?: return@withContext
        if (engine.state.value.durationMs > 0) { engine.play(); publish() } else startTrack(t)
        Unit
    }

    override suspend fun seek(positionMs: Long) = withContext(serial) { engine.seek(positionMs); publish() }

    override suspend fun next() = withContext(serial) { advance(auto = false); Unit }

    override suspend fun previous() = withContext(serial) {
        val cur = current ?: return@withContext
        val before = if (currentFromQueue) context.getOrNull(index) else context.getOrNull(index - 1)
        if (engine.state.value.positionMs > 3_000 || before == null) { engine.seek(0); publish(); return@withContext }
        if (!currentFromQueue) index -= 1
        currentFromQueue = false
        startTrack(before)
        Unit
    }

    override suspend fun setShuffle(on: Boolean) = withContext(serial) {
        if (on == shuffle) return@withContext
        shuffle = on
        val cursor = context.getOrNull(index)
        if (on) {
            context = context.take(index + 1) + context.drop(index + 1).shuffled()
        } else if (cursor != null) {
            context = base
            index = base.indexOfFirst { it.uri == cursor.uri }.coerceAtLeast(0)
        }
        publish()
    }

    override suspend fun setRepeat(mode: Repeat) = withContext(serial) { repeat = mode; publish() }

    override suspend fun addToQueue(uris: List<Uri>) = withContext(serial) {
        userQueue.addAll(uris.map { catalog().track(it) }); publish()
    }

    override suspend fun playNext(uris: List<Uri>) = withContext(serial) {
        userQueue.addAll(0, uris.map { catalog().track(it) }); publish()
    }

    override suspend fun insertInQueue(uris: List<Uri>, before: NowPlaying) = withContext(serial) {
        val add = uris.map { catalog().track(it) }
        val q = userQueue.indexOfFirst { it.uri == before.uri }
        if (q >= 0) userQueue.addAll(q, add)
        else {
            val c = context.drop(index + 1).indexOfFirst { it.uri == before.uri }
            if (c >= 0) context = context.toMutableList().also { it.addAll(index + 1 + c, add) }
            else userQueue.addAll(add)
        }
        publish()
    }

    override suspend fun removeFromQueue(items: List<NowPlaying>) = withContext(serial) {
        val gone = items.map { it.uri }.toSet()
        userQueue.removeAll { it.uri in gone }
        context = context.filterIndexed { i, t -> i <= index || t.uri !in gone }
        publish()
    }

    override suspend fun clearQueue() = withContext(serial) { userQueue.clear(); publish() }

    override suspend fun skipTo(item: NowPlaying) = withContext(serial) {
        val q = userQueue.indexOfFirst { it.uri == item.uri }
        if (q >= 0) {
            for (i in 0 until q) userQueue.removeFirst()
            currentFromQueue = true
            startTrack(userQueue.removeFirst())
        } else {
            val c = context.drop(index + 1).indexOfFirst { it.uri == item.uri }
            if (c >= 0) { index += 1 + c; currentFromQueue = false; startTrack(context[index]) }
        }
        Unit
    }

    override suspend fun moveInQueue(item: NowPlaying, target: NowPlaying, after: Boolean) = withContext(serial) {
        val from = userQueue.indexOfFirst { it.uri == item.uri }
        val to = userQueue.indexOfFirst { it.uri == target.uri }
        if (from >= 0 && to >= 0 && from != to) {
            val moved = userQueue.removeAt(from)
            val t = userQueue.indexOfFirst { it.uri == target.uri }
            userQueue.add(if (after) t + 1 else t, moved)
        } else {
            val rest = context.drop(index + 1).toMutableList()
            val f = rest.indexOfFirst { it.uri == item.uri }
            val t0 = rest.indexOfFirst { it.uri == target.uri }
            if (f >= 0 && t0 >= 0 && f != t0) {
                val moved = rest.removeAt(f)
                val t = rest.indexOfFirst { it.uri == target.uri }
                rest.add(if (after) t + 1 else t, moved)
                context = context.take(index + 1) + rest
            }
        }
        publish()
    }

    override suspend fun volume(): Double = level

    override suspend fun setVolume(level: Double) { this.level = level.coerceIn(0.0, 1.0); engine.setVolume(this.level) }

    override suspend fun setSpeed(speed: Double) = withContext(serial) { this@DesktopPlayer.speed = speed; engine.setSpeed(speed); publish() }

    // ---- internals (all on [serial]) ----

    private suspend fun startContext(tracks: List<Track>, start: Int, uri: Uri?, contextName: String?, positionMs: Long) {
        base = tracks
        contextUri = uri
        this.contextName = contextName
        val first = tracks[start]
        if (shuffle) { context = listOf(first) + (tracks - first).shuffled(); index = 0 } else { context = tracks; index = start }
        currentFromQueue = false
        failures = 0
        startTrack(first, positionMs)
    }

    /** Resolves the song's audio and starts it. False when the song has none (the user is told). */
    private suspend fun startTrack(track: Track, positionMs: Long = 0, skipOnFail: Boolean = false): Boolean {
        current = track
        this.skipOnFail = skipOnFail
        loading = true
        quality = null; provider = null
        val mine = ++generation
        publish()
        val resolved = try { resolver.resolve(track) } catch (e: Exception) { notice("${track.name}: ${e.message}"); null }
        if (mine != generation) return false // the user moved on while the extension was answering
        loading = false
        if (resolved == null) { engine.stop(); publish(); failedCurrent(); return false }
        quality = resolved.quality; provider = resolved.provider
        engine.load(resolved.source, positionMs, autoplay = true)
        engine.setSpeed(speed)
        engine.setVolume(level)
        publish()
        return true
    }

    private suspend fun failedCurrent() {
        failures += 1
        // Only while moving on by itself (next, end of a song): a song the user picked just says why it did not play.
        if (skipOnFail && failures <= 3 && failures < (context.size + userQueue.size).coerceAtLeast(1)) advance(auto = true, skippingFailure = true)
    }

    private suspend fun advance(auto: Boolean, skippingFailure: Boolean = false): Boolean {
        if (auto && !skippingFailure && repeat == Repeat.TRACK) { current?.let { return startTrack(it) } }
        if (!skippingFailure) failures = 0
        if (userQueue.isNotEmpty()) { currentFromQueue = true; return startTrack(userQueue.removeFirst(), skipOnFail = true) }
        val nextIndex = index + 1
        return when {
            nextIndex < context.size -> { index = nextIndex; currentFromQueue = false; startTrack(context[index], skipOnFail = true) }
            repeat == Repeat.CONTEXT && context.isNotEmpty() -> { index = 0; currentFromQueue = false; startTrack(context[0], skipOnFail = true) }
            else -> { if (auto) { engine.stop() }; publish(); false }
        }
    }

    private suspend fun loadContext(uri: Uri): List<Track> {
        return when (uri.split(':').getOrNull(1)) {
            "album" -> {
                val album = runCatching { catalog().album(uri) }.getOrNull()
                val ref = album?.let { AlbumRef(it.uri, it.name, it.images) }
                pages { off -> catalog().albumTracks(uri, off, 50) }.map { t -> if (t.album == null && ref != null) t.copy(album = ref) else t }
            }
            "playlist" -> pages { off -> playlists().tracks(uri, off, 100) }
            "artist" -> catalog().artistTopTracks(uri)
            else -> listOf(catalog().track(uri))
        }
    }

    private suspend fun pages(load: suspend (Int) -> Page<Track>): List<Track> {
        val all = ArrayList<Track>()
        var offset = 0
        while (all.size < 1000) {
            val p = load(offset)
            all += p.items
            offset = p.nextOffset ?: break
        }
        return all
    }

    private fun notice(text: String) { System.err.println("[orpheon] $text"); _notices.tryEmit(text) }

    private fun Track.np() = NowPlaying(
        kind = ItemKind.TRACK, uri = uri, uid = uid, name = name, artists = artists, album = album,
        imageUrl = album?.images?.best(640)?.url, durationMs = durationMs, explicit = explicit,
    )

    private fun publish() {
        val cur = current
        if (cur == null) { _state.value = null; _queue.value = null; return }
        val es = engine.state.value
        val restOfContext = context.drop(index + 1)
        val upcoming = userQueue.toList() + restOfContext
        val canNext = upcoming.isNotEmpty() || repeat == Repeat.CONTEXT
        _state.value = PlayerState(
            item = cur.np(),
            contextUri = contextUri,
            contextName = contextName,
            previous = (if (currentFromQueue) context.getOrNull(index) else context.getOrNull(index - 1))?.np(),
            next = upcoming.firstOrNull()?.np(),
            isPaused = !es.playing && !es.buffering && !loading,
            isBuffering = es.buffering || loading,
            positionMs = if (loading) 0 else es.positionMs,
            timestampMs = System.currentTimeMillis(),
            speed = speed,
            shuffle = shuffle,
            repeat = repeat,
            restrictions = PlayerRestrictions(canSkipNext = canNext),
        )
        _queue.value = Queue(current = cur.np(), queued = userQueue.map { it.np() }, nextUp = restOfContext.map { it.np() })
    }

    /** "FLAC · 16-bit · 44.1 kHz" and which extension answered, for the player's sound-quality line. */
    val sourceInfo: Pair<String?, String?> get() = provider to quality
}
