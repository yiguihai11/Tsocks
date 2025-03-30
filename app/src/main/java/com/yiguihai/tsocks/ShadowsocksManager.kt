package com.yiguihai.tsocks

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.yiguihai.tsocks.utils.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/**
 * Shadowsocks服务管理器
 */
class ShadowsocksManager {
    companion object {
        private const val TAG = "ShadowsocksManager"
        private const val ACL_FILE_NAME = "bypass-china.acl"
        private const val ERROR_CHANNEL_ID = "shadowsocks_error"
        private const val ERROR_NOTIFICATION_ID = 1001
        private val RESTART_DELAY = 1.seconds

        private var isRunning = AtomicBoolean(false)
        private var process: Process? = null
        private var processId = -1
        private var logJob: Job? = null
        private var restartCount = AtomicInteger(0)
        private const val MAX_RESTART_ATTEMPTS = 3
        private val serviceScope = CoroutineScope(Dispatchers.IO)
        
        /**
         * 启动Shadowsocks服务
         */
        suspend fun startService(context: Context): Boolean {
            if (isRunning.get()) {
                Log.i(TAG, context.getString(R.string.service_already_running))
                return true
            }
            
            val preferences = Preferences.getInstance(context)
            val ssConfig = preferences.getShadowsocksConfig()
            
            // 检查是否有可用服务器配置
            if (ssConfig.servers.isEmpty() || ssConfig.servers.all { it.disabled }) {
                Log.e(TAG, context.getString(R.string.no_available_servers))
                return false
            }
            
            val tmpConfigDir = context.getDir("configs", Context.MODE_PRIVATE)
            val tmpConfigFile = File(tmpConfigDir, "current.json")
            
            return try {
                // 处理配置
                val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
                val jsonObject = JsonParser.parseString(gson.toJson(ssConfig)).asJsonObject
                
                // 处理v2ray插件路径
                val nativeLibDir = context.applicationInfo.nativeLibraryDir
                val v2rayPluginPath = "$nativeLibDir/libv2ray-plugin.so"
                
                // 配置全局plugin
                if (jsonObject.has("plugin") && jsonObject.get("plugin").asString == "v2ray-plugin") {
                    jsonObject.addProperty("plugin", v2rayPluginPath)
                    Log.i(TAG, "配置文件路径: $v2rayPluginPath")
                }
                
                // 配置servers中的plugin
                jsonObject.getAsJsonArray("servers")?.let { serversArray ->
                    for (i in 0 until serversArray.size()) {
                        val serverObj = serversArray.get(i).asJsonObject
                        if (serverObj.has("plugin") && serverObj.get("plugin").asString == "v2ray-plugin") {
                            serverObj.addProperty("plugin", v2rayPluginPath)
                        }
                    }
                }
                
                // 写入配置文件
                withContext(Dispatchers.IO) {
                    tmpConfigFile.writeText(jsonObject.toString())
                    Log.i(TAG, "配置文件路径: ${tmpConfigFile.absolutePath}")
                    Log.i(TAG, "配置文件内容:\n${gson.toJson(JsonParser.parseString(tmpConfigFile.readText()))}")
                }
                
                // 重置重启计数
                restartCount.set(0)
                
                // 构建启动命令
                val ssLocalPath = "$nativeLibDir/libsslocal.so"
                val commandArgs = mutableListOf(
                    ssLocalPath,
                    "-c", tmpConfigFile.absolutePath
                )
                
                // 添加ACL配置
                if (preferences.getExcludeChinaIp()) {
                    extractAclFile(context)?.let { aclFile ->
                        commandArgs.addAll(listOf("--acl", aclFile.absolutePath))
                        Log.i(TAG, "使用ACL: ${aclFile.absolutePath}")
                    }
                }
                
                // 启动进程
                process = withContext(Dispatchers.IO) {
                    ProcessBuilder(commandArgs)
                        .redirectErrorStream(true)
                        .start()
                }
                
                // 获取进程PID
                processId = getProcessId(process)
                Log.i(TAG, "服务已启动, PID: $processId")
                
                // 读取进程输出
                startOutputMonitoring()
                
                isRunning.set(true)
                
                // 启动进程监控
                startProcessMonitor(context)
                
                true
            } catch (e: Exception) {
                Log.e(TAG, "服务启动错误", e)
                cleanupResources()
                false
            }
        }

        /**
         * 监控进程输出
         */
        private fun startOutputMonitoring() {
            process?.let { proc ->
                logJob = serviceScope.launch {
                    try {
                        BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                            reader.lineSequence()
                                .takeWhile { isActive }
                                .forEach { /* 可以选择不记录日志或只记录错误 */ }
                        }
                    } catch (e: Exception) { 
                        if (isRunning.get()) Log.e(TAG, "读取输出失败", e)
                    }
                }
            }
        }
        
