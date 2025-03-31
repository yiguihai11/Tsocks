package com.yiguihai.tsocks.utils

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.yiguihai.tsocks.ShadowsocksConfig
import com.yiguihai.tsocks.ServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Shadowsocks导入工具类
 * 用于将优选IP列表导入到Shadowsocks配置中
 */
object ShadowsocksImporter {
    private const val TAG = "ShadowsocksImporter"
    
    /**
     * 导入优选IP到Shadowsocks配置
     * 
     * @param context 上下文
     * @param ipList 按速度排序的IP列表
     * @return 成功导入的IP数量
     */
    suspend fun importOptimalIps(context: Context, ipList: List<SpeedTestResult>): Int = withContext(Dispatchers.IO) {
        try {
            if (ipList.isEmpty()) {
                Log.w(TAG, "IP列表为空，无法导入")
                return@withContext 0
            }
            
            // 获取当前Shadowsocks配置
            val preferences = com.yiguihai.tsocks.utils.Preferences.getInstance(context)
            val ssConfig = preferences.getShadowsocksConfig()
            
            // 记录修改的服务器数量
            var modifiedCount = 0
            
            // 遍历服务器配置
            val servers = ssConfig.servers.toMutableList()
            val sortedIpList = ipList.filter { it.downloadSpeed > 0 }.sortedByDescending { it.downloadSpeed }
            
            if (sortedIpList.isEmpty()) {
                Log.w(TAG, "没有有效的测速结果，无法导入")
                return@withContext 0
            }
            
            // 遍历服务器配置
            for (i in servers.indices) {
                val server = servers[i]
                
                // 检查服务器是否使用v2ray插件
                if (!server.plugin.isNullOrEmpty() && 
                    server.plugin.equals("v2ray-plugin", ignoreCase = true) &&
                    !server.plugin_opts.isNullOrEmpty() && 
                    !server.plugin_opts.contains("quic", ignoreCase = true)) {
                    
                    // 替换IP地址
                    val ipIndex = modifiedCount % sortedIpList.size
                    val newIp = sortedIpList[ipIndex].ip
                    val oldIp = server.server
                    
                    servers[i] = server.copy(server = newIp)
                    
                    Log.d(TAG, "服务器 #${i+1}: IP从 $oldIp 替换为 $newIp")
                    modifiedCount++
                }
            }
            
            // 如果有修改，更新配置
            if (modifiedCount > 0) {
                // 创建新的配置对象
                val newConfig = ssConfig.copy(servers = servers)
                
                // 保存配置
                preferences.saveShadowsocksConfig(newConfig)
                
                Log.d(TAG, "成功更新 $modifiedCount 个服务器的IP地址")
            } else {
                Log.w(TAG, "没有找到符合条件的服务器配置")
            }
            
            modifiedCount
        } catch (e: Exception) {
            Log.e(TAG, "导入IP失败: ${e.message}", e)
            0
        }
    }
    
    /**
     * 导入优选IP到Shadowsocks配置文件
     * 直接修改JSON配置文件
     * 
     * @param context 上下文
     * @param ipList 按速度排序的IP列表
     * @return 成功导入的IP数量
     */
    suspend fun importOptimalIpsToConfigFile(context: Context, ipList: List<SpeedTestResult>): Int = withContext(Dispatchers.IO) {
        try {
            if (ipList.isEmpty()) {
                Log.w(TAG, "IP列表为空，无法导入")
                return@withContext 0
            }
            
            // 获取Shadowsocks实例
            val ssPreferences = com.yiguihai.tsocks.utils.Preferences.getInstance(context)
            
            // 获取当前Shadowsocks配置JSON
            val ssConfig = ssPreferences.getShadowsocksConfig()
            val gson = Gson()
            val configJsonString = gson.toJson(ssConfig)
            
            if (configJsonString.isNullOrEmpty()) {
                Log.w(TAG, "配置JSON为空，无法导入")
                return@withContext 0
            }
            
            // 移除注释
            val cleanedJsonString = removeComments(configJsonString)
            
            // 解析JSON
            val jsonObject = JsonParser.parseString(cleanedJsonString).asJsonObject
            
            // 获取服务器数组
            val serversArray = jsonObject.getAsJsonArray("servers") ?: JsonArray()
            
            // 记录修改的服务器数量
            var modifiedCount = 0
            
            // 过滤并排序IP列表
            val sortedIpList = ipList.filter { it.downloadSpeed > 0 }.sortedByDescending { it.downloadSpeed }
            
            if (sortedIpList.isEmpty()) {
                Log.w(TAG, "没有有效的测速结果，无法导入")
                return@withContext 0
            }
            
            // 遍历服务器配置
            for (i in 0 until serversArray.size()) {
                val serverObj = serversArray.get(i).asJsonObject
                
                // 检查服务器是否使用v2ray插件
                val hasPlugin = serverObj.has("plugin") && 
                               !serverObj.get("plugin").isJsonNull && 
                               serverObj.get("plugin").asString.equals("v2ray-plugin", ignoreCase = true)
                
                val hasPluginOpts = serverObj.has("plugin_opts") && 
                                   !serverObj.get("plugin_opts").isJsonNull
                
                val notUsingQuic = hasPluginOpts && 
                                  !serverObj.get("plugin_opts").asString.contains("quic", ignoreCase = true)
                
                if (hasPlugin && notUsingQuic) {
                    // 替换IP地址
                    val ipIndex = modifiedCount % sortedIpList.size
                    val newIp = sortedIpList[ipIndex].ip
                    val oldIp = serverObj.get("server").asString
                    
                    serverObj.addProperty("server", newIp)
                    
                    Log.d(TAG, "服务器 #${i+1}: IP从 $oldIp 替换为 $newIp")
                    modifiedCount++
                }
            }
            
            // 如果有修改，更新配置
            if (modifiedCount > 0) {
                // 转换为新的配置对象并保存
                val newConfig = gson.fromJson(jsonObject.toString(), ShadowsocksConfig::class.java)
                ssPreferences.saveShadowsocksConfig(newConfig)
                
                Log.d(TAG, "成功更新 $modifiedCount 个服务器的IP地址")
            } else {
                Log.w(TAG, "没有找到符合条件的服务器配置")
            }
            
            modifiedCount
        } catch (e: Exception) {
            Log.e(TAG, "导入IP失败: ${e.message}", e)
            0
        }
    }
    
    /**
     * 移除JSON字符串中的注释
     */
    private fun removeComments(jsonString: String): String {
        val lines = jsonString.split("\n")
        val cleanedLines = mutableListOf<String>()
        
        for (line in lines) {
            // 移除行内注释
            var cleanedLine = line.split("//")[0]
            // 移除行尾注释
            cleanedLine = cleanedLine.split("#")[0]
            
            cleanedLines.add(cleanedLine)
        }
        
        return cleanedLines.joinToString("\n")
    }
} 