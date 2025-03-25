/*
 ============================================================================
 Name        : TProxyService.kt
 Author      : hev <r@hev.cc>
 Copyright   : Copyright (c) 2024 xyz
 Description : TProxy Service
 ============================================================================
 */

package com.yiguihai.tsocks

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.InetAddresses
import android.net.IpPrefix
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.IOException
import java.util.Locale

class TProxyService : VpnService() {
    companion object {
        private const val TAG = "TProxyService"
        const val ACTION_CONNECT = "hev.sockstun.START"
        const val ACTION_DISCONNECT = "hev.sockstun.STOP"
        const val CHANNEL_NAME = "socks5"
        private const val NOTIFICATION_ID = 1

        @JvmStatic private external fun TProxyStartService(configPath: String, fd: Int)
        @JvmStatic private external fun TProxyStopService()
        @JvmStatic private external fun TProxyGetStats(): LongArray?

        init {
            System.loadLibrary("hev-socks5-tunnel")
        }

        fun getStats(): LongArray? = TProxyGetStats()
    }

    private var tunFd: android.os.ParcelFileDescriptor? = null
    private val statsHandler = Handler(Looper.getMainLooper())
    private val statsRunnable = object : Runnable {
        override fun run() {
            updateNotification()
            statsHandler.postDelayed(this, 1000)
        }
    }
    
