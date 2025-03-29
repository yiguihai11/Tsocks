package com.yiguihai.tsocks

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.yiguihai.tsocks.utils.ByteUtils
import com.yiguihai.tsocks.utils.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/**
 * Shadowsocks服务管理器
 * 负责启动和停止Shadowsocks服务，处理ACL配置等
 */
class ShadowsocksManager {
    companion object {
        private const val TAG = "ShadowsocksManager"
        private const val ACL_FILE_NAME = "bypass-china.acl"
        private const val ERROR_CHANNEL_ID = "shadowsocks_error"
        private const val ERROR_NOTIFICATION_ID = 1001
        private const val STAT_TCP_PORT = 8010
        private val RESTART_DELAY = 1.seconds
        private val ERROR_RETRY_DELAY = 1.seconds
        private val RATE_UPDATE_INTERVAL = 1.seconds

        private var isRunning = AtomicBoolean(false)
        private var trafficMonitor: TrafficMonitor? = null
        private var process: Process? = null
        private var processId = -1
        private var logJob: Job? = null
        private var restartCount = AtomicInteger(0)
        private const val MAX_RESTART_ATTEMPTS = 3
        private val serviceScope = CoroutineScope(Dispatchers.IO)
        
        /**
         * 启动Shadowsocks服务
         * @param context 应用上下文
         * @return 是否成功启动
         */
        suspend fun startService(context: Context): Boolean {
            if (isRunning.get()) {
                Log.i(TAG, "Shadowsocks服务已经在运行")
                return true
            }
            
            val preferences = Preferences.getInstance(context)
            val ssConfig = preferences.getShadowsocksConfig()
            
            // 检查是否有可用的服务器配置
            if (ssConfig.servers.isEmpty() || ssConfig.servers.all { it.disabled }) {
                Log.e(TAG, "没有可用的服务器配置，无法启动服务")
                return false
            }
            
            val tmpConfigDir = context.getDir("configs", Context.MODE_PRIVATE)
            val tmpConfigFile = File(tmpConfigDir, "current.json")
            
            // 准备TCP地址
            val statAddr = "127.0.0.1:$STAT_TCP_PORT"
            
            try {
                // 处理v2ray插件路径
                val configJson = GsonBuilder().setPrettyPrinting().create().toJson(ssConfig)
                val jsonObject = JsonParser.parseString(configJson).asJsonObject
                
                // 检查并处理v2ray插件
                if (jsonObject.has("plugin") && jsonObject.get("plugin").asString == "v2ray-plugin") {
                    val nativeLibDir = context.applicationInfo.nativeLibraryDir
                    val v2rayPluginPath = "$nativeLibDir/libv2ray-plugin.so"
                    jsonObject.addProperty("plugin", v2rayPluginPath)
                    Log.i(TAG, "已更新v2ray插件路径: $v2rayPluginPath")
                }
                
                // 在IO调度器中执行文件写入操作，避免阻塞
                withContext(Dispatchers.IO) {
                    // 将配置写入临时文件
                    FileOutputStream(tmpConfigFile).use { it.write(jsonObject.toString().toByteArray()) }
                }
                
                // 重置重启计数
                restartCount.set(0)
                
                // 初始化流量监控，使用TCP
                trafficMonitor = TrafficMonitor(statAddr).apply { start() }
                
                // 先启动监控服务器，确保在Shadowsocks启动前已准备好接收连接
                delay(500)
                
                // 构建启动命令
                val nativeLibDir = context.applicationInfo.nativeLibraryDir
                val ssLocalPath = "$nativeLibDir/libsslocal.so"
                val commandArgs = mutableListOf(
                    ssLocalPath,
                    "-c", tmpConfigFile.absolutePath,
                    "--vpn",
                    "--stat-addr", statAddr
                )
                
                // 检查是否需要加载ACL文件来排除中国IP
                if (preferences.getExcludeChinaIp()) {
                    extractAclFile(context)?.let {
                        commandArgs.addAll(listOf("--acl", it.absolutePath))
                        Log.i(TAG, "使用ACL文件排除中国IP段: ${it.absolutePath}")
                    }
                }
                
                // 使用IO调度器启动进程，避免阻塞
                process = withContext(Dispatchers.IO) {
                    // 使用ProcessBuilder启动进程
                    ProcessBuilder(commandArgs)
                        .redirectErrorStream(true) // 合并错误流和输出流
                        .start()
                }
                
                // 获取进程PID
                processId = getProcessId(process)
                Log.i(TAG, "Shadowsocks进程启动，PID: $processId")
                
                // 读取进程输出
                startOutputMonitoring()
                
                isRunning.set(true)
                
                // 启动进程监控
                startProcessMonitor(context)
                
                return true
            } catch (e: Exception) {
                Log.e(TAG, "启动Shadowsocks服务出错", e)
                cleanupResources()
                return false
            }
        }

        /**
         * 监控进程输出
         */
        private fun startOutputMonitoring() {
            process?.let { proc ->
                logJob = serviceScope.launch {
                    runCatching {
                        BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                            reader.lineSequence()
                                .takeWhile { isActive }
                                .forEach { Log.d(TAG, "Shadowsocks输出: $it") }
                        }
                    }.onFailure { 
                        if (isRunning.get()) Log.e(TAG, "读取输出失败", it)
                    }
                }
            }
        }
        