        /**
         * 监控进程状态
         */
        private fun startProcessMonitor(context: Context) {
            serviceScope.launch {
                process?.let { proc ->
                    try {
                        // 等待进程结束
                        val exitCode = proc.waitFor()
                        
                        // 检查进程是否是被我们主动停止的
                        if (isRunning.get()) {
                            Log.w(TAG, "VPN服务停止, 错误: $exitCode")
                            
                            // 尝试重启，限制重启次数
                            if (restartCount.incrementAndGet() <= MAX_RESTART_ATTEMPTS) {
                                Log.i(TAG, "正在连接 (${restartCount.get()}/$MAX_RESTART_ATTEMPTS)")
                                delay(RESTART_DELAY)
                                cleanupResources()
                                startService(context)
                            } else {
                                Log.e(TAG, "VPN服务重启失败")
                                stopService()
                                stopVpnService(context)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "进程监控错误", e)
                    }
                }
            }
        }

        /**
         * 停止VPN服务
         */
        private fun stopVpnService(context: Context) {
            try {
                // 向TProxyService发送停止指令
                context.startService(Intent(context, TProxyService::class.java).apply {
                    action = TProxyService.ACTION_DISCONNECT
                })
                
                // 显示通知
                showFailureNotification(context)
            } catch (e: Exception) {
                Log.e(TAG, "服务停止错误", e)
            }
        }
        
        /**
         * 显示失败通知
         */
        private fun showFailureNotification(context: Context) {
            try {
                val notificationManager = context.getSystemService(NotificationManager::class.java)
                
                // 创建通知渠道
                val channel = NotificationChannel(
                    ERROR_CHANNEL_ID,
                    context.getString(R.string.error),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply { 
                    description = context.getString(R.string.shadowsocks_start_failed) 
                }
                
                notificationManager.createNotificationChannel(channel)
                
                // 创建通知
                val pendingIntent = PendingIntent.getActivity(
                    context, 0, 
                    context.packageManager.getLaunchIntentForPackage(context.packageName), 
                    PendingIntent.FLAG_IMMUTABLE
                )
                
                val notification = Notification.Builder(context, ERROR_CHANNEL_ID)
                    .setContentTitle(context.getString(R.string.vpn_service_stopped))
                    .setContentText(context.getString(R.string.vpn_service_restart_failed))
                    .setSmallIcon(R.drawable.baseline_vpn_lock_24)
                    .setAutoCancel(true)
                    .setContentIntent(pendingIntent)
                    .build()
                
                // 显示通知
                notificationManager.notify(ERROR_NOTIFICATION_ID, notification)
            } catch (e: Exception) { 
                Log.e(TAG, "显示通知失败", e)
            }
        }

        /**
         * 获取进程ID
         */
        private fun getProcessId(process: Process?): Int = try {
            process?.javaClass?.getDeclaredField("pid")?.apply {
                isAccessible = true
            }?.getInt(process) ?: -1
        } catch (e: Exception) { 
            Log.e(TAG, "获取进程ID失败", e)
            -1
        }

        /**
         * 停止服务
         */
        fun stopService() {
            if (!isRunning.getAndSet(false)) return
            
            try {
                // 取消日志监控
                logJob?.cancel()
                logJob = null
                
                // 终止进程
                if (processId > 0) android.os.Process.killProcess(processId)
                
                process?.let {
                    it.destroy()
                    try { it.waitFor() } catch(_: Exception) {}
                    if (it.isAlive) it.destroyForcibly()
                }
                
                process = null
                processId = -1
            } catch (e: Exception) { 
                Log.e(TAG, "停止服务失败", e)
            }
        }
        
        /**
         * 清理资源
         */
        private fun cleanupResources() {
            logJob?.cancel()
            logJob = null
            
            process?.destroy()
            process = null
            processId = -1
        }

        /**
         * 检查服务状态
         */
        fun isServiceRunning() = isRunning.get()
        
        /**
         * 提取ACL文件
         */
        private suspend fun extractAclFile(context: Context): File? {
            val aclDir = context.getDir("acl", Context.MODE_PRIVATE)
            val aclFile = File(aclDir, ACL_FILE_NAME)
            
            // 文件已存在则直接返回
            if (aclFile.exists() && aclFile.length() > 0) return aclFile
            
            return try {
                withContext(Dispatchers.IO) {
                    context.assets.open(ACL_FILE_NAME).use { input ->
                        aclFile.outputStream().use { output ->
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
    }
}