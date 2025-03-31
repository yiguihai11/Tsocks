package com.yiguihai.tsocks.utils

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStreamReader

/**
 * 测速结果
 */
@Immutable
data class SpeedTestResult(
    val ip: String,
    val port: Int,
    val dataCenter: String = "",
    val region: String = "",
    val city: String = "",
    val latency: String = "",
    val latencyMs: Long = 0L,
    val downloadSpeed: Double = 0.0,
    val status: String = "",
    val isSuccessful: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 网络相关信息，包含运营商和外网IP
 */
data class NetworkInfo(
    val carrier: String = "", // 运营商信息
    val externalIp: String = "", // 外网IP地址
    val timestamp: Long = System.currentTimeMillis() // 获取时间
)

/**
 * 测速配置
 */
data class SpeedTestConfig(
    val testMode: String = "tcping",  // "ping" 或 "tcping"
    val maxThreads: Int = 100,
    val speedTestThreads: Int = 5,
    val speedTestUrl: String = "https://speed.cloudflare.com/__down?bytes=500000000",
    val maxTimeout: Int = if (testMode == "ping") 1000 else 2000
)

/**
 * Cloudflare数据中心位置信息
 */
@Immutable
data class CloudflareLocation(
    @SerializedName("iata")
    val iata: String,
    
    @SerializedName("lat")
    val latitude: Double,
    
    @SerializedName("lon")
    val longitude: Double,
    
    @SerializedName("cca2")
    val countryCode: String,
    
    @SerializedName("region")
    val region: String,
    
    @SerializedName("city")
    val city: String
)

/**
 * 优选IP管理
 */
object OptimalIpManager {
    // 保存全部导入的IP列表
    val importedIpList = mutableStateListOf<String>()
    
    // 当前测速结果
    val testResults = mutableStateListOf<SpeedTestResult>()
    
    // 测速状态 - 使用同步机制保护
    private val stateLock = Any()
    val isTestingInProgress = mutableStateOf(false)
    val currentProgress = mutableStateOf(0)
    val totalIps = mutableStateOf(0)
    val statusMessage = mutableStateOf("")
    
    // 保存的历史测速结果
    val savedTestResults = mutableStateListOf<SpeedTestResult>()
    
    // 网络相关信息
    val networkInfo = mutableStateOf(NetworkInfo())
    
    // 数据中心位置信息缓存
    private var locationMap: Map<String, CloudflareLocation> = emptyMap()
    
    /**
     * 加载Cloudflare位置数据
     */
    suspend fun loadLocations(context: Context) {
        if (locationMap.isNotEmpty()) return
        
        withContext(Dispatchers.IO) {
            try {
                context.assets.open("locations.json").use { inputStream ->
                    val reader = InputStreamReader(inputStream)
                    val locations = Gson().fromJson(reader, Array<CloudflareLocation>::class.java)
                    locationMap = locations.associateBy { it.iata }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    /**
     * 根据IATA代码获取位置信息
     */
    fun getLocationByIata(iata: String): CloudflareLocation? {
        return locationMap[iata]
    }
    
    /**
     * 清除当前测试结果
     */
    fun clearCurrentResults() {
        synchronized(stateLock) {
            testResults.clear()
            updateProgress(0)
            updateTotalIps(0)
            updateStatusMessage("")
        }
    }
    
    /**
     * 保存当前测试结果到历史记录中
     */
    fun saveCurrentResultsToHistory() {
        synchronized(stateLock) {
            savedTestResults.clear()
            savedTestResults.addAll(testResults)
        }
    }
    
    /**
     * 停止测速
     */
    fun stopTesting() {
        synchronized(stateLock) {
            isTestingInProgress.value = false
        }
    }
    
    /**
     * 更新网络信息
     */
    fun updateNetworkInfo(carrier: String, externalIp: String) {
        synchronized(stateLock) {
            networkInfo.value = NetworkInfo(
                carrier = carrier,
                externalIp = externalIp,
                timestamp = System.currentTimeMillis()
            )
        }
    }
    
    /**
     * 线程安全地更新进度
     */
    fun updateProgress(progress: Int) {
        synchronized(stateLock) {
            currentProgress.value = progress
        }
    }
    
    /**
     * 线程安全地更新总IP数
     */
    fun updateTotalIps(count: Int) {
        synchronized(stateLock) {
            totalIps.value = count
        }
    }
    
    /**
     * 线程安全地更新状态消息
     */
    fun updateStatusMessage(message: String) {
        synchronized(stateLock) {
            statusMessage.value = message
        }
    }
    
    /**
     * 线程安全地添加测试结果
     */
    fun addTestResult(result: SpeedTestResult) {
        synchronized(stateLock) {
            testResults.add(result)
        }
    }
    
    /**
     * 线程安全地更新测试结果
     */
    fun updateTestResult(result: SpeedTestResult) {
        synchronized(stateLock) {
            val index = testResults.indexOfFirst { it.ip == result.ip }
            if (index >= 0) {
                testResults[index] = result
            }
        }
    }
} 