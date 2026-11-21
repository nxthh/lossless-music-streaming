// Part of Orpheon (https://github.com/kota-zo/Orpheon), GPL-3.0.
package app.orpheon.desktop.stream

import app.myspoti.extensions.AudioStream
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * A local web server on 127.0.0.1 that fetches a stream for the player. An audio-source extension gives a
 * URL and up to 20 headers (Referer, Authorization...), which libVLC cannot send; the proxy sends them, passes
 * the player's Range requests on (so seeking works) and, when the server refuses an expired address, asks the
 * extension once more, as the extensions API promises. Nothing but this machine can connect to it.
 */
class StreamProxy : AutoCloseable {
    private class Entry(@Volatile var stream: AudioStream, val renew: () -> AudioStream?) {
        /** What the first bytes of the file say it is: libVLC picks its decoder from the type, and servers often label it wrongly. */
        @Volatile var type: String? = null
    }

    private val entries = ConcurrentHashMap<String, Entry>()
    private val order = ArrayDeque<String>()
    private val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(15)).build()
    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        executor = Executors.newCachedThreadPool { r -> Thread(r, "stream-proxy").apply { isDaemon = true } }
        createContext("/s/") { ex -> handle(ex) }
        start()
    }

    /** The address to give the player for [stream]; [renew] asks the extension again when the address has expired. */
    fun register(stream: AudioStream, renew: () -> AudioStream?): String {
        val token = UUID.randomUUID().toString().replace("-", "")
        entries[token] = Entry(stream, renew)
        synchronized(order) { order.addLast(token); while (order.size > 12) entries.remove(order.removeFirst()) }
        return "http://127.0.0.1:${server.address.port}/s/$token"
    }

    private fun open(entry: Entry, method: String, range: String?): HttpResponse<java.io.InputStream> {
        val b = HttpRequest.newBuilder(URI(entry.stream.url)).timeout(Duration.ofSeconds(30))
        if (method == "HEAD") b.method("HEAD", HttpRequest.BodyPublishers.noBody()) else b.GET()
        entry.stream.headers.forEach { (k, v) -> runCatching { b.header(k, v) } } // the JDK refuses a few names; the extension API drops them too
        if (range != null) b.header("Range", range)
        return http.send(b.build(), HttpResponse.BodyHandlers.ofInputStream())
    }

    private fun handle(ex: HttpExchange) {
        try {
            val entry = entries[ex.requestURI.path.removePrefix("/s/")]
            if (entry == null || ex.requestMethod !in setOf("GET", "HEAD")) { ex.sendResponseHeaders(404, -1); return }
            val range = ex.requestHeaders.getFirst("Range")
            var resp = open(entry, ex.requestMethod, range)
            if (resp.statusCode() in setOf(401, 403, 404, 410)) {
                resp.body().close()
                val fresh = runCatching { entry.renew() }.getOrNull()?.takeIf { it.url.isNotEmpty() }
                if (fresh != null) { entry.stream = fresh; resp = open(entry, ex.requestMethod, range) }
            }
            val h = resp.headers()
            var body = resp.body()
            var type = entry.type
            val start = range?.let { Regex("bytes=(\\d+)-").find(it)?.groupValues?.get(1)?.toLongOrNull() }
            if (ex.requestMethod == "GET" && resp.statusCode() in 200..206 && (range == null || start == 0L)) {
                val pb = java.io.PushbackInputStream(body, 16)
                val head = pb.readNBytes(16)
                pb.unread(head)
                body = pb
                sniff(head)?.let { entry.type = it; type = it }
                if (type == null && head.isNotEmpty() && (head[0] == '<'.code.toByte() || head[0] == '{'.code.toByte())) {
                    System.err.println("stream proxy: the server sent text, not audio: " + String(head, Charsets.ISO_8859_1))
                }
            }
            val upstreamType = h.firstValue("Content-Type").orElse(null)
            val sent = type ?: entry.stream.mimeType ?: upstreamType ?: "application/octet-stream"
            ex.responseHeaders.add("Content-Type", sent)
            listOf("Content-Range", "Accept-Ranges", "Last-Modified", "ETag").forEach { name ->
                h.firstValue(name).ifPresent { ex.responseHeaders.add(name, it) }
            }
            val length = h.firstValueAsLong("Content-Length").orElse(-1)
            System.err.println("stream proxy: ${ex.requestMethod} range=${range ?: "-"} -> ${resp.statusCode()} type=$sent (server said ${upstreamType ?: "-"}) length=$length")
            val head = ex.requestMethod == "HEAD"
            if (head) {
                length.takeIf { it >= 0 }?.let { ex.responseHeaders.add("Content-Length", it.toString()) }
                ex.sendResponseHeaders(resp.statusCode(), -1)
            } else {
                // 0 = chunked, for an upstream that does not say how long it is.
                ex.sendResponseHeaders(resp.statusCode(), if (length >= 0) length else 0)
                body.use { it.copyTo(ex.responseBody) }
            }
        } catch (_: IOException) {
            // The player closed the connection (a seek, a skip): nothing to report.
        } catch (e: Exception) {
            System.err.println("stream proxy: ${e.message}")
        } finally {
            ex.close()
        }
    }

    /** The audio type of a file from its first bytes, null when they say nothing. */
    private fun sniff(b: ByteArray): String? {
        if (b.size < 4) return null
        val head = String(b, Charsets.ISO_8859_1)
        fun u(i: Int) = b[i].toInt() and 0xFF
        return when {
            head.startsWith("fLaC") -> "audio/flac"
            head.startsWith("OggS") -> "audio/ogg"
            head.startsWith("RIFF") -> "audio/wav"
            head.startsWith("ID3") -> "audio/mpeg"
            b.size >= 8 && head.substring(4, 8) == "ftyp" -> "audio/mp4"
            u(0) == 0x1A && u(1) == 0x45 && u(2) == 0xDF && u(3) == 0xA3 -> "audio/webm"
            u(0) == 0xFF && (u(1) and 0xF6) == 0xF0 -> "audio/aac"
            u(0) == 0xFF && (u(1) and 0xE0) == 0xE0 -> "audio/mpeg"
            else -> null
        }
    }

    override fun close() = server.stop(0)
}