        /**
         * 监控进程状态，在进程意外退出时自动重启
         */
        private fun startProcessMonitor(context: Context) {
            serviceScope.launch {
                process?.let { proc ->
                    try {
                        // 等待进程结束
                        val exitCode = proc.waitFor()
                        
                        // 检查进程是否是被我们主动停止的
                        if (isRunning.get()) {
                            Log.w(TAG, "Shadowsocks进程意外退出，退出码: $exitCode")
                            
                            // 尝试重启，但限制重启次数
                            if (restartCount.incrementAndGet() <= MAX_RESTART_ATTEMPTS) {
                                Log.i(TAG, "尝试重启Shadowsocks服务 (${restartCount.get()}/$MAX_RESTART_ATTEMPTS)")
                                delay(RESTART_DELAY)
                                cleanupResources()
                                // 调用挂起函数需要在协程上下文中
                                startService(context)
                            } else {
                                Log.e(TAG, "Shadowsocks服务重启失败次数过多，不再尝试重启")
                                stopService()
                                
                                // 关闭整个VPN服务
                                stopVpnService(context)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "进程监控异常", e)
                    }
                }
            }
        }

        /**
         * 停止整个VPN服务
         */
        private fun stopVpnService(context: Context) {
            try {
                Log.i(TAG, "Shadowsocks多次重启失败，正在停止VPN服务...")
                
                // 向TProxyService发送停止指令
                val intent = Intent(context, TProxyService::class.java)
                intent.action = TProxyService.ACTION_DISCONNECT
                context.startService(intent)
                
                // 显示通知告知用户
                showFailureNotification(context)
            } catch (e: Exception) {
                Log.e(TAG, "停止VPN服务失败", e)
            }
        }
        
        /**
         * 显示重启失败通知
         */
        private fun showFailureNotification(context: Context) {
            runCatching {
                val notificationManager = context.getSystemService(NotificationManager::class.java)
                
                // 创建通知渠道（如果尚未创建）
                val channel = NotificationChannel(
                    ERROR_CHANNEL_ID,
                    "Shadowsocks错误",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply { description = "Shadowsocks服务错误通知" }
                
                notificationManager.createNotificationChannel(channel)
                
                // 创建打开应用的Intent
                val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                val pendingIntent = PendingIntent.getActivity(
                    context, 0, intent, PendingIntent.FLAG_IMMUTABLE
                )
                
                // 构建通知
                val notification = Notification.Builder(context, ERROR_CHANNEL_ID)
                    .setContentTitle("Shadowsocks服务已停止")
                    .setContentText("由于多次启动失败，VPN服务已被关闭")
                    .setSmallIcon(R.drawable.baseline_vpn_lock_24)
                    .setAutoCancel(true)
                    .setContentIntent(pendingIntent)
                    .build()
                
                // 显示通知
                notificationManager.notify(ERROR_NOTIFICATION_ID, notification)
            }.onFailure { Log.e(TAG, "显示失败通知失败", it) }
        }

        /**
         * 获取进程ID
         */
        private fun getProcessId(process: Process?): Int = runCatching {
            process?.javaClass?.getDeclaredField("pid")?.apply {
                isAccessible = true
            }?.getInt(process) ?: -1
        }.getOrElse { 
            Log.e(TAG, "获取进程ID失败", it)
            -1
        }

        /**
         * 停止服务
         */
        fun stopService() {
            if (!isRunning.getAndSet(false)) return
            
            runCatching {
                // 停止监控
                logJob?.cancel()
                logJob = null
                trafficMonitor?.stop()
                trafficMonitor = null
                
                // 终止进程
                if (processId > 0) android.os.Process.killProcess(processId)
                
                process?.let {
                    it.destroy()
                    runCatching { it.waitFor() }
                    if (it.isAlive) it.destroyForcibly()
                }
                
                process = null
                processId = -1
            }.onFailure { Log.e(TAG, "停止服务失败", it) }
        }
        
        /**
         * 清理资源
         */
        private fun cleanupResources() {
            logJob?.cancel()
            logJob = null
            
            trafficMonitor?.stop()
            trafficMonitor = null
            
            process?.let {
                try {
                    it.destroy()
                } catch (e: Exception) {
                    Log.e(TAG, "清理进程资源失败", e)
                }
            }
            
            process = null
            processId = -1
        }

        /**
         * 检查Shadowsocks服务是否正在运行
         * @return 是否正在运行
         */
        fun isServiceRunning(): Boolean {
            return isRunning.get()
        }
        
        /**
         * 从assets提取ACL文件到应用私有目录
         */
        private suspend fun extractAclFile(context: Context): File? {
            val aclDir = context.getDir("acl", Context.MODE_PRIVATE)
            val aclFile = File(aclDir, ACL_FILE_NAME)
            
            // 使用IO调度器检查文件是否存在
            val fileExists = withContext(Dispatchers.IO) {
                aclFile.exists() && aclFile.length() > 0
            }
            
            // 如果文件已存在且有内容，直接返回
            if (fileExists) {
                return aclFile
            }
            
            return try {
                withContext(Dispatchers.IO) {
                    context.assets.open(ACL_FILE_NAME).use { input ->
                        FileOutputStream(aclFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    aclFile
                }
            } catch (e: Exception) {
                Log.e(TAG, "提取ACL文件失败: ${e.message}", e)
                null
            }
        }

        /**
         * 获取当前流量统计
         * @return 流量统计信息，如果未运行则返回null
         */
        fun getTrafficStats(): ShadowsocksTrafficStats? {
            return trafficMonitor?.getCurrentStats()
        }
    }

    /**
     * 流量统计数据类
     */
    data class ShadowsocksTrafficStats(
        val uploadSpeed: Long,    // 上传速率（字节/秒）
        val downloadSpeed: Long,  // 下载速率（字节/秒）
        val uploadTotal: Long,    // 总上传流量（字节）
        val downloadTotal: Long   // 总下载流量（字节）
    )

    /**
     * 流量监控类
     */
    private class TrafficMonitor(
        private val statAddr: String
    ) {
        private var serverSocket: ServerSocket? = null
        private val buffer = ByteArray(16)
        private val stat = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
        private var txTotal = 0L
        private var rxTotal = 0L
        private var txRate = 0L
        private var rxRate = 0L
        private var running = AtomicBoolean(false)
        private var lastTx = 0L
        private var lastRx = 0L
        private var lastStatsTime = SystemClock.elapsedRealtime()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        init {
            Log.d(TAG, "流量监控使用TCP模式，地址: $statAddr")
        }
        
        fun start() {
            if (running.getAndSet(true)) return
            startTcpServer()
        }
        
        private fun startTcpServer() {
            scope.launch {
                try {
                    val parts = statAddr.split(":")
                    if (parts.size != 2) {
                        Log.e(TAG, "无效的统计地址格式: $statAddr")
                        return@launch
                    }
                    
                    val port = parts[1].toIntOrNull() ?: 0
                    
                    if (port <= 0) {
                        Log.e(TAG, "无效的端口号: $port")
                        return@launch
                    }
                    
                    withContext(Dispatchers.IO) {
                        // 关闭可能存在的旧服务器
                        serverSocket?.close()
                        
                        // 创建新服务器
                        serverSocket = ServerSocket(port)
                    }
                    
                    Log.d(TAG, "TCP流量统计服务器已启动，端口: $port")
                    
                    try {
                        while (scope.isActive && running.get() && serverSocket?.isClosed == false) {
                            try {
                                // 在IO线程上下文中接受连接
                                val newSocket = withContext(Dispatchers.IO) {
                                    // 设置超时，防止阻塞
                                    serverSocket?.soTimeout = 2000 // 增加超时时间，减少CPU使用
                                    serverSocket?.accept()
                                } ?: continue
                                
                                Log.d(TAG, "接收到TCP流量统计连接")
                                
                                try {
                                    withContext(Dispatchers.IO) {
                                        newSocket.soTimeout = 1000
                                        val inputStream = newSocket.getInputStream()
                                        
                                        when (val bytesRead = inputStream.read(buffer)) {
                                            16 -> scope.launch { processStats() }
                                            -1 -> { /* 连接关闭 */ }
                                            else -> Log.w(TAG, "读取异常: $bytesRead/16 字节")
                                        }
                                    }
                                } catch (e: java.net.SocketTimeoutException) {
                                    // 超时忽略
                                } catch (e: Exception) {
                                    Log.w(TAG, "读取TCP流量数据失败: ${e.message}")
                                } finally {
                                    withContext(Dispatchers.IO) {
                                        try {
                                            newSocket.close()
                                        } catch (e: Exception) {
                                            // 忽略关闭错误
                                        }
                                    }
                                }
                                
                                // 读取完成后等待一段时间，减少CPU使用
                                delay(1.seconds)
                            } catch (e: java.net.SocketTimeoutException) {
                                // accept超时忽略
                            } catch (e: Exception) {
                                if (running.get()) {
                                    Log.e(TAG, "TCP服务器异常: ${e.message}")
                                    delay(ERROR_RETRY_DELAY)
                                }
                            }
                        }
                    } finally {
                        withContext(Dispatchers.IO) {
                            try {
                                serverSocket?.close()
                                serverSocket = null
                            } catch (e: Exception) {
                                // 忽略关闭错误
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "启动TCP流量统计服务器失败: ${e.message}")
                }
            }
        }
        
        private fun processStats() {
            runCatching {
                stat.position(0)
                val tx = stat.getLong(0)
                val rx = stat.getLong(8)
                val now = SystemClock.elapsedRealtime()
                val timeDelta = now - lastStatsTime
                
                if (timeDelta >= RATE_UPDATE_INTERVAL.inWholeMilliseconds * 2) {
                    // 计算速率
                    val txDelta = if (tx >= lastTx) tx - lastTx else tx
                    val rxDelta = if (rx >= lastRx) rx - lastRx else rx
                    
                    txRate = ((txDelta * 1000) / timeDelta).coerceAtLeast(0)
                    rxRate = ((rxDelta * 1000) / timeDelta).coerceAtLeast(0)
                    
                    lastTx = tx
                    lastRx = rx
                    lastStatsTime = now
                    
                    // 只在有明显流量变化时输出日志
                    if (txRate > 1024 || rxRate > 1024) {
                        Log.d(TAG, "流量统计 - 上传: ${ByteUtils.formatBytesSpeed(txRate)}, 下载: ${ByteUtils.formatBytesSpeed(rxRate)}")
                    }
                }
                
                // 更新总流量
                txTotal = tx
                rxTotal = rx
            }.onFailure { Log.e(TAG, "处理统计失败", it) }
        }

        fun getCurrentStats() = ShadowsocksTrafficStats(
                uploadSpeed = txRate,
                downloadSpeed = rxRate,
                uploadTotal = txTotal,
                downloadTotal = rxTotal
            )

        fun stop() {
            if (running.getAndSet(false)) {
                serverSocket?.close()
                serverSocket = null
                scope.coroutineContext.cancelChildren()
            }
        }
    }
}

