package com.yiguihai.tsocks.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.telephony.TelephonyManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.Socket
import java.net.InetSocketAddress

/**
 * 网络工具类，用于获取运营商信息和外网IP
 */
object NetworkUtils {
    private const val TAG = "NetworkUtils"
    
    /**
     * 获取当前运营商信息
     */
    fun getCarrierName(context: Context): String {
        try {
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val operatorName = telephonyManager.networkOperatorName
            
            // 根据运营商名称返回中文名称
            return when {
                operatorName.contains("CHINA MOBILE", ignoreCase = true) 
                    || operatorName.contains("CMCC", ignoreCase = true) -> "中国移动"
                    
                operatorName.contains("CHN-UNICOM", ignoreCase = true) 
                    || operatorName.contains("UNICOM", ignoreCase = true) 
                    || operatorName.contains("CUCC", ignoreCase = true) -> "中国联通"
                    
                operatorName.contains("CHN-CT", ignoreCase = true) 
                    || operatorName.contains("TELECOM", ignoreCase = true) 
                    || operatorName.contains("CTCC", ignoreCase = true) -> "中国电信"
                    
                operatorName.contains("CHINA BROADCASTING NETWORK", ignoreCase = true) 
                    || operatorName.contains("CBN", ignoreCase = true) -> "中国广电"
                    
                operatorName.isNotEmpty() -> operatorName
                
                else -> "未知运营商"
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取运营商信息失败: ${e.message}", e)
            return "获取失败"
        }
    }
    
    /**
     * 获取网络连接类型 - 使用现代API
     */
    fun getNetworkType(context: Context): String {
        try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            
            // 获取活跃网络
            val network = connectivityManager.activeNetwork ?: return "无网络连接"
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return "无网络连接"
            
            return when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                    // 获取移动网络类型
                    val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                    when (telephonyManager.dataNetworkType) {
                        TelephonyManager.NETWORK_TYPE_LTE -> "4G"
                        TelephonyManager.NETWORK_TYPE_NR -> "5G"
                        TelephonyManager.NETWORK_TYPE_UMTS, 
                        TelephonyManager.NETWORK_TYPE_EVDO_0,
                        TelephonyManager.NETWORK_TYPE_HSDPA,
                        TelephonyManager.NETWORK_TYPE_HSPA,
                        TelephonyManager.NETWORK_TYPE_HSPAP -> "3G"
                        TelephonyManager.NETWORK_TYPE_GPRS,
                        TelephonyManager.NETWORK_TYPE_EDGE -> "2G"
                        else -> "移动网络"
                    }
                }
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "以太网"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "蓝牙网络"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                else -> "其他网络"
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取网络类型失败: ${e.message}", e)
            return "获取失败"
        }
    }
    
    /**
     * 获取外网IP地址
     * 通过多个备用接口尝试获取，提高成功率
     */
    suspend fun getExternalIpAddress(): String = withContext(Dispatchers.IO) {
        val ipProviders = listOf(
            "https://api.ipify.org",
            "https://api.ip.sb/ip",
            "https://ifconfig.me/ip",
            "https://icanhazip.com",
            "https://ipinfo.io/ip",
            "https://myexternalip.com/raw"
        )
        
        for (provider in ipProviders) {
            try {
                Log.d(TAG, "尝试从 $provider 获取外网IP")
                val url = URL(provider)
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.setRequestProperty("User-Agent", "Mozilla/5.0")
                
                if (connection.responseCode == 200) {
                    val ip = connection.inputStream.bufferedReader().use { it.readText().trim() }
                    if (ip.isNotEmpty() && isValidIpAddress(ip)) {
                        Log.d(TAG, "成功获取外网IP: $ip 从 $provider")
                        return@withContext ip
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "从 $provider 获取IP失败: ${e.message}")
                // 继续尝试下一个提供商
            }
        }
        
        // 如果所有API都失败，尝试使用备用方法
        try {
            Log.d(TAG, "尝试使用备用方法获取IP")
            val socket = Socket()
            socket.connect(InetSocketAddress("1.1.1.1", 80), 5000)
            val ip = socket.localAddress.hostAddress
            socket.close()
            
            if (!ip.isNullOrEmpty() && !ip.startsWith("127.") && !ip.startsWith("0.")) {
                Log.d(TAG, "通过Socket连接获取本地IP: $ip")
                return@withContext ip
            }
        } catch (e: Exception) {
            Log.e(TAG, "Socket方法获取IP失败: ${e.message}")
        }
        
        // 都失败了，返回空字符串
        Log.e(TAG, "所有方法获取外网IP均失败")
        return@withContext ""
    }
    
    /**
     * 验证字符串是否为有效的IP地址
     */
    private fun isValidIpAddress(ip: String): Boolean {
        return try {
            // 使用正则表达式检查是否是有效的IPv4地址
            val ipv4Regex = """^(([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])\.){3}([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])$""".toRegex()
            ipv4Regex.matches(ip)
        } catch (e: Exception) {
            false
        }
    }
} 