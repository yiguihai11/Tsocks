package com.yiguihai.tsocks

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.InetAddresses
import android.net.IpPrefix
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.yiguihai.tsocks.utils.ByteUtils
import com.yiguihai.tsocks.utils.Preferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.io.IOException

data class TrafficStats(
    val uploadSpeed: String,
    val downloadSpeed: String,
    val uploadPackets: Int,
    val downloadPackets: Int,
    val ssUploadSpeed: String = "0 KB/s",
    val ssDownloadSpeed: String = "0 KB/s"
)

class TProxyService : VpnService() {
    companion object {
        private const val TAG = "TProxyService"
        const val ACTION_CONNECT = "com.yiguihai.tsocks.ACTION_CONNECT"
        const val ACTION_DISCONNECT = "com.yiguihai.tsocks.ACTION_DISCONNECT"
        const val CHANNEL_NAME = "socks5"
        private const val NOTIFICATION_ID = 1
        private const val ERROR_NOTIFICATION_ID = 2
        private const val CONFIG_FILENAME = "hev-socks5-tunnel.yaml"
        private const val STATS_UPDATE_INTERVAL_MS = 2000L
        private const val CONFIG_LOAD_DELAY_MS = 500L
        private const val IPV4_PREFIX_LENGTH = 32
        private const val IPV6_PREFIX_LENGTH = 128
        private const val DEFAULT_IPV4_DNS = "8.8.8.8"
        private const val DEFAULT_IPV6_DNS = "2001:4860:4860::8888"
        private const val IPV4_DEFAULT_ROUTE = "0.0.0.0"
        private const val IPV6_DEFAULT_ROUTE = "::"
        private const val VPN_SESSION_NAME = "TSocks VPN"

        @JvmStatic private external fun TProxyStartService(configPath: String, fd: Int)
        @JvmStatic private external fun TProxyStopService()
        @JvmStatic private external fun TProxyGetStats(): LongArray?

        private val _trafficStats = MutableSharedFlow<TrafficStats>(
            replay = 1,
            extraBufferCapacity = 64
        )
        val trafficStats = _trafficStats.asSharedFlow()

        init {
            System.loadLibrary("hev-socks5-tunnel")
        }
    }

