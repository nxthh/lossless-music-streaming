// Part of Orpheon (https://github.com/kota-zo/Orpheon), GPL-3.0.
package app.orpheon.desktop.player

import app.myspoti.api.Track
import app.myspoti.extensions.TrackInfo
import app.orpheon.desktop.ext.ExtensionManager
import app.orpheon.desktop.stream.StreamProxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI

/** Something the engine can open, and where it came from. */
data class Resolved(val source: String, val provider: String?, val quality: String?)

/** Why a song has no audio, in words for the user. */
class NoAudio(message: String) : Exception(message)

fun interface AudioResolver {
    suspend fun resolve(track: Track): Resolved
}

/**
 * Where a song's audio is: a local file, else the user's audio-source extensions in their order
 * (the extensions API: "every play of a song goes to the active audio-source extension first").
 */
class SourceResolver(private val extensions: ExtensionManager, private val proxy: StreamProxy) : AudioResolver {

    override suspend fun resolve(track: Track): Resolved {
        if (track.uri.startsWith("file:")) {
            val path = File(URI(track.uri)).absolutePath
            return Resolved(path, "local-file", null)
        }
        val info = track.toInfo()
        val stream = withContext(Dispatchers.IO) { extensions.resolveAudio(info) }
            ?: throw NoAudio(extensions.lastNotes.joinToString("; ").ifBlank { "no audio source has this song" })
        if (stream.manifest != null) throw NoAudio("this source sends a DASH manifest, which the desktop app cannot play yet")
        val url = proxy.register(stream) {
            runCatching { extensions.resolveAudio(info) }.getOrNull()?.takeIf { it.url.isNotEmpty() }
        }
        return Resolved(url, stream.providerId, stream.qualityLabel())
    }
}

fun Track.toInfo(): TrackInfo = TrackInfo(
    id = uri.substringAfterLast(':'),
    uri = uri,
    title = name,
    artist = artists.firstOrNull()?.name.orEmpty(),
    artists = artists.map { it.name },
    album = album?.name.orEmpty(),
    durationMs = durationMs,
    cover = album?.images?.firstOrNull()?.url,
)
