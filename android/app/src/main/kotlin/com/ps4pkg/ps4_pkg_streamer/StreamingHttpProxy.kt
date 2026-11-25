package com.ps4pkg.ps4_pkg_streamer

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

data class StreamEntry(
    val id: String,
    val url: String,
    val fileName: String,
    val headers: Map<String, String> = emptyMap(),
)

data class ProxyStats(
    val running: Boolean,
    val port: Int,
    val localIp: String,
    val activeConnections: Int,
    val totalBytesTransferred: Long,
    val bytesPerSecond: Long,
)

data class ProbeResult(
    val ok: Boolean,
    val statusCode: Int,
    val contentType: String,
    val contentLength: Long,
    val supportsRange: Boolean,
    val isValidPkgHeader: Boolean,
    val finalUrl: String,
    val message: String,
)

/**
 * Lightweight, zero-disk-usage HTTP/1.1 streaming proxy with full Range/Resume (206 Partial Content),
 * HTTP Basic Auth, and cross-protocol redirect (HTTPS -> HTTP) support for PS4 RPI.
 */
class StreamingHttpProxy(
    private val port: Int = 8888,
    private val onLog: ((String) -> Unit)? = null,
    private val onStateChanged: ((Map<String, StreamEntry>, Map<String, String>) -> Unit)? = null,
) {

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var workerPool: ExecutorService? = null

    private val entries = ConcurrentHashMap<String, StreamEntry>()
    private val jsonManifests = ConcurrentHashMap<String, String>()
    private val activeConnections = AtomicInteger(0)
    private val totalBytesTransferred = AtomicLong(0)
    private val intervalBytes = AtomicLong(0)
    private var lastSpeedCalcTimeMs = System.currentTimeMillis()
    @Volatile
    private var lastCalculatedSpeedBps: Long = 0

    private fun log(msg: String) {
        onLog?.invoke(msg)
    }

    fun isRunning(): Boolean = running.get()

    fun getPort(): Int = serverSocket?.localPort ?: port

    fun restoreState(savedEntries: Map<String, StreamEntry>, savedManifests: Map<String, String>) {
        entries.putAll(savedEntries)
        jsonManifests.putAll(savedManifests)
    }

    fun start(): Boolean {
        if (running.getAndSet(true)) return true
        return try {
            val socket = ServerSocket(port, 50, InetAddress.getByName("0.0.0.0"))
            socket.reuseAddress = true
            serverSocket = socket
            workerPool = Executors.newCachedThreadPool()

            acceptThread = Thread({
                while (running.get()) {
                    try {
                        val client = socket.accept()
                        client.tcpNoDelay = true
                        client.soTimeout = 60_000
                        workerPool?.execute { handleClient(client) }
                    } catch (_: IOException) {
                        if (!running.get()) break
                    }
                }
            }, "PS4PkgProxyAccept")
            acceptThread?.start()
            true
        } catch (e: Exception) {
            running.set(false)
            false
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        workerPool?.shutdownNow()
        workerPool = null
        acceptThread = null
        activeConnections.set(0)
    }

    fun registerStream(
        url: String,
        fileName: String?,
        headers: Map<String, String>,
        preferredId: String? = null,
    ): StreamEntry {
        val id = preferredId?.replace(Regex("[^a-zA-Z0-9_-]"), "_")?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString().replace("-", "").take(12)
        val sanitizedName = sanitizeFileName(fileName, url)
        val entry = StreamEntry(
            id = id,
            url = url.trim(),
            fileName = sanitizedName,
            headers = headers,
        )
        entries[id] = entry
        onStateChanged?.invoke(entries.toMap(), jsonManifests.toMap())
        return entry
    }

    fun registerJsonManifest(id: String, jsonBody: String): String {
        jsonManifests[id] = jsonBody
        onStateChanged?.invoke(entries.toMap(), jsonManifests.toMap())
        return "http://${getLocalIpAddress()}:${getPort()}/json/$id.json"
    }

    fun unregisterStream(id: String) {
        entries.remove(id)
        jsonManifests.remove(id)
        onStateChanged?.invoke(entries.toMap(), jsonManifests.toMap())
    }

    fun resetStats() {
        totalBytesTransferred.set(0)
        intervalBytes.set(0)
        lastCalculatedSpeedBps = 0
        lastSpeedCalcTimeMs = System.currentTimeMillis()
    }

    fun snapshotStats(): ProxyStats {
        val now = System.currentTimeMillis()
        val elapsedMs = now - lastSpeedCalcTimeMs
        if (elapsedMs >= 500) {
            val bytes = intervalBytes.getAndSet(0)
            lastCalculatedSpeedBps = (bytes * 1000L) / elapsedMs
            lastSpeedCalcTimeMs = now
        }
        return ProxyStats(
            running = running.get(),
            port = getPort(),
            localIp = getLocalIpAddress(),
            activeConnections = activeConnections.get(),
            totalBytesTransferred = totalBytesTransferred.get(),
            bytesPerSecond = lastCalculatedSpeedBps,
        )
    }

    private fun handleClient(client: Socket) {
        activeConnections.incrementAndGet()
        try {
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            val out = BufferedOutputStream(client.getOutputStream(), 65536)

            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) {
                writeError(out, 400, "Bad Request")
                return
            }

            val method = parts[0].uppercase()
            val rawPath = parts[1].substringBefore("?")

            val requestHeaders = mutableMapOf<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) {
                    val key = line.substring(0, idx).trim().lowercase()
                    val value = line.substring(idx + 1).trim()
                    requestHeaders[key] = value
                }
            }

            if (rawPath == "/health") {
                val body = "OK".toByteArray()
                val header = "HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                out.write(header.toByteArray())
                out.write(body)
                out.flush()
                return
            }

            // Serve GoldHEN BGFT manifest JSON: /json/<id>.json
            val jsonMatch = Regex("^/json/([a-zA-Z0-9_-]+)\\.json$").matchEntire(rawPath)
            if (jsonMatch != null) {
                val jsonId = jsonMatch.groupValues[1]
                val jsonStr = jsonManifests[jsonId]
                if (jsonStr == null) {
                    writeError(out, 404, "Manifest Not Found")
                    return
                }
                val body = jsonStr.toByteArray(Charsets.UTF_8)
                val header = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/json\r\n" +
                    "Content-Length: ${body.size}\r\n" +
                    "Connection: close\r\n\r\n"
                out.write(header.toByteArray())
                if (method != "HEAD") {
                    out.write(body)
                }
                out.flush()
                log("PS4 استلم ملف التعريف JSON بنجاح")
                return
            }

            // Expected path: /pkg/<id>.pkg or /pkg/<id>
            val match = Regex("^/pkg/([a-zA-Z0-9_-]+)(?:\\.pkg)?$").matchEntire(rawPath)
            if (match == null) {
                writeError(out, 404, "Not Found")
                return
            }

            val id = match.groupValues[1]
            val entry = entries[id]
            if (entry == null) {
                writeError(out, 404, "Stream Not Registered")
                return
            }

            if (method != "GET" && method != "HEAD") {
                writeError(out, 405, "Method Not Allowed")
                return
            }

            proxyUpstream(method, entry, requestHeaders["range"], out)
        } catch (_: IOException) {
            // Client disconnected (e.g. PS4 paused or canceled the download)
        } finally {
            activeConnections.decrementAndGet()
            try {
                client.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun proxyUpstream(
        method: String,
        entry: StreamEntry,
        rangeHeader: String?,
        out: BufferedOutputStream,
    ) {
        var conn: HttpURLConnection? = null
        try {
            log("PS4 طلب $method ${if (rangeHeader != null) "($rangeHeader)" else ""}")
            conn = openUpstreamConnection(
                urlStr = entry.url,
                method = method,
                rangeHeader = rangeHeader,
                customHeaders = entry.headers,
            )

            var statusCode = conn.responseCode

            // Some file hosts reject HEAD (405/403). Fallback to GET with Range: bytes=0-0 to probe size.
            if (method == "HEAD" && (statusCode == 405 || statusCode == 403 || statusCode == 501)) {
                conn.disconnect()
                conn = openUpstreamConnection(
                    urlStr = entry.url,
                    method = "GET",
                    rangeHeader = "bytes=0-0",
                    customHeaders = entry.headers,
                )
                statusCode = conn.responseCode
                if (statusCode !in listOf(200, 206)) {
                    log("فشل التحميل من السيرفر: HTTP $statusCode (${conn.responseMessage ?: ""})")
                    writeError(out, statusCode, conn.responseMessage ?: "Upstream Error")
                    return
                }
                val totalLength = parseTotalLengthFromContentRange(conn.getHeaderField("Content-Range"))
                    ?: conn.contentLengthLong
                val headersOut = buildString {
                    append("HTTP/1.1 200 OK\r\n")
                    if (totalLength >= 0) append("Content-Length: $totalLength\r\n")
                    append("Accept-Ranges: bytes\r\n")
                    append("Content-Type: application/octet-stream\r\n")
                    append("Content-Disposition: attachment; filename=\"${entry.fileName}\"\r\n")
                    append("Connection: close\r\n\r\n")
                }
                out.write(headersOut.toByteArray())
                out.flush()
                return
            }

            if (statusCode !in listOf(200, 206)) {
                val hint = when (statusCode) {
                    401 -> "يتطلب اسم مستخدم وكلمة مرور (HTTP 401)"
                    403 -> "ممنوع الوصول أو يتطلب Cookie/كلمة مرور (HTTP 403)"
                    404 -> "الملف غير موجود على السيرفر (HTTP 404)"
                    else -> "خطأ من السيرفر: HTTP $statusCode"
                }
                log("⚠️ $hint")
                writeError(out, statusCode, conn.responseMessage ?: "Upstream Error")
                return
            }

            val contentType = conn.contentType ?: ""
            if (contentType.contains("text/html", ignoreCase = true)) {
                log("⚠️ تحذير: الرابط يرجّع صفحة HTML وليس ملف PKG مباشر (قد يتطلب كلمة مرور في المتصفح)")
            }

            val contentLength = conn.contentLengthLong
            val contentRange = conn.getHeaderField("Content-Range")
            val statusText = if (statusCode == 206) "Partial Content" else "OK"
            log("السيرفر استجاب: HTTP $statusCode (${if (contentLength > 0) "${contentLength / (1024 * 1024)} MB" else "stream"})")

            val responseHeader = buildString {
                append("HTTP/1.1 $statusCode $statusText\r\n")
                if (contentLength >= 0) {
                    append("Content-Length: $contentLength\r\n")
                }
                if (statusCode == 206 && !contentRange.isNullOrBlank()) {
                    append("Content-Range: $contentRange\r\n")
                }
                append("Accept-Ranges: bytes\r\n")
                append("Content-Type: application/octet-stream\r\n")
                append("Content-Disposition: attachment; filename=\"${entry.fileName}\"\r\n")
                append("Connection: close\r\n\r\n")
            }

            out.write(responseHeader.toByteArray())
            out.flush()

            if (method == "HEAD") return

            BufferedInputStream(conn.inputStream, 65536).use { input ->
                val buffer = ByteArray(65536)
                while (running.get()) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    out.write(buffer, 0, read)
                    totalBytesTransferred.addAndGet(read.toLong())
                    intervalBytes.addAndGet(read.toLong())
                }
                out.flush()
            }
        } catch (e: IOException) {
            log("خطأ في البث: ${e.message ?: "انقطع الاتصال"}")
            try {
                writeError(out, 502, "Bad Gateway: ${e.message ?: "Upstream failure"}")
            } catch (_: Exception) {
            }
        } finally {
            conn?.disconnect()
        }
    }

    fun probeUrl(urlStr: String, customHeaders: Map<String, String>): ProbeResult {
        var conn: HttpURLConnection? = null
        return try {
            conn = openUpstreamConnection(
                urlStr = urlStr.trim(),
                method = "GET",
                rangeHeader = "bytes=0-15",
                customHeaders = customHeaders,
            )
            val code = conn.responseCode
            val contentType = conn.contentType ?: "unknown"
            val finalUrl = conn.url?.toString() ?: urlStr
            val contentRange = conn.getHeaderField("Content-Range")
            val totalLength = parseTotalLengthFromContentRange(contentRange) ?: conn.contentLengthLong
            val supportsRange = code == 206 ||
                (conn.getHeaderField("Accept-Ranges")?.contains("bytes", ignoreCase = true) == true)

            if (code == 401) {
                return ProbeResult(
                    ok = false,
                    statusCode = 401,
                    contentType = contentType,
                    contentLength = -1,
                    supportsRange = false,
                    isValidPkgHeader = false,
                    finalUrl = finalUrl,
                    message = "🔒 الرابط محمي بكلمة مرور (HTTP 401). أدخل اسم المستخدم وكلمة المرور في الخيارات المتقدمة.",
                )
            }
            if (code == 403) {
                return ProbeResult(
                    ok = false,
                    statusCode = 403,
                    contentType = contentType,
                    contentLength = -1,
                    supportsRange = false,
                    isValidPkgHeader = false,
                    finalUrl = finalUrl,
                    message = "🔒 السيرفر رفض الوصول (HTTP 403). قد يتطلب الرابط Cookie أو Referer أو تسجيل دخول.",
                )
            }
            if (code !in listOf(200, 206)) {
                return ProbeResult(
                    ok = false,
                    statusCode = code,
                    contentType = contentType,
                    contentLength = totalLength,
                    supportsRange = supportsRange,
                    isValidPkgHeader = false,
                    finalUrl = finalUrl,
                    message = "❌ السيرفر أعاد خطأ HTTP $code (${conn.responseMessage ?: ""}).",
                )
            }

            val magic = ByteArray(16)
            val bytesRead = try {
                conn.inputStream.use { it.read(magic) }
            } catch (_: Exception) {
                0
            }

            val isPkg = bytesRead >= 4 &&
                magic[0] == 0x7F.toByte() &&
                magic[1] == 0x43.toByte() && // 'C'
                magic[2] == 0x4E.toByte() && // 'N'
                magic[3] == 0x54.toByte()    // 'T'

            val isHtml = contentType.contains("text/html", ignoreCase = true) ||
                (bytesRead >= 5 && String(magic, 0, bytesRead).trimStart().startsWith("<"))

            val isZip = bytesRead >= 2 && magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte()
            val isRar = bytesRead >= 4 && String(magic, 0, 4) == "Rar!"

            val msg = when {
                isPkg -> {
                    val sizeMb = if (totalLength > 0) String.format("%.1f MB", totalLength / (1024.0 * 1024.0)) else "غير معروف"
                    val resumeTxt = if (supportsRange) "يدعم الاستئناف ✅" else "لا يدعم الاستئناف ⚠️"
                    "✅ ملف PS4 PKG صالح! الحجم: $sizeMb • $resumeTxt"
                }
                isHtml -> "⚠️ الرابط يفتح صفحة ويب (HTML) وليس ملف PKG مباشر! إذا كانت الصفحة تطلب باسوورد، افتحها في المتصفح وأدخل الباسوورد ثم انسخ رابط التحميل المباشر."
                isZip || isRar -> "📦 الملف مضغوط (ZIP/RAR) وليس ملف .pkg مباشر — جهاز PS4 يقبل ملفات .pkg فقط بعد فك الضغط."
                else -> "⚠️ الملف لا يبدأ بترويسة PS4 PKG القياسية (0x7F CNT). نوع المحتوى: $contentType"
            }

            ProbeResult(
                ok = isPkg,
                statusCode = code,
                contentType = contentType,
                contentLength = totalLength,
                supportsRange = supportsRange,
                isValidPkgHeader = isPkg,
                finalUrl = finalUrl,
                message = msg,
            )
        } catch (e: Exception) {
            ProbeResult(
                ok = false,
                statusCode = 0,
                contentType = "",
                contentLength = -1,
                supportsRange = false,
                isValidPkgHeader = false,
                finalUrl = urlStr,
                message = "❌ تعذر الاتصال بالرابط: ${e.message ?: "خطأ غير معروف"}",
            )
        } finally {
            conn?.disconnect()
        }
    }

    internal fun openUpstreamConnection(
        urlStr: String,
        method: String,
        rangeHeader: String?,
        customHeaders: Map<String, String>,
        maxRedirects: Int = 10,
    ): HttpURLConnection {
        var currentUrl = urlStr
        var redirects = 0

        while (true) {
            val url = URL(currentUrl)
            val userInfo = url.userInfo
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = method
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 60_000
                setRequestProperty(
                    "User-Agent",
                    customHeaders["User-Agent"]
                        ?: "Mozilla/5.0 (Linux; Android 14) PS4PkgStreamer/1.0",
                )
                setRequestProperty("Accept", "*/*")
                if (!userInfo.isNullOrBlank() && customHeaders.keys.none { it.equals("Authorization", ignoreCase = true) }) {
                    val encoded = android.util.Base64.encodeToString(
                        userInfo.toByteArray(Charsets.UTF_8),
                        android.util.Base64.NO_WRAP,
                    )
                    setRequestProperty("Authorization", "Basic $encoded")
                }
                if (!rangeHeader.isNullOrBlank()) {
                    setRequestProperty("Range", rangeHeader)
                }
                for ((k, v) in customHeaders) {
                    if (k.isNotBlank() && v.isNotBlank() && !k.equals("Range", ignoreCase = true)) {
                        setRequestProperty(k, v)
                    }
                }
            }

            val code = conn.responseCode
            if (code in listOf(301, 302, 303, 307, 308)) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank() || redirects >= maxRedirects) {
                    throw IOException("Too many redirects or missing Location header")
                }
                currentUrl = URL(url, location).toString()
                redirects++
                continue
            }

            return conn
        }
    }

    internal fun parseTotalLengthFromContentRange(contentRange: String?): Long? {
        if (contentRange.isNullOrBlank()) return null
        val slashIdx = contentRange.lastIndexOf('/')
        if (slashIdx == -1 || slashIdx == contentRange.length - 1) return null
        return contentRange.substring(slashIdx + 1).trim().toLongOrNull()
    }

    private fun writeError(out: BufferedOutputStream, code: Int, message: String) {
        val body = "$code $message\n".toByteArray()
        val resp = "HTTP/1.1 $code $message\r\n" +
            "Content-Type: text/plain\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n"
        out.write(resp.toByteArray())
        out.write(body)
        out.flush()
    }

    private fun sanitizeFileName(fileName: String?, url: String): String {
        val candidate = fileName?.takeIf { it.isNotBlank() }
            ?: run {
                val pathPart = url.substringBefore("?").substringAfterLast('/')
                pathPart.ifBlank { "package.pkg" }
            }
        val clean = candidate.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return if (clean.endsWith(".pkg", ignoreCase = true)) clean else "$clean.pkg"
    }

    companion object {
        fun getLocalIpAddress(): String {
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "127.0.0.1"
                var fallbackIp: String? = null
                for (intf in interfaces) {
                    if (!intf.isUp || intf.isLoopback) continue
                    for (addr in intf.inetAddresses) {
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            val ip = addr.hostAddress ?: continue
                            if (intf.name.startsWith("wlan") || intf.name.startsWith("eth")) {
                                return ip
                            }
                            if (fallbackIp == null) fallbackIp = ip
                        }
                    }
                }
                return fallbackIp ?: "127.0.0.1"
            } catch (_: Exception) {
                return "127.0.0.1"
            }
        }
    }
}
