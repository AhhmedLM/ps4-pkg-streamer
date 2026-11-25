package com.ps4pkg.ps4_pkg_streamer

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class GoldHenInstallResult(
    val success: Boolean,
    val title: String,
    val contentId: String,
    val packageSize: Long,
    val manifestUrl: String,
    val proxyUrl: String,
    val streamId: String,
    val error: String? = null,
)

/**
 * Direct GoldHEN BinLoader (port 9090 / 9021 / 9020) PKG installer.
 * Works on PS4 GoldHEN 5.05 through 14.02+ without requiring the legacy flatz RPI app.
 * Registers the PKG directly with PS4's native BGFT download manager in the background.
 */
object GoldHenInstaller {

    private val PAYLOAD_MARKER = byteArrayOf(
        0xB4.toByte(), 0xB4.toByte(), 0xB4.toByte(),
        0xB4.toByte(), 0xB4.toByte(), 0xB4.toByte()
    )

    fun installViaBinLoader(
        context: Context,
        proxy: StreamingHttpProxy,
        ps4Ip: String,
        upstreamUrl: String,
        headers: Map<String, String>,
        onLog: (String) -> Unit,
    ): GoldHenInstallResult {
        var callbackServer: ServerSocket? = null
        var ps4Callback: Socket? = null

        try {
            // 1. Read first 4096 bytes of the remote PKG to extract metadata (zero disk usage)
            onLog("قراءة ترويسة ملف الـ PKG عن بُعد...")
            val meta = fetchPkgMetadata(proxy, upstreamUrl, headers)
            val sizeMb = meta.packageSize / (1024.0 * 1024.0)
            onLog("تم التعرف على الحزمة: ${meta.contentId} (${String.format("%.1f", sizeMb)} MB • ${meta.bgftType})")

            // 2. Register stream + BGFT manifest JSON on our local StreamingHttpProxy
            // Using contentId as deterministic ID so PS4 Resume works even after service restart or link refresh
            val entry = proxy.registerStream(
                url = upstreamUrl,
                fileName = meta.friendlyName,
                headers = headers,
                preferredId = meta.contentId,
            )
            val localIp = StreamingHttpProxy.getLocalIpAddress()
            val proxyUrl = "http://$localIp:${proxy.getPort()}/pkg/${entry.id}.pkg"

            val manifestJson = buildManifestJson(
                pkgUrl = proxyUrl,
                packageSize = meta.packageSize,
                digestHex = meta.digestHex,
            )
            val manifestUrl = proxy.registerJsonManifest(entry.id, manifestJson)
            onLog("تم تجهيز ملف التعريف المحلي: $manifestUrl")

            // 3. Load and patch GoldHEN DPI payload with our phone's IP and ephemeral callback port
            val payloadBytes = context.assets.open("goldhen_dpi_payload.bin").use { it.readBytes() }
            val markerOffset = findMarker(payloadBytes, PAYLOAD_MARKER)
            if (markerOffset < 0) {
                throw IOException("Payload marker not found in goldhen_dpi_payload.bin")
            }

            callbackServer = ServerSocket(0, 5, InetAddress.getByName("0.0.0.0")).apply {
                reuseAddress = true
                soTimeout = 12_000
            }
            val callbackPort = callbackServer.localPort

            val ipBytes = InetAddress.getByName(localIp).address
            if (ipBytes.size != 4) {
                throw IOException("عنوان IP للجوال غير صالح (IPv4 مطلوب): $localIp")
            }
            System.arraycopy(ipBytes, 0, payloadBytes, markerOffset, 4)
            // Network byte order (Big-Endian) for port
            payloadBytes[markerOffset + 4] = ((callbackPort shr 8) and 0xFF).toByte()
            payloadBytes[markerOffset + 5] = (callbackPort and 0xFF).toByte()

            // 4. Send patched payload to GoldHEN BinLoader (9090, fallback 9021, 9020)
            onLog("الاتصال بـ GoldHEN BinLoader على $ps4Ip (المنفذ 9090)...")
            val sentPort = sendPayloadToGoldHen(ps4Ip, payloadBytes)
                ?: throw IOException(
                    "تعذر الاتصال بـ GoldHEN BinLoader على $ps4Ip:9090. تأكد من تفعيل BinLoader Server في إعدادات GoldHEN."
                )
            onLog("تم إرسال الـ Payload إلى GoldHEN (المنفذ $sentPort)، بانتظار استجابة الـ PS4...")

            // 5. Wait for PS4 payload to connect back to our phone
            ps4Callback = callbackServer.accept().apply {
                tcpNoDelay = true
                soTimeout = 10_000
            }
            onLog("اتصل الـ PS4 بالخدمة! جاري تسجيل التحميل في نظام الـ PS4 (BGFT)...")

            // 6. Send binary PKG registration packet (Little-Endian)
            val packet = buildPkgPacket(
                manifestUrl = manifestUrl,
                friendlyName = meta.friendlyName,
                contentId = meta.contentId,
                bgftType = meta.bgftType,
                packageSize = meta.packageSize,
            )
            val out = ps4Callback.getOutputStream()
            out.write(packet)
            out.flush()

            // Give the PS4 payload a moment to register & start BGFT task, then send clean exit (0)
            Thread.sleep(700L)
            try {
                out.write(ByteArray(4)) // 0u = Service Exit
                out.flush()
            } catch (_: Exception) {
            }

            onLog("✅ تم تسجيل المهمة في GoldHEN بنجاح! تابع إشعارات التنزيل على الـ PS4.")
            return GoldHenInstallResult(
                success = true,
                title = meta.friendlyName,
                contentId = meta.contentId,
                packageSize = meta.packageSize,
                manifestUrl = manifestUrl,
                proxyUrl = proxyUrl,
                streamId = entry.id,
            )
        } catch (e: Exception) {
            val errMsg = e.message ?: "خطأ غير معروف أثناء الإرسال عبر GoldHEN"
            onLog("❌ فشل GoldHEN BinLoader: $errMsg")
            return GoldHenInstallResult(
                success = false,
                title = "",
                contentId = "",
                packageSize = 0,
                manifestUrl = "",
                proxyUrl = "",
                streamId = "",
                error = errMsg,
            )
        } finally {
            try {
                ps4Callback?.close()
            } catch (_: Exception) {
            }
            try {
                callbackServer?.close()
            } catch (_: Exception) {
            }
        }
    }

