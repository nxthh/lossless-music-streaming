// Part of Orpheon (https://github.com/kota-zo/Orpheon), GPL-3.0.
package app.orpheon.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.myspoti.api.Album
import app.myspoti.api.NowPlaying
import app.myspoti.api.Repeat
import app.myspoti.api.Track
import app.myspoti.extensions.Action
import app.myspoti.extensions.Manifest
import app.orpheon.desktop.engine.VlcEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/** The colour of all text in the window: change this one value to restyle it. */
private val TextColor = Color(0xFFF2EEF8)

fun main() = application {
    val graph = remember { VlcEngine.createOrNull()?.let { AppGraph(it) } }
    Window(
        onCloseRequest = { graph?.close(); exitApplication() },
        title = "Orpheon",
        state = rememberWindowState(width = 1180.dp, height = 760.dp),
    ) {
        MaterialTheme(colorScheme = darkColorScheme()) {
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF14101F), Color(0xFF2A1B3D), Color(0xFF0E1B2B))))) {
                if (graph == null) MissingVlc() else App(graph)
            }
        }
    }
}

@Composable
private fun MissingVlc() = Column(Modifier.fillMaxSize().padding(48.dp), verticalArrangement = Arrangement.Center) {
    Text("Orpheon needs VLC's playback library", style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(12.dp))
    Text("Windows: install VLC from videolan.org (64-bit).  Linux: install the vlc package (apt/dnf/pacman).")
}

private enum class Tab(val label: String) { SEARCH("Search"), QUEUE("Queue"), EXTENSIONS("Extensions") }

