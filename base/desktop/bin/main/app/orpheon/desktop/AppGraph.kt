// Part of Orpheon (https://github.com/kota-zo/Orpheon), GPL-3.0.
package app.orpheon.desktop

import app.myspoti.api.MyspotiApi
import app.orpheon.desktop.engine.AudioEngine
import app.orpheon.desktop.ext.DesktopHost
import app.orpheon.desktop.ext.ExtensionManager
import app.orpheon.desktop.player.DesktopPlayer
import app.orpheon.desktop.player.SourceResolver
import app.orpheon.desktop.player.toInfo
import app.orpheon.desktop.stream.StreamProxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File

/** Everything the desktop app is made of, built once: engine, extensions, stream proxy, player, API. */
class AppGraph(val engine: AudioEngine) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val proxy = StreamProxy()
    private val metadata: MyspotiApi = DesktopApi.metadata()

    val host = DesktopHost(File(AppDirs.data, "extensions/state"))
    val extensions = ExtensionManager(File(AppDirs.data, "extensions"), host)
    val player = DesktopPlayer(engine, { metadata.catalog }, { metadata.playlists }, SourceResolver(extensions, proxy), scope)
    val api: MyspotiApi = DesktopApi.of(metadata, player)

    init {
        host.nowPlayingJson = { player.nowPlayingTrack?.toInfo()?.toJson() }
    }

    override fun close() {
        scope.cancel()
        extensions.close()
        proxy.close()
        engine.close()
    }
}
