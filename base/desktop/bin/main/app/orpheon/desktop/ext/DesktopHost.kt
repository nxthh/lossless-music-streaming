// Part of Orpheon (https://github.com/kota-zo/Orpheon), GPL-3.0.
package app.orpheon.desktop.ext

import app.myspoti.extensions.Host
import app.myspoti.extensions.Storage
import java.io.File
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

/** A key/value store kept in one file; values are JSON text, as the extension runtime wants them. */
class FileStorage(private val file: File) : Storage {
    private val props = Properties().apply { if (file.exists()) file.inputStream().use { load(it) } }

    @Synchronized override fun get(key: String): String? = props.getProperty(key)
    @Synchronized override fun set(key: String, json: String) { props.setProperty(key, json); save() }
    @Synchronized override fun remove(key: String) { props.remove(key); save() }
    @Synchronized override fun size(): Int = props.values.sumOf { (it as String).length }

    private fun save() {
        file.parentFile?.mkdirs()
        file.outputStream().use { props.store(it, null) }
    }
}

/**
 * What the desktop gives the extension sandbox: its own storage and settings, kept per extension
 * under [dir]. Network goes through the runtime's default (checked against the extension's hosts).
 */
class DesktopHost(private val dir: File) : Host {
    /** The song playing now, as the hooks receive a track (JSON); set once the player exists. */
    @Volatile var nowPlayingJson: () -> String? = { null }

    private val stores = ConcurrentHashMap<String, FileStorage>()
    private val settings = ConcurrentHashMap<String, FileStorage>()

    private fun safe(id: String) = id.replace(Regex("[^A-Za-z0-9._-]"), "_")

    override fun log(extensionId: String, message: String) = System.err.println("[ext $extensionId] $message")

    override fun storage(extensionId: String): Storage =
        stores.getOrPut(extensionId) { FileStorage(File(dir, "${safe(extensionId)}/storage.properties")) }

    private fun settingsOf(extensionId: String) =
        settings.getOrPut(extensionId) { FileStorage(File(dir, "${safe(extensionId)}/settings.properties")) }

    override fun setting(extensionId: String, key: String): String? = settingsOf(extensionId).get(key)

    /** [valueJson] is JSON text: `"FLAC"` for a string, `true` for a toggle, `3` for a number. */
    fun setSetting(extensionId: String, key: String, valueJson: String) = settingsOf(extensionId).set(key, valueJson)

    override fun nowPlaying(): String? = nowPlayingJson()
}