/** A working shell over the real player, catalog and extensions. The Immersive screens replace it once they are extracted from :app (DESKTOP.md). */
@Composable
private fun App(graph: AppGraph) {
    var tab by remember { mutableStateOf(Tab.SEARCH) }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(graph) { graph.player.notices.collect { snack.showSnackbar(it) } }
    fun report(e: Throwable) { scope.launch { snack.showSnackbar(e.message ?: e.toString()) } }

    Scaffold(
        containerColor = Color.Transparent,
        contentColor = TextColor,
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = { NowPlayingBar(graph, ::report) },
    ) { pad ->
        Row(Modifier.padding(pad).fillMaxSize()) {
            NavigationRail(containerColor = Color.Transparent) {
                Tab.entries.forEach { t ->
                    NavigationRailItem(selected = tab == t, onClick = { tab = t }, icon = {}, label = { Text(t.label) }, alwaysShowLabel = true)
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight().padding(16.dp)) {
                when (tab) {
                    Tab.SEARCH -> SearchScreen(graph, scope, ::report)
                    Tab.QUEUE -> QueueScreen(graph, scope, ::report)
                    Tab.EXTENSIONS -> ExtensionsScreen(graph, ::report)
                }
            }
        }
    }
}

@Composable
private fun SearchScreen(graph: AppGraph, scope: CoroutineScope, report: (Throwable) -> Unit) {
    var q by remember { mutableStateOf("") }
    var tracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var albums by remember { mutableStateOf<List<Album>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }

    fun search() {
        if (q.isBlank()) return
        scope.launch(Dispatchers.IO) {
            busy = true
            try {
                coroutineScope {
                    val t = async { graph.api.search.tracks(q, 0, 30).items }
                    val a = async { runCatching { graph.api.search.albums(q, 0, 12).items }.getOrDefault(emptyList()) }
                    tracks = t.await(); albums = a.await()
                }
            } catch (e: Exception) { report(e) } finally { busy = false }
        }
    }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(q, { q = it }, Modifier.weight(1f), singleLine = true, label = { Text("Search songs and albums") })
            Button(onClick = ::search, enabled = !busy) { Text(if (busy) "Searching..." else "Search") }
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (albums.isNotEmpty()) item { Text("Albums", style = MaterialTheme.typography.titleMedium) }
            itemsIndexed(albums) { _, a ->
                Row(Modifier.fillMaxWidth().clickable { scope.launch { runCatching { graph.player.play(a.uri) }.onFailure(report) } }.padding(vertical = 6.dp)) {
                    Text("${a.name} - ${a.artists.joinToString { it.name }}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (tracks.isNotEmpty()) item { Spacer(Modifier.height(8.dp)); Text("Songs", style = MaterialTheme.typography.titleMedium) }
            itemsIndexed(tracks) { i, t ->
                Row(Modifier.fillMaxWidth().clickable { scope.launch { graph.player.playTracks(tracks, i, "Search: $q") } }.padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${t.name} - ${t.artists.joinToString { it.name }}", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(fmt(t.durationMs))
                }
            }
        }
    }
}

@Composable
private fun QueueScreen(graph: AppGraph, scope: CoroutineScope, report: (Throwable) -> Unit) {
    val queue by graph.player.queue.collectAsState()
    val qv = queue
    if (qv == null) { Text("Nothing is playing."); return }
    fun row(n: NowPlaying) = scope.launch { runCatching { graph.player.skipTo(n) }.onFailure(report) }
    LazyColumn {
        item { Text("Now playing", style = MaterialTheme.typography.titleMedium); Text(qv.current?.name ?: "-") }
        if (qv.queued.isNotEmpty()) {
            item { Spacer(Modifier.height(12.dp)); Text("Queue", style = MaterialTheme.typography.titleMedium) }
            itemsIndexed(qv.queued) { _, n -> Text(n.name + " - " + n.artists.joinToString { it.name }, Modifier.fillMaxWidth().clickable { row(n) }.padding(vertical = 4.dp)) }
        }
        item { Spacer(Modifier.height(12.dp)); Text("Next up", style = MaterialTheme.typography.titleMedium) }
        itemsIndexed(qv.nextUp) { _, n -> Text(n.name + " - " + n.artists.joinToString { it.name }, Modifier.fillMaxWidth().clickable { row(n) }.padding(vertical = 4.dp)) }
    }
}

@Composable
private fun ExtensionsScreen(graph: AppGraph, report: (Throwable) -> Unit) {
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf("") }
    var fetching by remember { mutableStateOf(false) }
    var version by remember { mutableIntStateOf(0) }
    var pending by remember { mutableStateOf<Pair<ByteArray, Manifest>?>(null) }
    val installed = remember(version) { graph.extensions.list() }
    val order = remember(version) { graph.extensions.audioOrder }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Extensions", style = MaterialTheme.typography.headlineSmall)
        Text("Orpheon has no music of its own: songs play from the audio-source extension you install and turn on here.")
        Button(onClick = {
            val d = FileDialog(null as Frame?, "Install an extension (.zip)", FileDialog.LOAD).apply { isVisible = true }
            if (d.file != null) runCatching {
                val bytes = File(d.directory, d.file).readBytes()
                pending = bytes to graph.extensions.inspect(bytes)
            }.onFailure(report)
        }) { Text("Install from file...") }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(link, { link = it }, Modifier.weight(1f), singleLine = true, label = { Text("Repository link (github.com/owner/name) or .zip address") })
            Button(enabled = link.isNotBlank() && !fetching, onClick = {
                scope.launch(Dispatchers.IO) {
                    fetching = true
                    try {
                        val bytes = graph.extensions.download(link)
                        pending = bytes to graph.extensions.inspect(bytes)
                    } catch (e: Exception) { report(e) } finally { fetching = false }
                }
            }) { Text(if (fetching) "Downloading..." else "Install from link") }
        }

        installed.forEach { e ->
            val isSource = e.manifest.declares(Action.AUDIO_SOURCE)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("${e.manifest.name} ${e.manifest.version}", style = MaterialTheme.typography.titleMedium)
                    Text(e.manifest.description, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (isSource) {
                    Text("Audio source")
                    Switch(checked = e.id in order, onCheckedChange = { on ->
                        graph.extensions.setAudioOrder(if (on) order + e.id else order - e.id); version++
                    })
                }
                TextButton(onClick = { graph.extensions.remove(e.id); version++ }) { Text("Remove") }
            }
        }
    }

    pending?.let { (bytes, m) ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Install ${m.name} ${m.version}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("by ${m.author}")
                    Text("It asks to:")
                    m.asks().forEach { a -> Text((if (a.critical) "! " else "- ") + a.key + (if (a.why.isNotBlank()) ": ${a.why}" else "")) }
                }
            },
            confirmButton = {
                Button(onClick = {
                    runCatching { graph.extensions.install(bytes, m.asks().map { it.key }.toSet()) }.onFailure(report)
                    pending = null; version++
                }) { Text("Allow and install") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun NowPlayingBar(graph: AppGraph, report: (Throwable) -> Unit) {
    val scope = rememberCoroutineScope()
    val ps by graph.player.state.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(250) } }
    var volume by remember { mutableFloatStateOf(0.8f) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    val s = ps

    Surface(color = Color(0xCC0B0814), contentColor = TextColor, tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            val dur = (s?.item?.durationMs ?: 0L).coerceAtLeast(1)
            val pos = s?.positionAt(now) ?: 0L
            Slider(
                value = dragging ?: (pos.toFloat() / dur).coerceIn(0f, 1f),
                onValueChange = { dragging = it },
                onValueChangeFinished = { dragging?.let { f -> scope.launch { graph.player.seek((f * dur).toLong()) } }; dragging = null },
                enabled = s != null,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(s?.item?.name ?: "Nothing playing", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                    val (provider, quality) = graph.player.sourceInfo
                    Text(
                        (s?.item?.artists?.joinToString { it.name } ?: "") + (quality?.let { "  |  $it" } ?: "") + (provider?.let { " via $it" } ?: ""),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Text("${fmt(pos)} / ${fmt(s?.item?.durationMs ?: 0)}")
                TextButton(onClick = { scope.launch { graph.player.setShuffle(!(s?.shuffle ?: false)) } }) { Text(if (s?.shuffle == true) "Shuffle: on" else "Shuffle") }
                TextButton(onClick = {
                    val m = when (s?.repeat ?: Repeat.OFF) { Repeat.OFF -> Repeat.CONTEXT; Repeat.CONTEXT -> Repeat.TRACK; Repeat.TRACK -> Repeat.OFF }
                    scope.launch { graph.player.setRepeat(m) }
                }) { Text("Repeat: " + (s?.repeat ?: Repeat.OFF).name.lowercase()) }
                Button(onClick = { scope.launch { graph.player.previous() } }, enabled = s != null) { Text("Prev") }
                Button(onClick = {
                    scope.launch { runCatching { if (s?.isPaused == false) graph.player.pause() else graph.player.resume() }.onFailure(report) }
                }, enabled = s != null) { Text(if (s == null || s.isPaused) "Play" else "Pause") }
                Button(onClick = { scope.launch { graph.player.next() } }, enabled = s?.restrictions?.canSkipNext == true) { Text("Next") }
                Slider(volume, { volume = it; scope.launch { graph.player.setVolume(it.toDouble()) } }, Modifier.width(120.dp))
            }
        }
    }
}

private fun fmt(ms: Long): String = "%d:%02d".format(ms / 60000, ms / 1000 % 60)
