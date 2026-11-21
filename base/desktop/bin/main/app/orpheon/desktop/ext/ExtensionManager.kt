// Part of Orpheon (https://github.com/kota-zo/Orpheon), GPL-3.0.
package app.orpheon.desktop.ext

import app.myspoti.extensions.Action
import app.myspoti.extensions.Archive
import app.myspoti.extensions.AudioQuery
import app.myspoti.extensions.AudioStream
import app.myspoti.extensions.ExtensionPackage
import app.myspoti.extensions.ExtensionRuntime
import app.myspoti.extensions.GitHubRepo
import app.myspoti.extensions.Hook
import app.myspoti.extensions.HookBudgets
import app.myspoti.extensions.Host
import app.myspoti.extensions.Manifest
import app.myspoti.extensions.TrackInfo
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * The extensions the user installed: kept on disk (their package and the permissions the user
 * granted), run in the sandbox of :extensions, asked for audio in the user's order.
 */
class ExtensionManager(private val dir: File, private val host: Host) : AutoCloseable {
    class Installed(val manifest: Manifest, val script: String, val granted: Set<String>) {
        val id: String get() = manifest.id
    }

    private val packages = File(dir, "packages").also { it.mkdirs() }
    private val installed = linkedMapOf<String, Installed>()
    private val runtimes = ConcurrentHashMap<String, ExtensionRuntime>()
    private val orderFile = File(dir, "audio-order.txt")

    /** Ids of the extensions asked for audio, first to last; an extension not listed is off. */
    @Volatile var audioOrder: List<String> = emptyList()
        private set

    init {
        packages.listFiles { f -> f.extension == "zip" }?.sortedBy { it.name }?.forEach { zip ->
            runCatching {
                val pkg = ExtensionPackage.read(zip.readBytes())
                val granted = File(packages, "${zip.nameWithoutExtension}.grants").takeIf { it.exists() }?.readLines()?.filter { it.isNotBlank() }?.toSet().orEmpty()
                installed[pkg.manifest.id] = Installed(pkg.manifest, pkg.script, granted)
            }.onFailure { System.err.println("extension ${zip.name}: ${it.message}") }
        }
        audioOrder = orderFile.takeIf { it.exists() }?.readLines()?.filter { it in installed }.orEmpty()
    }

    @Synchronized fun list(): List<Installed> = installed.values.toList()

    private val http by lazy { HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(15)).build() }

    /**
     * Downloads an extension from a repository link (`owner/name`, `github.com/owner/name`, `gitlab:group/name`,
     * `codeberg:owner/name`, a repository URL) or from a direct https `.zip` address. A repository is read at its
     * default branch; the package is cut from the archive as the phone does ([Archive.toPackage]).
     */
    fun download(link: String): ByteArray {
        val url = GitHubRepo.parse(link)?.archive(GitHubRepo.HEAD, false)
            ?: link.trim().takeIf { it.startsWith("https://") && it.substringBefore('?').endsWith(".zip") }
            ?: throw IllegalArgumentException("not a repository link or an https .zip address")
        val resp = http.send(HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(60)).GET().build(), HttpResponse.BodyHandlers.ofByteArray())
        if (resp.statusCode() != 200) throw IOException("download failed: HTTP ${resp.statusCode()}")
        if (resp.body().size > 20 * 1024 * 1024) throw IOException("the download is over 20 MB")
        return resp.body()
    }

    /** The package's manifest, for the install prompt (what it asks for), before anything is kept. */
    fun inspect(zip: ByteArray): Manifest = ExtensionPackage.read(Archive.toPackage(zip)).manifest

    /** Installs [zip] with the permissions the user accepted ([grantedKeys], from [Manifest.asks]). */
    @Synchronized fun install(zip: ByteArray, grantedKeys: Set<String>): Installed {
        val bytes = Archive.toPackage(zip)
        val pkg = ExtensionPackage.read(bytes)
        val name = pkg.manifest.id.replace(Regex("[^A-Za-z0-9._-]"), "_")
        File(packages, "$name.zip").writeBytes(bytes)
        File(packages, "$name.grants").writeText(grantedKeys.joinToString("\n"))
        runtimes.remove(pkg.manifest.id)?.close()
        val inst = Installed(pkg.manifest, pkg.script, grantedKeys)
        installed[pkg.manifest.id] = inst
        // An audio source is useless off: turn it on at install (the Extensions tab still switches it off).
        if (pkg.manifest.declares(Action.AUDIO_SOURCE) && pkg.manifest.id !in audioOrder) setAudioOrder(audioOrder + pkg.manifest.id)
        return inst
    }

    @Synchronized fun remove(id: String) {
        val name = id.replace(Regex("[^A-Za-z0-9._-]"), "_")
        File(packages, "$name.zip").delete(); File(packages, "$name.grants").delete()
        runtimes.remove(id)?.close()
        installed.remove(id)
        setAudioOrder(audioOrder - id)
    }

    @Synchronized fun setAudioOrder(ids: List<String>) {
        audioOrder = ids.filter { it in installed }.distinct()
        orderFile.writeText(audioOrder.joinToString("\n"))
    }

    private fun runtime(inst: Installed): ExtensionRuntime? = synchronized(runtimes) {
        runtimes[inst.id] ?: runCatching {
            ExtensionRuntime(inst.manifest, inst.script, host, inst.granted).also { it.start() }
        }.onFailure { host.log(inst.id, "does not load: ${it.message}") }.getOrNull()?.also { runtimes[inst.id] = it }
    }

    /**
     * Asks the audio sources in the user's order and returns the first answer. Blocking: the sandbox runs
     * the hook on the extension's own thread, for at most [HookBudgets.AUDIO]. Null: no extension has audio for it.
     */
    /** Why the last [resolveAudio] found nothing, one line per extension: shown to the user instead of a bare "no audio". */
    @Volatile var lastNotes: List<String> = emptyList()
        private set

    fun resolveAudio(track: TrackInfo, ahead: Boolean = false): AudioStream? {
        val notes = ArrayList<String>()
        try {
            if (audioOrder.isEmpty()) {
                val off = list().filter { it.manifest.declares(Action.AUDIO_SOURCE) }.map { it.manifest.name }
                notes += if (off.isEmpty()) "no audio-source extension is installed" else "audio source is switched off for: ${off.joinToString()}"
            }
            for (id in audioOrder) {
                val inst = synchronized(this) { installed[id] } ?: continue
                val name = inst.manifest.name
                val rt = runtime(inst)
                if (rt == null) { notes += "$name: the script did not load"; continue }
                if (!rt.provides(Hook.AUDIO_SOURCE)) { notes += "$name: not allowed to give audio (permission audio.source missing)"; continue }
                val out = try { rt.call(Hook.AUDIO_SOURCE, AudioQuery.toJson(track, ahead), HookBudgets.AUDIO) }
                catch (e: Exception) { host.log(id, "audio-source failed: ${e.message}"); notes += "$name: ${e.message}"; continue }
                if (out == null) { notes += "$name: no audio-source hook in its script, or it answered nothing"; continue }
                val stream = try { AudioStream.parse(out) }
                catch (e: Exception) { host.log(id, "audio-source answer refused: ${e.message}"); notes += "$name: answer refused (${e.message})"; continue }
                if (stream == null) { notes += "$name: has no audio for this song"; continue }
                return stream.copy(providerId = id)
            }
            return null
        } finally { lastNotes = notes }
    }

    override fun close() { runtimes.values.forEach { runCatching { it.close() } }; runtimes.clear() }
}
