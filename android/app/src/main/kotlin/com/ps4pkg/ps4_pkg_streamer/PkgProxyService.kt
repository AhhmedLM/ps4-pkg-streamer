package com.ps4pkg.ps4_pkg_streamer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import java.util.Locale

class PkgProxyService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var statsTickerRunning = false

    private val statsTicker = object : Runnable {
        override fun run() {
            val stats = proxy?.snapshotStats() ?: ProxyStats(
                running = false,
                port = DEFAULT_PORT,
                localIp = StreamingHttpProxy.getLocalIpAddress(),
                activeConnections = 0,
                totalBytesTransferred = 0,
                bytesPerSecond = 0,
            )
            updateNotification(stats)
            onStatsUpdated?.invoke(stats)
            if (statsTickerRunning) {
                mainHandler.postDelayed(this, 1000L)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopProxyAndSelf()
            return START_NOT_STICKY
        }

        val port = intent?.getIntExtra(EXTRA_PORT, DEFAULT_PORT) ?: DEFAULT_PORT
        startForegroundWithNotification(
            ProxyStats(
                running = true,
                port = port,
                localIp = StreamingHttpProxy.getLocalIpAddress(),
                activeConnections = 0,
                totalBytesTransferred = 0,
                bytesPerSecond = 0,
            )
        )

        acquireLocks()

        if (proxy == null || proxy?.isRunning() != true) {
            val newProxy = StreamingHttpProxy(port)
            if (newProxy.start()) {
                proxy = newProxy
            }
        }

        if (!statsTickerRunning) {
            statsTickerRunning = true
            mainHandler.post(statsTicker)
        }

        return START_STICKY
    }

    override fun onDestroy() {
        statsTickerRunning = false
        mainHandler.removeCallbacks(statsTicker)
        proxy?.stop()
        proxy = null
        releaseLocks()
        onStatsUpdated?.invoke(
            ProxyStats(
                running = false,
                port = DEFAULT_PORT,
                localIp = StreamingHttpProxy.getLocalIpAddress(),
                activeConnections = 0,
                totalBytesTransferred = 0,
                bytesPerSecond = 0,
            )
        )
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun stopProxyAndSelf() {
        statsTickerRunning = false
        mainHandler.removeCallbacks(statsTicker)
        proxy?.stop()
        proxy = null
        releaseLocks()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun acquireLocks() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "PS4PkgStreamer::ProxyWakeLock"
                ).apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    @Suppress("DEPRECATION")
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wm.createWifiLock(mode, "PS4PkgStreamer::ProxyWifiLock").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null

        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Exception) {
        }
        wifiLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "PS4 PKG Background Streamer",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "يبقي خادم البث المباشر للـ PS4 يعمل في الخلفية"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun startForegroundWithNotification(stats: ProxyStats) {
        val notification = buildNotification(stats)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(stats: ProxyStats) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(stats))
    }

    private fun buildNotification(stats: ProxyStats): Notification {
        val openIntent = packageManager.getLaunchIntentForPackage(packageName)
        val openPending = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, PkgProxyService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val speedMb = stats.bytesPerSecond / (1024.0 * 1024.0)
        val totalMb = stats.totalBytesTransferred / (1024.0 * 1024.0)
        val contentText = if (stats.activeConnections > 0) {
            String.format(
                Locale.US,
                "بث نشط للـ PS4 • %.2f MB/s • تم نقل %.1f MB",
                speedMb,
                totalMb
            )
        } else {
            "جاهز للبث على ${stats.localIp}:${stats.port}"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PS4 PKG Streamer يعمل في الخلفية")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPending)
            .addAction(android.R.drawable.ic_media_pause, "إيقاف الخادم", stopPending)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "ps4_pkg_proxy_channel"
        const val NOTIFICATION_ID = 4040
        const val DEFAULT_PORT = 8888
        const val ACTION_START = "com.ps4pkg.ps4_pkg_streamer.ACTION_START"
        const val ACTION_STOP = "com.ps4pkg.ps4_pkg_streamer.ACTION_STOP"
        const val EXTRA_PORT = "port"

        @Volatile
        var proxy: StreamingHttpProxy? = null

        @Volatile
        var onStatsUpdated: ((ProxyStats) -> Unit)? = null
    }
}
