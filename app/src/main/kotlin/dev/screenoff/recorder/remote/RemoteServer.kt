package dev.screenoff.recorder.remote

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import dev.screenoff.core.RecMode
import dev.screenoff.recorder.Diag
import dev.screenoff.recorder.Graph
import dev.screenoff.recorder.RecorderBus
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject

/**
 * Small HTTP server for live viewing and control from another device, meant to
 * be reached over Tailscale. Every page except the login needs the PIN cookie.
 * No dependencies: plain sockets, one thread per connection, at most 8.
 */
class RemoteServer(private val context: Context, private val pin: String, private val port: Int = PORT) {

    private val token = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val pool = Executors.newFixedThreadPool(MAX_CONNECTIONS)
    private val open = AtomicInteger(0)
    private var server: ServerSocket? = null
    @Volatile private var running = false
    @Volatile private var failedLogins = 0

    fun start() {
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(port))
        server = s
        running = true
        Thread({ acceptLoop(s) }, "remote-accept").apply { isDaemon = true }.start()
        Diag.log("remote viewing on port $port, addresses ${addresses().joinToString()}")
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        pool.shutdownNow()
        LiveHub.clear()
    }

    private fun acceptLoop(s: ServerSocket) {
        while (running) {
            val socket = try {
                s.accept()
            } catch (e: IOException) {
                break
            }
            if (open.incrementAndGet() > MAX_CONNECTIONS) {
                open.decrementAndGet()
                runCatching { socket.close() }
                continue
            }
            pool.execute {
                try {
                    socket.use { handle(it) }
                } catch (e: Exception) {
                    // Viewer went away. Nothing to do.
                } finally {
                    open.decrementAndGet()
                }
            }
        }
    }

    private class Request(val method: String, val path: String, val query: Map<String, String>, val headers: Map<String, String>, val body: String)

    private fun handle(socket: Socket) {
        socket.soTimeout = 15_000
        val input = BufferedInputStream(socket.getInputStream())
        val out = socket.getOutputStream()
        val req = readRequest(input) ?: return
        val authed = cookie(req.headers["cookie"], COOKIE) == token

        when {
            req.path == "/login" && req.method == "POST" -> login(req, out)
            !authed && req.path.startsWith("/api/") -> send(out, 401, "application/json", """{"error":"login"}""")
            !authed && (req.path == "/live.mjpg" || req.path == "/snap.jpg") -> send(out, 401, "text/plain", "login")
            !authed -> send(out, 200, "text/html; charset=utf-8", Pages.login(error = false))
            req.path == "/" -> send(out, 200, "text/html; charset=utf-8", Pages.DASHBOARD)
            req.path == "/api/status" -> send(out, 200, "application/json", status().toString())
            req.path == "/api/mode" && req.method == "POST" -> {
                val mode = runCatching { RecMode.valueOf(req.query["m"] ?: "") }.getOrNull()
                val ok = mode != null && RemoteBridge.send(RemoteBridge.Command.Mode(mode))
                send(out, if (ok) 200 else 409, "application/json", """{"ok":$ok}""")
            }
            req.path == "/api/keep" && req.method == "POST" -> {
                val ok = RemoteBridge.send(RemoteBridge.Command.Keep)
                send(out, if (ok) 200 else 409, "application/json", """{"ok":$ok}""")
            }
            req.path == "/snap.jpg" -> snapshot(out)
            req.path == "/live.mjpg" -> live(socket, out)
            req.path == "/logout" -> {
                val headers = "Set-Cookie: $COOKIE=; Path=/; Max-Age=0\r\nLocation: /\r\n"
                send(out, 302, "text/plain", "", headers)
            }
            else -> send(out, 404, "text/plain", "not found")
        }
    }

    private fun login(req: Request, out: OutputStream) {
        val given = form(req.body)["pin"] ?: ""
        if (pin.isNotEmpty() && constantTimeEquals(given, pin)) {
            failedLogins = 0
            val headers = "Set-Cookie: $COOKIE=$token; Path=/; HttpOnly; SameSite=Strict; Max-Age=2592000\r\nLocation: /\r\n"
            send(out, 302, "text/plain", "", headers)
            Diag.log("remote login ok")
        } else {
            failedLogins++
            Diag.log("remote login refused ($failedLogins in a row)")
            // Slow down guessing: 1 s, then longer for repeated failures.
            Thread.sleep((1000L * failedLogins).coerceAtMost(10_000L))
            send(out, 200, "text/html; charset=utf-8", Pages.login(error = true))
        }
    }

    private fun status(): JSONObject {
        val s = RecorderBus.status.value
        val c = Graph.config.config.value
        val clips = Graph.index.snapshot()
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return JSONObject()
            .put("state", s.state.name)
            .put("mode", s.mode?.name ?: JSONObject.NULL)
            .put("segmentStartedAtMs", s.segmentStartedAtMs ?: JSONObject.NULL)
            .put("serverTimeMs", System.currentTimeMillis())
            .put("error", s.lastError ?: JSONObject.NULL)
            .put("motionChanged", s.motionChanged)
            .put("motionNeeded", s.motionNeeded)
            .put("rotation", LiveHub.rotationDegrees)
            .put("battery", if (level >= 0 && scale > 0) level * 100 / scale else JSONObject.NULL)
            .put("charging", (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0)
            .put("clips", clips.size)
            .put("usedBytes", clips.sumOf { it.sizeBytes })
            .put("capBytes", c.capBytes)
            .put("audio", c.audioEnabled)
    }

    private fun snapshot(out: OutputStream) {
        LiveHub.snapshotWanted = true
        val frame = LiveHub.await(after = -1, timeoutMs = 3_000)
        if (frame == null) {
            send(out, 503, "text/plain", "camera not ready")
        } else {
            sendBytes(out, 200, "image/jpeg", frame.second)
        }
    }

    private fun live(socket: Socket, out: OutputStream) {
        socket.soTimeout = 0
        out.write(
            ("HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=frame\r\n" +
                "Cache-Control: no-cache, no-store\r\nPragma: no-cache\r\nConnection: close\r\n\r\n").toByteArray()
        )
        out.flush()
        LiveHub.viewers.incrementAndGet()
        try {
            var last = -1L
            var idle = 0
            while (running) {
                val frame = LiveHub.await(last, timeoutMs = 2_000)
                if (frame == null) {
                    // Camera paused (switching). Keep the connection, give up after a minute.
                    if (++idle > 30) break
                    continue
                }
                idle = 0
                last = frame.first
                val jpeg = frame.second
                out.write("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpeg.size}\r\n\r\n".toByteArray())
                out.write(jpeg)
                out.write("\r\n".toByteArray())
                out.flush()
            }
        } catch (e: IOException) {
            // Viewer closed the page.
        } finally {
            LiveHub.viewers.decrementAndGet()
        }
    }

    private fun readRequest(input: InputStream): Request? {
        val first = readLine(input) ?: return null
        val parts = first.split(" ")
        if (parts.size < 2) return null
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
            if (headers.size > 64) return null
        }
        val length = headers["content-length"]?.toIntOrNull()?.coerceIn(0, 4096) ?: 0
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = try {
                input.read(body, read, length - read)
            } catch (e: SocketTimeoutException) {
                -1
            }
            if (n < 0) break
            read += n
        }
        val target = parts[1]
        val q = target.indexOf('?')
        val path = if (q >= 0) target.substring(0, q) else target
        val query = if (q >= 0) form(target.substring(q + 1)) else emptyMap()
        return Request(parts[0].uppercase(), path, query, headers, String(body, 0, read))
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
            if (sb.length > 8192) return null
        }
    }

    private fun send(out: OutputStream, code: Int, type: String, body: String, extraHeaders: String = "") =
        sendBytes(out, code, type, body.toByteArray(), extraHeaders)

    private fun sendBytes(out: OutputStream, code: Int, type: String, body: ByteArray, extraHeaders: String = "") {
        val reason = when (code) {
            200 -> "OK"
            302 -> "Found"
            401 -> "Unauthorized"
            404 -> "Not Found"
            409 -> "Conflict"
            else -> "Error"
        }
        out.write(
            ("HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" +
                "Cache-Control: no-store\r\nX-Frame-Options: DENY\r\nConnection: close\r\n$extraHeaders\r\n").toByteArray()
        )
        out.write(body)
        out.flush()
    }

    companion object {
        const val PORT = 8090
        private const val MAX_CONNECTIONS = 8
        private const val COOKIE = "screenoff"

        fun newPin(): String = (SecureRandom().nextInt(900_000) + 100_000).toString()

        /** This phone's IPv4 addresses. Tailscale ones start with 100. */
        fun addresses(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .map { it.hostAddress ?: "" }
                .filter { it.isNotEmpty() }
                .sortedByDescending { it.startsWith("100.") }
        }.getOrDefault(emptyList())

        private fun form(s: String): Map<String, String> = s.split('&').mapNotNull {
            val i = it.indexOf('=')
            if (i <= 0) null else runCatching {
                URLDecoder.decode(it.substring(0, i), "UTF-8") to URLDecoder.decode(it.substring(i + 1), "UTF-8")
            }.getOrNull()
        }.toMap()

        private fun cookie(header: String?, name: String): String? = header?.split(';')
            ?.map { it.trim() }?.firstOrNull { it.startsWith("$name=") }?.substringAfter('=')

        private fun constantTimeEquals(a: String, b: String): Boolean =
            MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
    }
}