    private data class PkgMeta(
        val contentId: String,
        val friendlyName: String,
        val bgftType: String,
        val packageSize: Long,
        val digestHex: String,
    )

    private fun fetchPkgMetadata(
        proxy: StreamingHttpProxy,
        urlStr: String,
        headers: Map<String, String>,
    ): PkgMeta {
        val conn = proxy.openUpstreamConnection(
            urlStr = urlStr.trim(),
            method = "GET",
            rangeHeader = "bytes=0-4095",
            customHeaders = headers,
        )
        try {
            val code = conn.responseCode
            if (code !in listOf(200, 206)) {
                throw IOException("فشل قراءة الملف من السيرفر (HTTP $code)")
            }

            val totalFromHttp = proxy.parseTotalLengthFromContentRange(
                conn.getHeaderField("Content-Range")
            ) ?: conn.contentLengthLong

            val headerBuf = ByteArray(4096)
            var totalRead = 0
            conn.inputStream.use { input ->
                while (totalRead < headerBuf.size) {
                    val r = input.read(headerBuf, totalRead, headerBuf.size - totalRead)
                    if (r == -1) break
                    totalRead += r
                }
            }

            if (totalRead < 0x100 ||
                headerBuf[0] != 0x7F.toByte() ||
                headerBuf[1] != 0x43.toByte() ||
                headerBuf[2] != 0x4E.toByte() ||
                headerBuf[3] != 0x54.toByte()
            ) {
                throw IOException("الرابط لا يشير إلى ملف PS4 PKG صالح (ترويسة 0x7F CNT غير موجودة)")
            }

            val bb = ByteBuffer.wrap(headerBuf).order(ByteOrder.BIG_ENDIAN)

            // 0x18: pkg_content_type (uint32 BE)
            val contentTypeVal = bb.getInt(0x18)
            val bgftType = when (contentTypeVal) {
                0x1A -> "PS4GD" // Game Data
                0x1B -> "PS4AC" // Additional Content (DLC)
                0x1C -> "PS4DP" // Delta Patch
                0x1D -> "PS4AL" // App License / Theme
                else -> "PS4GD"
            }

            // 0x40..0x63: content_id (36 bytes ASCII)
            val contentIdRaw = String(headerBuf, 0x40, 36, Charsets.US_ASCII)
            val contentId = contentIdRaw.trim('\u0000', ' ').ifBlank {
                "UP0000-CUSA00000_00-0000000000000000"
            }

            // 0x418: pkg_size (uint64 BE) if available, else HTTP total size
            val sizeFromHeader = if (totalRead >= 0x420) bb.getLong(0x418) else -1L
            val packageSize = when {
                totalFromHttp > 0 -> totalFromHttp
                sizeFromHeader > 0 -> sizeFromHeader
                else -> throw IOException("تعذر تحديد حجم ملف الـ PKG")
            }

            // 0xFE0..0xFFF: package digest (32 bytes) if present
            val digestHex = if (totalRead >= 0x1000) {
                headerBuf.sliceArray(0xFE0 until 0x1000)
                    .joinToString("") { "%02x".format(it) }
            } else {
                "0".repeat(64)
            }

            // Derive friendly name from URL filename or content_id
            val urlName = urlStr.substringBefore("?").substringAfterLast('/')
                .replace("%20", " ")
                .removeSuffix(".pkg")
                .removeSuffix(".PKG")
                .trim()
            val friendlyName = if (urlName.isNotBlank() && urlName.length <= 64) {
                urlName
            } else {
                contentId
            }

            return PkgMeta(
                contentId = contentId,
                friendlyName = friendlyName,
                bgftType = bgftType,
                packageSize = packageSize,
                digestHex = digestHex,
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun buildManifestJson(
        pkgUrl: String,
        packageSize: Long,
        digestHex: String,
    ): String {
        val escapedUrl = pkgUrl.replace("\\", "\\\\").replace("\"", "\\\"")
        return "{\"originalFileSize\":$packageSize," +
            "\"packageDigest\":\"$digestHex\"," +
            "\"numberOfSplitFiles\":1," +
            "\"pieces\":[{\"url\":\"$escapedUrl\"," +
            "\"fileOffset\":0," +
            "\"fileSize\":$packageSize," +
            "\"hashValue\":\"0000000000000000000000000000000000000000\"}]}"
    }

    private fun sendPayloadToGoldHen(ps4Ip: String, payload: ByteArray): Int? {
        val ports = intArrayOf(9090, 9021, 9020)
        for (port in ports) {
            var socket: Socket? = null
            try {
                socket = Socket()
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(ps4Ip, port), 3000)
                socket.soTimeout = 5000
                socket.getOutputStream().use { out ->
                    out.write(payload)
                    out.flush()
                }
                return port
            } catch (_: Exception) {
                // Try next BinLoader port
            } finally {
                try {
                    socket?.close()
                } catch (_: Exception) {
                }
            }
        }
        return null
    }

    private fun buildPkgPacket(
        manifestUrl: String,
        friendlyName: String,
        contentId: String,
        bgftType: String,
        packageSize: Long,
    ): ByteArray {
        val out = ByteArrayOutputStream()

        fun writeIntLE(v: Int) {
            val b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
            out.write(b)
        }

        fun writeLongLE(v: Long) {
            val b = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array()
            out.write(b)
        }

        fun writeString(s: String) {
            val bytes = s.toByteArray(Charsets.UTF_8)
            writeIntLE(bytes.size)
            out.write(bytes)
        }

        // Command: 1 = New Package
        writeIntLE(1)
        writeString(manifestUrl)
        writeString(friendlyName)
        writeString(contentId)
        writeString(bgftType)
        writeLongLE(packageSize)
        // Icon length = 0
        writeIntLE(0)

        return out.toByteArray()
    }

    private fun findMarker(data: ByteArray, marker: ByteArray): Int {
        outer@ for (i in 0..(data.size - marker.size)) {
            for (j in marker.indices) {
                if (data[i + j] != marker[j]) continue@outer
            }
            return i
        }
        return -1
    }
}