    private var tunFd: android.os.ParcelFileDescriptor? = null
    private val statsHandler = Handler(Looper.getMainLooper())
    private val statsRunnable = Runnable { updateNotification() }
    private lateinit var preferences: Preferences
    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private var shadowsocksStatsJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when {
            tunFd != null -> when (intent?.action) {
                ACTION_CONNECT -> return START_STICKY
                ACTION_DISCONNECT -> {
                    stopService()
                    return START_STICKY
                }
            }
            intent?.action == ACTION_CONNECT -> startService()
            intent?.action == ACTION_DISCONNECT -> stopService()
            else -> if (Preferences.getInstance(applicationContext).isEnabled) {
                startService()
            } else {
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onRevoke() {
        stopService()
        super.onRevoke()
    }

    override fun onDestroy() {
        statsHandler.removeCallbacks(statsRunnable)
        stopService()
        super.onDestroy()
    }

    private fun startService() {
        try {
            if (tunFd != null) {
                stopSelf()
                return
            }

            initNotificationChannel()
            startForeground(NOTIFICATION_ID, createNotification())

            preferences = Preferences.getInstance(applicationContext)
            if (!preferences.isConfigLoaded()) {
                Thread.sleep(CONFIG_LOAD_DELAY_MS)
            }

            setupVpnInterface()
            if (!setupTProxyService()) {
                Log.e(TAG, "TProxy服务设置失败")
                stopService()
                return
            }

            // 启动Shadowsocks服务 - 在协程中调用挂起函数
            serviceScope.launch {
                try {
                    if (!ShadowsocksManager.startService(applicationContext)) {
                        Log.e(TAG, "Shadowsocks服务启动失败")
                        showErrorNotification("Shadowsocks服务启动失败，请检查配置")
                        Log.w(TAG, "仅启动HEV Socks5 Tunnel服务，Shadowsocks未启动")
                    } else {
                        // 启动Shadowsocks流量统计监视
                        startShadowsocksStatsMonitor()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "启动Shadowsocks服务失败", e)
                    showErrorNotification("Shadowsocks服务启动失败：${e.message}")
                }
            }

            statsHandler.post(statsRunnable)
            preferences.isEnabled = true
            updateNotification()
        } catch (e: Exception) {
            Log.e(TAG, "启动服务失败", e)
            stopService()
        }
    }

    private fun startShadowsocksStatsMonitor() {
        shadowsocksStatsJob?.cancel()
        shadowsocksStatsJob = serviceScope.launch {
            try {
                while (true) {
                    try {
                        val stats = ShadowsocksManager.getTrafficStats()
                        if ((stats?.uploadSpeed ?: 0) > 0 || (stats?.downloadSpeed ?: 0) > 0) {
                            Log.d(TAG, "Shadowsocks流量 - 上传: ${ByteUtils.formatBytesSpeed(stats?.uploadSpeed ?: 0)}, 下载: ${ByteUtils.formatBytesSpeed(stats?.downloadSpeed ?: 0)}")
                        }
                        delay(STATS_UPDATE_INTERVAL_MS)
                    } catch (e: CancellationException) {
                        // 协程被取消，退出循环
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "监视Shadowsocks流量统计失败", e)
            }
        }
    }

    private fun stopService() {
        statsHandler.removeCallbacks(statsRunnable)
        shadowsocksStatsJob?.cancel()
        shadowsocksStatsJob = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        releaseResources()
        
        try {
            ShadowsocksManager.stopService()
            Preferences(applicationContext).isEnabled = false
            getSystemService(NotificationManager::class.java)?.cancel(ERROR_NOTIFICATION_ID)
        } catch (e: Exception) {
            Log.e(TAG, "停止服务时出错", e)
        }
        
        stopSelf()
    }

    private fun releaseResources() {
        try {
            tunFd?.close()
            tunFd = null
            TProxyStopService()
        } catch (e: Exception) {
            Log.e(TAG, "释放资源失败", e)
        }
    }

    private fun setupVpnInterface() {
        val configFile = File(filesDir, CONFIG_FILENAME)
        if (!configFile.exists() || !configFile.canRead() || configFile.length() == 0L) {
            throw IOException("配置文件无效")
        }

        val (mtu, ipv4, ipv6) = parseTunnelConfig(configFile)
        val builder = Builder().apply {
            setBlocking(false)
            setMtu(mtu)

            if (preferences.isIPv4Enabled()) {
                addAddress(ipv4, IPV4_PREFIX_LENGTH)
                addRoute(IPV4_DEFAULT_ROUTE, 0)
                addDnsServer(preferences.getDnsV4String().takeIf { it.isNotEmpty() } ?: DEFAULT_IPV4_DNS)
            }

            if (preferences.isIPv6Enabled()) {
                addAddress(ipv6, IPV6_PREFIX_LENGTH)
                addRoute(IPV6_DEFAULT_ROUTE, 0)
                addDnsServer(preferences.getDnsV6String().takeIf { it.isNotEmpty() } ?: DEFAULT_IPV6_DNS)
            }

            setupExcludeRoutes(this, preferences.excludedIps)
            setupAppFilter(this)
            setSession(VPN_SESSION_NAME)
        }

        tunFd = builder.establish() ?: throw IOException("无法建立VPN接口")
    }

    private fun parseTunnelConfig(configFile: File): Triple<Int, String, String> {
        val yaml = Yaml()
        val config = yaml.load<Map<String, Any>>(configFile.readText())
        val tunnelConfig = (config["tunnel"] as? Map<*, *>)?.let { map ->
            map.mapKeys { it.key.toString() }
               .mapValues { it.value }
        } ?: throw IOException("配置文件格式错误")
        
        val mtu = when (val mtuValue = tunnelConfig["mtu"]) {
            is Int -> mtuValue
            is String -> mtuValue.toIntOrNull() ?: throw IOException("无效的MTU值")
            else -> throw IOException("无效的MTU值类型")
        }

        val ipv4 = (tunnelConfig["ipv4"] as? String)?.removeQuotes() 
            ?: throw IOException("无效的IPv4地址")
        val ipv6 = (tunnelConfig["ipv6"] as? String)?.removeQuotes() 
            ?: throw IOException("无效的IPv6地址")

        return Triple(mtu, ipv4, ipv6)
    }

    private fun String.removeQuotes() = replace("'", "").replace("\"", "")

    private fun setupExcludeRoutes(builder: Builder, excludeIps: List<String>) {
        excludeIps.filter { it.isNotBlank() }.forEach { ip ->
            try {
                val (address, prefix) = when {
                    "/" in ip -> {
                        val parts = ip.split("/", limit = 2)
                        val prefixValue = parts.getOrElse(1) { "" }.toIntOrNull() 
                            ?: if (ip.contains(":")) IPV6_PREFIX_LENGTH else IPV4_PREFIX_LENGTH
                        Pair(parts[0], prefixValue)
                    }
                    else -> Pair(ip, if (ip.contains(":")) IPV6_PREFIX_LENGTH else IPV4_PREFIX_LENGTH)
                }
                builder.excludeRoute(IpPrefix(InetAddresses.parseNumericAddress(address), prefix))
            } catch (e: Exception) {
                Log.e(TAG, "添加排除路由失败: $ip", e)
            }
        }
    }

    private fun setupAppFilter(builder: Builder) {
        when (preferences.getProxyModeIntValue()) {
            0 -> builder.addDisallowedApplication(packageName)
            1 -> {
                val apps = preferences.proxiedApps
                    .filter { it.isEnabled }
                    .map { it.packageName }
                apps.forEach { builder.addDisallowedApplication(it) }
                if (packageName !in apps) {
                    builder.addDisallowedApplication(packageName)
                }
            }
            2 -> {
                val apps = preferences.proxiedApps
                    .filter { it.isEnabled }
                    .map { it.packageName }
                if (apps.isEmpty()) {
                    builder.addDisallowedApplication(packageName)
                } else {
                    apps.forEach { builder.addAllowedApplication(it) }
                }
            }
        }
    }

    private fun setupTProxyService(): Boolean {
        val configFile = File(filesDir, CONFIG_FILENAME)
        if (!configFile.exists() || !configFile.canRead() || configFile.length() == 0L) {
            return false
        }
        return try {
            TProxyStartService(configFile.absolutePath, tunFd!!.fd)
            true
        } catch (e: Exception) {
            Log.e(TAG, "启动 TProxy 服务失败", e)
            false
        }
    }

    private fun updateNotification() {
        // 在后台线程执行，避免阻塞主线程
        serviceScope.launch(Dispatchers.IO) {
            val tproxyStats = TProxyGetStats()
            val ssStats = ShadowsocksManager.getTrafficStats()
            
            try {
                val uploadSpeed = tproxyStats?.let { ByteUtils.formatSpeed(it[1]) } ?: "0 KB/s"
                val downloadSpeed = tproxyStats?.let { ByteUtils.formatSpeed(it[3]) } ?: "0 KB/s"
                val uploadPackets = tproxyStats?.get(0)?.toInt() ?: 0
                val downloadPackets = tproxyStats?.get(2)?.toInt() ?: 0
                
                // 格式化Shadowsocks流量数据
                val ssUploadSpeed = ByteUtils.formatBytesSpeed(ssStats?.uploadSpeed ?: 0L)
                val ssDownloadSpeed = ByteUtils.formatBytesSpeed(ssStats?.downloadSpeed ?: 0L)
                
                // 广播完整的流量统计
                broadcastTrafficStats(
                    uploadSpeed, 
                    downloadSpeed, 
                    uploadPackets, 
                    downloadPackets,
                    ssUploadSpeed,
                    ssDownloadSpeed
                )

                // 在主线程更新通知
                withContext(Dispatchers.Main) {
                    getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, createNotification())
                }
            } catch (e: Exception) {
                Log.e(TAG, "更新通知失败", e)
            }
            
            // 确保统计信息持续更新
            statsHandler.postDelayed(statsRunnable, STATS_UPDATE_INTERVAL_MS)
        }
    }

    private fun broadcastTrafficStats(
        uploadSpeed: String,
        downloadSpeed: String,
        uploadPackets: Int,
        downloadPackets: Int,
        ssUploadSpeed: String = "0 KB/s",
        ssDownloadSpeed: String = "0 KB/s"
    ) {
        try {
            _trafficStats.tryEmit(TrafficStats(
                uploadSpeed = uploadSpeed,
                downloadSpeed = downloadSpeed,
                uploadPackets = uploadPackets,
                downloadPackets = downloadPackets,
                ssUploadSpeed = ssUploadSpeed,
                ssDownloadSpeed = ssDownloadSpeed
            ))
        } catch (e: Exception) {
            Log.e(TAG, "发送流量统计失败", e)
        }
    }

    private fun initNotificationChannel() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager?.getNotificationChannel(CHANNEL_NAME)?.let { return }

        val channel = NotificationChannel(
            CHANNEL_NAME,
            getString(R.string.app_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            setSound(null, null)
            enableVibration(false)
            description = "VPN服务状态和流量统计"
            setShowBadge(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            importance = NotificationManager.IMPORTANCE_HIGH
        }

        notificationManager?.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val baseBuilder = Notification.Builder(this, CHANNEL_NAME)
            .setSmallIcon(R.drawable.baseline_vpn_lock_24)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)

        return try {
            val contentIntent = PendingIntent.getActivity(
                this, 
                0,
                Intent(this, TrafficStatsActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or 
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                PendingIntent.FLAG_IMMUTABLE
            )

            val stopIntent = PendingIntent.getService(
                this, 
                0,
                Intent(this, TProxyService::class.java).setAction(ACTION_DISCONNECT),
                PendingIntent.FLAG_IMMUTABLE
            )
            
            baseBuilder
                .setContentTitle(getString(R.string.app_name))
                .setContentText("点击查看流量统计")
                .setContentIntent(contentIntent)
                .setOnlyAlertOnce(true)
                .addAction(
                    Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.baseline_stop_24),
                        "停止",
                        stopIntent
                    ).build()
                )
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "创建通知失败", e)
            baseBuilder
                .setContentTitle("VPN服务")
                .setContentText("VPN正在运行")
                .build()
        }
    }

    private fun showErrorNotification(errorMessage: String) {
        try {
            val notificationManager = getSystemService(NotificationManager::class.java)
            val builder = Notification.Builder(this, CHANNEL_NAME)
                .setSmallIcon(R.drawable.baseline_vpn_lock_24)
                .setContentTitle("VPN服务警告")
                .setContentText(errorMessage)
                .setCategory(Notification.CATEGORY_ERROR)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(false)
                .setAutoCancel(true)
            
            notificationManager?.notify(ERROR_NOTIFICATION_ID, builder.build())
        } catch (e: Exception) {
            Log.e(TAG, "显示错误通知失败", e)
        }
    }
}