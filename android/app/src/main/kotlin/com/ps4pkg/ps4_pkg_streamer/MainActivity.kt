package com.ps4pkg.ps4_pkg_streamer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var eventSink: EventChannel.EventSink? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            METHOD_CHANNEL
        ).setMethodCallHandler { call, result ->
            when (call.method) {
                "startService" -> {
                    val port = call.argument<Int>("port") ?: PkgProxyService.DEFAULT_PORT
                    requestNotificationPermissionIfNeeded()
                    startProxyService(port)
                    // Wait briefly for the service socket to bind, then return status
                    mainHandler.postDelayed({
                        result.success(buildStatusMap())
                    }, 250L)
                }

                "stopService" -> {
                    val intent = Intent(this, PkgProxyService::class.java).apply {
                        action = PkgProxyService.ACTION_STOP
                    }
                    startService(intent)
                    result.success(true)
                }

                "registerUrl" -> {
                    val url = call.argument<String>("url")
                    if (url.isNullOrBlank()) {
                        result.error("INVALID_URL", "URL is required", null)
                        return@setMethodCallHandler
                    }
                    val fileName = call.argument<String>("fileName")
                    @Suppress("UNCHECKED_CAST")
                    val headers = (call.argument<Map<String, Any?>>("headers") ?: emptyMap())
                        .mapNotNull { (k, v) -> v?.toString()?.let { k to it } }
                        .toMap()

                    val proxy = PkgProxyService.proxy
                    if (proxy == null || !proxy.isRunning()) {
                        result.error("SERVICE_NOT_RUNNING", "Proxy service is not running", null)
                        return@setMethodCallHandler
                    }

                    val entry = proxy.registerStream(url, fileName, headers)
                    val localIp = StreamingHttpProxy.getLocalIpAddress()
                    val proxyUrl = "http://$localIp:${proxy.getPort()}/pkg/${entry.id}.pkg"
                    result.success(
                        mapOf(
                            "id" to entry.id,
                            "proxyUrl" to proxyUrl,
                            "fileName" to entry.fileName,
                            "localIp" to localIp,
                            "port" to proxy.getPort(),
                        )
                    )
                }

                "unregisterUrl" -> {
                    val id = call.argument<String>("id")
                    if (!id.isNullOrBlank()) {
                        PkgProxyService.proxy?.unregisterStream(id)
                    }
                    result.success(true)
                }

                "resetStats" -> {
                    PkgProxyService.proxy?.resetStats()
                    result.success(true)
                }

                "getStatus" -> {
                    result.success(buildStatusMap())
                }

                "probeUrl" -> {
                    val url = call.argument<String>("url")
                    if (url.isNullOrBlank()) {
                        result.error("INVALID_URL", "URL is required", null)
                        return@setMethodCallHandler
                    }
                    @Suppress("UNCHECKED_CAST")
                    val headers = (call.argument<Map<String, Any?>>("headers") ?: emptyMap())
                        .mapNotNull { (k, v) -> v?.toString()?.let { k to it } }
                        .toMap()

                    Thread {
                        val proxy = PkgProxyService.proxy ?: StreamingHttpProxy(PkgProxyService.DEFAULT_PORT)
                        val probe = proxy.probeUrl(url, headers)
                        mainHandler.post {
                            result.success(
                                mapOf(
                                    "ok" to probe.ok,
                                    "statusCode" to probe.statusCode,
                                    "contentType" to probe.contentType,
                                    "contentLength" to probe.contentLength,
                                    "supportsRange" to probe.supportsRange,
                                    "isValidPkgHeader" to probe.isValidPkgHeader,
                                    "finalUrl" to probe.finalUrl,
                                    "message" to probe.message,
                                )
                            )
                        }
                    }.start()
                }

                else -> result.notImplemented()
            }
        }

        EventChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            EVENT_CHANNEL
        ).setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                eventSink = events
                PkgProxyService.onStatsUpdated = { stats ->
                    mainHandler.post {
                        eventSink?.success(statsToMap(stats))
                    }
                }
                eventSink?.success(buildStatusMap())
            }

            override fun onCancel(arguments: Any?) {
                eventSink = null
                PkgProxyService.onStatsUpdated = null
            }
        })

        EventChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            LOG_CHANNEL
        ).setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                PkgProxyService.onProxyLog = { msg ->
                    mainHandler.post {
                        events?.success(msg)
                    }
                }
            }

            override fun onCancel(arguments: Any?) {
                PkgProxyService.onProxyLog = null
            }
        })
    }

    private fun startProxyService(port: Int) {
        val intent = Intent(this, PkgProxyService::class.java).apply {
            action = PkgProxyService.ACTION_START
            putExtra(PkgProxyService.EXTRA_PORT, port)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, intent)
        } else {
            startService(intent)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    1001
                )
            }
        }
    }

    private fun buildStatusMap(): Map<String, Any> {
        val stats = PkgProxyService.proxy?.snapshotStats() ?: ProxyStats(
            running = false,
            port = PkgProxyService.DEFAULT_PORT,
            localIp = StreamingHttpProxy.getLocalIpAddress(),
            activeConnections = 0,
            totalBytesTransferred = 0,
            bytesPerSecond = 0,
        )
        return statsToMap(stats)
    }

    private fun statsToMap(stats: ProxyStats): Map<String, Any> {
        return mapOf(
            "running" to stats.running,
            "port" to stats.port,
            "localIp" to stats.localIp,
            "activeConnections" to stats.activeConnections,
            "totalBytesTransferred" to stats.totalBytesTransferred,
            "bytesPerSecond" to stats.bytesPerSecond,
        )
    }

    companion object {
        private const val METHOD_CHANNEL = "com.ps4pkg.ps4_pkg_streamer/proxy"
        private const val EVENT_CHANNEL = "com.ps4pkg.ps4_pkg_streamer/stats"
        private const val LOG_CHANNEL = "com.ps4pkg.ps4_pkg_streamer/logs"
    }
}