    // 获取应用程序设置实例
    private lateinit var preferences: Preferences

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> startService()
            ACTION_DISCONNECT -> stopService()
        }
        return START_STICKY
    }

    override fun onRevoke() {
        Log.i(TAG, "VPN 被系统撤销")
        broadcastVpnState(ServiceReceiver.VPN_STATE_DISCONNECTED)
        stopService()
        super.onRevoke()
    }

    override fun onDestroy() {
        Log.i(TAG, "VPN 服务被销毁")
        stopService()
        super.onDestroy()
    }

    private fun startService() {
        try {
            broadcastVpnState(ServiceReceiver.VPN_STATE_CONNECTING)
            initNotificationChannel()
            startForeground(NOTIFICATION_ID, createNotification())
            
            // 初始化设置
            preferences = Preferences.getInstance(applicationContext)
            
            // 确保配置已加载
            if (!preferences.isConfigLoaded()) {
                Log.i(TAG, "等待代理配置加载...")
                // 等待短暂时间以确保配置加载完成
                Thread.sleep(500)
                if (!preferences.isConfigLoaded()) {
                    Log.w(TAG, "代理配置可能未完全加载，继续启动服务")
                }
            }
            
            // 记录代理应用信息
            val enabledApps = preferences.proxiedApps.filter { it.isEnabled }
            Log.i(TAG, "已启用的代理应用数量: ${enabledApps.size}")
            
            setupVpnInterface()
            setupTProxyService() ?: throw IOException("无法设置 TProxy 服务")
            statsHandler.post(statsRunnable)
            preferences.apply {
                isEnabled = true
                recordConnection()
            }
            Log.i(TAG, "VPN 服务已启动")
            broadcastVpnState(ServiceReceiver.VPN_STATE_CONNECTED)
        } catch (e: Exception) {
            Log.e(TAG, "启动服务时出错", e)
            broadcastVpnState(ServiceReceiver.VPN_STATE_FAILED)
            stopService()
        }
    }

    private fun stopService() {
        statsHandler.removeCallbacks(statsRunnable)
        stopForeground(STOP_FOREGROUND_REMOVE)
        releaseResources()
        Log.i(TAG, "VPN 服务已停止")
        broadcastVpnState(ServiceReceiver.VPN_STATE_DISCONNECTED)
    }

    private fun releaseResources() {
        try {
            tunFd?.close()
            tunFd = null
            TProxyStopService()
        } catch (e: Exception) {
            Log.e(TAG, "释放资源时出错", e)
        }
    }

    private fun broadcastVpnState(state: Int) {
        Intent(ServiceReceiver.ACTION_VPN_STATE_CHANGED).apply {
            putExtra(ServiceReceiver.EXTRA_VPN_STATE, state)
            sendBroadcast(this)
        }
    }

    private fun setupVpnInterface() {
        val configFile = File(filesDir, "hev-socks5-tunnel.yaml")
        val (mtu, ipv4, ipv6) = parseTunnelConfig(configFile)
        Log.d(TAG, "使用隧道配置 - MTU: $mtu, IPv4: $ipv4, IPv6: $ipv6")

        tunFd = Builder().apply {
            setBlocking(false)
            setMtu(mtu)
            if (preferences.isIPv4Enabled()) {
                addAddress(ipv4, 32)
                addRoute("0.0.0.0", 0)
                preferences.getDnsV4String().takeIf { it.isNotEmpty() }?.let { addDnsServer(it) }
            }
            if (preferences.isIPv6Enabled()) {
                addAddress(ipv6, 128)
                addRoute("::", 0)
                preferences.getDnsV6String().takeIf { it.isNotEmpty() }?.let { addDnsServer(it) }
            }
            setupExcludeRoutes(this, preferences.excludedIps)
            setupAppFilter(this)
        }.establish() ?: throw IOException("无法建立 VPN 接口")
    }

    private fun parseTunnelConfig(configFile: File): Triple<Int, String, String> {
        if (!configFile.exists() || !configFile.canRead()) throw IOException("配置文件不可读: ${configFile.absolutePath}")
        val lines = configFile.readLines()
        return Triple(
            lines.find { it.trim().startsWith("  mtu:") }?.substringAfter("mtu:")?.trim()?.toIntOrNull() ?: throw IOException("未找到 MTU 设置"),
            lines.find { it.trim().startsWith("  ipv4:") }?.substringAfter("ipv4:")?.trim()?.removeQuotes() ?: throw IOException("未找到 IPv4 设置"),
            lines.find { it.trim().startsWith("  ipv6:") }?.substringAfter("ipv6:")?.trim()?.removeQuotes() ?: throw IOException("未找到 IPv6 设置")
        )
    }

    private fun String.removeQuotes() = replace("'", "").replace("\"", "")

    private fun setupExcludeRoutes(builder: Builder, excludeIps: List<String>) {
        excludeIps.filter { it.isNotBlank() }.forEach { ip ->
            try {
                val (address, prefix) = if ("/" in ip) ip.split("/").let { it[0] to it[1].toInt() }
                else ip to if (ip.contains(":")) 128 else 32
                builder.excludeRoute(IpPrefix(InetAddresses.parseNumericAddress(address), prefix))
            } catch (e: Exception) {
                Log.e(TAG, "添加排除路由失败: $ip", e)
            }
        }
    }

    private fun setupAppFilter(builder: Builder) {
        when (preferences.getProxyModeIntValue()) {
            0 -> {
                // 全局代理模式
                builder.addDisallowedApplication(packageName)
                Log.d(TAG, "全局代理模式：仅排除本应用 $packageName")
            }
            1 -> { // 绕行模式
                val apps = preferences.proxiedApps.filter { it.isEnabled }.map { it.packageName }
                Log.d(TAG, "绕行模式: 找到 ${apps.size} 个应用需要排除: ${apps.joinToString()}")
                apps.forEach { builder.addDisallowedApplication(it) }
                if (packageName !in apps) {
                    builder.addDisallowedApplication(packageName)
                    Log.d(TAG, "添加本应用 $packageName 到排除列表")
                }
            }
            2 -> { // 仅代理模式
                val apps = preferences.proxiedApps.filter { it.isEnabled }.map { it.packageName }
                Log.d(TAG, "仅代理模式: 找到 ${apps.size} 个应用需要代理: ${apps.joinToString()}")
                if (apps.isEmpty()) {
                    builder.addDisallowedApplication(packageName)
                    Log.d(TAG, "未找到需要代理的应用，仅排除本应用 $packageName")
                } else {
                    apps.forEach { 
                        try {
                            builder.addAllowedApplication(it)
                        } catch (e: Exception) {
                            Log.e(TAG, "添加允许应用 $it 失败: ${e.message}")
                        }
                    }
                }
            }
        }
        
        // 记录配置加载状态
        Log.d(TAG, "代理配置已加载: isConfigLoaded=${preferences.isConfigLoaded()}, 代理应用数量: ${preferences.proxiedApps.size}")
    }

    private fun setupTProxyService(): Boolean? {
        val configFile = File(filesDir, "hev-socks5-tunnel.yaml")
        if (!configFile.exists() || !configFile.canRead() || configFile.length() == 0L) {
            Log.e(TAG, "配置文件无效: ${configFile.absolutePath}")
            return null
        }
        return try {
            TProxyStartService(configFile.absolutePath, tunFd!!.fd)
            true
        } catch (e: Exception) {
            Log.e(TAG, "启动 TProxy 服务失败", e)
            null
        }
    }

    private fun updateNotification() {
        getStats()?.let { stats ->
            val text = String.format(
                Locale.US, "↑ %s  ↓ %s\n↑ %d pkt/s  ↓ %d pkt/s",
                formatSpeed(stats[1]), formatSpeed(stats[3]), stats[0], stats[2]
            )
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID, createNotification(text))
        }
    }

    private fun formatSpeed(bytes: Long) = when {
        bytes >= 1024 * 1024 -> String.format(Locale.US, "%.2f MB/s", bytes / (1024.0 * 1024))
        else -> String.format(Locale.US, "%.2f KB/s", bytes / 1024.0)
    }

    private fun createNotification(stats: String = ""): Notification =
        NotificationCompat.Builder(this, CHANNEL_NAME)
            .setContentTitle(getString(R.string.app_name))
            .setSmallIcon(R.drawable.baseline_vpn_lock_24)
            .setContentText(stats)
            .setStyle(NotificationCompat.BigTextStyle().bigText(stats))
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .addAction(R.drawable.baseline_stop_24, "停止", PendingIntent.getService(this, 0, Intent(this, TProxyService::class.java).setAction(ACTION_DISCONNECT), PendingIntent.FLAG_IMMUTABLE))
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()

    private fun initNotificationChannel() {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
            NotificationChannel(CHANNEL_NAME, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                enableVibration(false)
            }
        )
    }
}