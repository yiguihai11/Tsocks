package com.yiguihai.tsocks.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import com.yiguihai.tsocks.AppInfo
import com.yiguihai.tsocks.BalancerConfig
import com.yiguihai.tsocks.LocalConfig
import com.yiguihai.tsocks.LogConfig
import com.yiguihai.tsocks.MiscConfig
import com.yiguihai.tsocks.OnlineConfig
import com.yiguihai.tsocks.RuntimeConfig
import com.yiguihai.tsocks.ServerConfig
import com.yiguihai.tsocks.ShadowsocksConfig
import com.yiguihai.tsocks.Socks5Config
import com.yiguihai.tsocks.TunnelConfig
import com.yiguihai.tsocks.utils.NetworkInfo
import com.yiguihai.tsocks.utils.SpeedTestConfig
import com.yiguihai.tsocks.utils.SpeedTestResult
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * AppInfo 的自定义 TypeAdapter，确保 packageName 始终是 String 类型
 */
class AppInfoTypeAdapter : com.google.gson.TypeAdapter<AppInfo>() {
    override fun write(out: JsonWriter, value: AppInfo?) {
        if (value == null) {
            out.nullValue()
            return
        }
        out.beginObject()
        out.name("packageName").value(value.packageName)
        out.name("appName").value(value.appName)
        out.name("uid").value(value.uid)
        out.name("isSystemApp").value(value.isSystemApp)
        out.name("isEnabled").value(value.isEnabled)
        out.endObject()
    }

    override fun read(reader: JsonReader): AppInfo {
        var packageName = ""
        var appName = ""
        var uid = 0
        var isSystemApp = false
        var isEnabled = false
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "packageName" -> packageName = reader.nextString()
                "appName" -> appName = reader.nextString()
                "uid" -> uid = reader.nextInt()
                "isSystemApp" -> isSystemApp = reader.nextBoolean()
                "isEnabled" -> isEnabled = reader.nextBoolean()
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return AppInfo(packageName, appName, uid, isSystemApp, isEnabled)
    }
}

/**
 * 管理应用程序首选项的类
 * 使用委托属性简化首选项访问
 */
class Preferences(context: Context) {
    companion object {
        // VPN基本首选项常量
        private const val VPN_PREF_NAME = "vpn_preferences"
        private const val KEY_ENABLED = "vpn_enabled"
        
        // 代理配置常量
        private const val PROXY_PREF_NAME = "proxy_config"
        private const val KEY_PROXY_MODE = "proxy_mode"
        private const val KEY_IPV4_ENABLED = "ipv4_enabled"
        private const val KEY_IPV6_ENABLED = "ipv6_enabled"
        private const val KEY_EXCLUDED_IPS = "excluded_ips"
        private const val KEY_PROXIED_APPS = "proxied_apps"
        private const val KEY_DNS_V4 = "dns_v4"
        private const val KEY_DNS_V6 = "dns_v6"
        private const val KEY_EXCLUDE_CHINA_IP = "exclude_china_ip"

        // HevSocks5Tunnel配置常量
        private const val TUNNEL_PREF_NAME = "hev_socks5_tunnel"
        private const val KEY_TUNNEL_CONFIG = "tunnel_config"
        private const val KEY_SOCKS5_CONFIG = "socks5_config"
        private const val KEY_MISC_CONFIG = "misc_config"

        // Shadowsocks配置常量
        private const val SS_PREF_NAME = "shadowsocks_config"
        private const val KEY_SS_LOCALS = "locals"
        private const val KEY_SS_SERVERS = "servers"
        private const val KEY_SS_BALANCER = "balancer"
        private const val KEY_SS_ONLINE_CONFIG = "online_config"
        private const val KEY_SS_LOG = "log"
        private const val KEY_SS_RUNTIME = "runtime"
        
        // 优化IP首选项常量
        private const val OPTIMAL_IP_PREF_NAME = "tsocks_preferences"
        private const val KEY_SPEED_TEST_CONFIG = "speed_test_config"
        private const val KEY_IMPORTED_IP_LIST = "imported_ip_list"
        private const val KEY_LAST_NETWORK_INFO = "last_network_info"
        private const val KEY_TEST_RESULTS = "test_results"
        
        // 单例模式
        @Volatile
        private var INSTANCE: Preferences? = null
        
        fun getInstance(context: Context): Preferences =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Preferences(context.applicationContext).also { INSTANCE = it }
            }
    }
    
    // SharedPreferences 实例
    private val vpnPrefs: SharedPreferences = context.getSharedPreferences(VPN_PREF_NAME, Context.MODE_PRIVATE)
    private val proxyPrefs: SharedPreferences = context.getSharedPreferences(PROXY_PREF_NAME, Context.MODE_PRIVATE)
    private val tunnelPrefs: SharedPreferences = context.getSharedPreferences(TUNNEL_PREF_NAME, Context.MODE_PRIVATE)
    private val ssPrefs: SharedPreferences = context.getSharedPreferences(SS_PREF_NAME, Context.MODE_PRIVATE)
    private val optimalIpPrefs: SharedPreferences = context.getSharedPreferences(OPTIMAL_IP_PREF_NAME, Context.MODE_PRIVATE)
    
    // 代理配置相关变量
    private val configScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val saveMutex = Mutex()
    private var lastSaveTime = 0L
    private val hasPendingChanges = AtomicBoolean(false)
    private val isConfigLoaded = AtomicBoolean(false)
    private val gson: Gson = GsonBuilder()
        .registerTypeAdapter(AppInfo::class.java, AppInfoTypeAdapter())
        .create()
    private val appProxyStatusCache = ConcurrentHashMap<String, Boolean>()
    
    // 代理设置保存延迟常量
    private val SAVE_DELAY = 200L
    private val SAVE_THROTTLE = 500L
    
    // HevSocks5Tunnel配置相关的状态
    private var tunnelConfig = mutableStateOf(TunnelConfig())
    private var socks5Config = mutableStateOf(Socks5Config())
    private var miscConfig = mutableStateOf(MiscConfig())
    
    // Shadowsocks配置状态
    private var ssLocals = mutableStateOf<List<LocalConfig>>(listOf())
    private var ssServers = mutableStateOf<List<ServerConfig>>(listOf())
    private var ssBalancer = mutableStateOf(BalancerConfig())
    private var ssOnlineConfig = mutableStateOf(OnlineConfig())
    private var ssLog = mutableStateOf(LogConfig())
    private var ssRuntime = mutableStateOf(RuntimeConfig())
    
    // VPN 是否启用
    var isEnabled: Boolean
        get() = vpnPrefs.getBoolean(KEY_ENABLED, false)
        set(value) = vpnPrefs.edit { putBoolean(KEY_ENABLED, value) }
    
    // 代理模式
    var proxyMode = mutableIntStateOf(0)
        private set
    
    // 获取代理模式的原始int值，避免自动装箱
    fun getProxyModeIntValue(): Int = proxyMode.intValue
    
    // IPv4配置
    var ipv4Enabled = mutableStateOf(true)
        private set
    
    // 获取IPv4配置的原始boolean值，避免自动装箱
    fun isIPv4Enabled(): Boolean = ipv4Enabled.value
    
    // IPv6配置
    var ipv6Enabled = mutableStateOf(true)
        private set
    
    // 获取IPv6配置的原始boolean值，避免自动装箱
    fun isIPv6Enabled(): Boolean = ipv6Enabled.value
    
    // 获取排除中国IP功能状态
    fun getExcludeChinaIp(): Boolean = proxyPrefs.getBoolean(KEY_EXCLUDE_CHINA_IP, false)
    
    // 更新排除中国IP功能状态
    fun updateExcludeChinaIp(enabled: Boolean) {
        proxyPrefs.edit { 
            putBoolean(KEY_EXCLUDE_CHINA_IP, enabled)
        }
        markProxyConfigChanged()
    }
    
    // DNS配置
    var dnsV4 = mutableStateOf("8.8.8.8")
        private set
    
    // 获取DNS V4的原始String值，避免自动装箱
    fun getDnsV4String(): String = dnsV4.value
    
    var dnsV6 = mutableStateOf("2001:4860:4860::8844")
        private set
    
    // 获取DNS V6的原始String值，避免自动装箱
    fun getDnsV6String(): String = dnsV6.value
    
    // 应用和IP列表
    val excludedIps = mutableStateListOf<String>()
    val proxiedApps = mutableStateListOf<AppInfo>()
    
    init {
        // 初始化代理配置
        loadProxyConfig()
        startAutoSaveTask()

        // 加载HevSocks5Tunnel配置
        loadTunnelConfigs()

        // 加载Shadowsocks配置
        loadShadowsocksConfigs()
    }
    
    // 重置所有首选项
    fun resetAll() {
        vpnPrefs.edit { clear() }
        proxyPrefs.edit { clear() }
        tunnelPrefs.edit { clear() }
        ssPrefs.edit { clear() }
        
        // 重新加载所有配置默认值
        loadProxyConfig()
        loadTunnelConfigs()
        loadShadowsocksConfigs()
    }

    // 获取代理应用配置JSON
    fun getProxiedAppsJson(): String? {
        return proxyPrefs.getString(KEY_PROXIED_APPS, "[]")
    }

    // 保存代理应用配置JSON
    fun saveProxiedAppsJson(json: String) {
        proxyPrefs.edit { 
            putString(KEY_PROXIED_APPS, json)
        }
    }
    
    // 加载代理配置
    private fun loadProxyConfig() {
        if (isConfigLoaded.get()) return

        try {
            with(proxyPrefs) {
                proxyMode.intValue = getInt(KEY_PROXY_MODE, 0)
                ipv4Enabled.value = getBoolean(KEY_IPV4_ENABLED, true)
                ipv6Enabled.value = getBoolean(KEY_IPV6_ENABLED, true)
                dnsV4.value = getString(KEY_DNS_V4, "8.8.8.8") ?: "8.8.8.8"
                dnsV6.value = getString(KEY_DNS_V6, "2001:4860:4860::8844") ?: "2001:4860:4860::8844"
            }

            // 加载排除的IP列表
            gson.fromJson<List<String>>(
                proxyPrefs.getString(KEY_EXCLUDED_IPS, "[]"),
                object : TypeToken<List<String>>() {}.type
            )?.let { list ->
                excludedIps.apply {
                    clear()
                    addAll(list)
                }
            }

            // 加载代理的应用列表
            val proxiedAppsJson = proxyPrefs.getString(KEY_PROXIED_APPS, "[]") ?: "[]"
            Log.d("Preferences", "加载应用列表JSON: $proxiedAppsJson")
            
            runCatching {
                val appsList = gson.fromJson<List<AppInfo>>(
                    proxiedAppsJson,
                    object : TypeToken<List<AppInfo>>() {}.type
                ) ?: emptyList()
                
                // 过滤掉可能的无效数据
                appsList.filter { it.packageName.isNotBlank() }
            }.onSuccess { apps ->
                appProxyStatusCache.clear()
                proxiedApps.apply {
                    clear()
                    // 确保packageName是字符串类型并添加到缓存
                    addAll(apps.map { app ->
                        if (app.isEnabled) {
                            appProxyStatusCache[app.packageName] = true
                            Log.d("Preferences", "已启用应用: ${app.packageName}")
                        }
                        app
                    })
                }
                Log.d("Preferences", "成功加载 ${apps.size} 个应用，其中 ${apps.count { it.isEnabled }} 个已启用")
            }.onFailure { e ->
                Log.e("Preferences", "加载应用列表失败: ${e.message}", e)
                // 清空列表
                proxiedApps.clear()
                appProxyStatusCache.clear()
            }

            isConfigLoaded.set(true)
            Log.d("Preferences", "代理配置加载完成")
        } catch (e: Exception) {
            Log.e("Preferences", "加载代理配置失败", e)
        }
    }

    // 自动保存配置的协程任务
    private fun startAutoSaveTask() {
        configScope.launch {
            while (isActive) {
                if (hasPendingChanges.get()) {
                    saveProxyConfig()
                    hasPendingChanges.set(false)
                }
                delay(SAVE_DELAY)
            }
        }
    }

    // 保存代理配置
    private suspend fun saveProxyConfig() {
        saveMutex.withLock {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastSaveTime < SAVE_THROTTLE) return
            lastSaveTime = currentTime

            withContext(Dispatchers.IO) {
                try {
                    val excludedIpsJson = gson.toJson(excludedIps.toList())
                    val fixedApps = proxiedApps.map { app ->
                        app.copy(packageName = app.packageName)
                    }
                    val proxiedAppsJson = gson.toJson(fixedApps)

                    proxyPrefs.edit().apply {
                        putInt(KEY_PROXY_MODE, proxyMode.intValue)
                        putBoolean(KEY_IPV4_ENABLED, ipv4Enabled.value)
                        putBoolean(KEY_IPV6_ENABLED, ipv6Enabled.value)
                        putString(KEY_DNS_V4, dnsV4.value)
                        putString(KEY_DNS_V6, dnsV6.value)
                        putString(KEY_EXCLUDED_IPS, excludedIpsJson)
                        putString(KEY_PROXIED_APPS, proxiedAppsJson)
                    }.apply()

                    Log.d("Preferences", "代理配置保存完成")
                } catch (e: Exception) {
                    Log.e("Preferences", "保存代理配置失败", e)
                }
            }
        }
    }

    // 代理配置更新方法
    fun updateProxyMode(mode: Int) {
        if (proxyMode.intValue != mode) {
            proxyMode.intValue = mode
            markProxyConfigChanged()
        }
    }

    fun updateIPv4Enabled(enabled: Boolean) {
        if (ipv4Enabled.value != enabled) {
            ipv4Enabled.value = enabled
            markProxyConfigChanged()
        }
    }

    fun updateIPv6Enabled(enabled: Boolean) {
        if (ipv6Enabled.value != enabled) {
            ipv6Enabled.value = enabled
            markProxyConfigChanged()
        }
    }

    fun updateDnsV4(dns: String) {
        if (dnsV4.value != dns) {
            dnsV4.value = dns
            markProxyConfigChanged()
        }
    }

    fun updateDnsV6(dns: String) {
        if (dnsV6.value != dns) {
            dnsV6.value = dns
            markProxyConfigChanged()
        }
    }

    fun addExcludedIp(ip: String) {
        if (ip !in excludedIps) {
            excludedIps.add(ip)
            markProxyConfigChanged()
        }
    }

    fun removeExcludedIp(ip: String) {
        if (excludedIps.remove(ip)) markProxyConfigChanged()
    }

    fun updateAppProxyStatus(packageName: String, enabled: Boolean) {
        appProxyStatusCache[packageName] = enabled
        proxiedApps.find { it.packageName == packageName }?.let { existingApp ->
            if (existingApp.isEnabled != enabled) {
                existingApp.isEnabled = enabled
                markProxyConfigChanged()
            }
        } ?: run {
            // 如果应用不在列表中且需要启用，则尝试添加到列表
            if (enabled) {
                Log.w("Preferences", "应用 $packageName 未在代理列表中找到，尝试添加")
                
                // 创建一个最小化的AppInfo对象添加到列表
                // 注意：此处缺少完整的应用信息，当用户重新打开APP列表时会加载完整信息
                val newApp = AppInfo(
                    packageName = packageName,
                    appName = packageName, // 临时使用packageName作为名称
                    uid = 0, // 临时UID
                    isSystemApp = false, // 默认为非系统应用
                    isEnabled = true // 标记为启用
                )
                proxiedApps.add(newApp)
            } else {
                // 应用不在列表中且不需要启用，无需操作
                Log.d("Preferences", "应用 $packageName 未在代理列表中找到且不需启用，忽略操作")
                return // 无需标记配置更改
            }
        }
        markProxyConfigChanged()
        
        // 添加一个强制保存机制，确保应用选择状态能够立即保存
        configScope.launch {
            saveProxyConfig()
        }
    }

    private fun markProxyConfigChanged() {
        hasPendingChanges.set(true)
    }

    // 获取配置加载状态
    fun isConfigLoaded(): Boolean {
        return isConfigLoaded.get()
    }

    // 获取配置
    fun getTunnelConfig(): TunnelConfig = tunnelConfig.value
    fun getSocks5Config(): Socks5Config = socks5Config.value
    fun getMiscConfig(): MiscConfig = miscConfig.value

    // 更新配置
    fun updateTunnelConfig(config: TunnelConfig) {
        tunnelConfig.value = config
        saveTunnelConfigs()
    }

    fun updateSocks5Config(config: Socks5Config) {
        socks5Config.value = config
        saveTunnelConfigs()
    }

    fun updateMiscConfig(config: MiscConfig) {
        miscConfig.value = config
        saveTunnelConfigs()
    }

    // 保存配置
    private fun saveTunnelConfigs() {
        tunnelPrefs.edit {
            putString(KEY_TUNNEL_CONFIG, gson.toJson(tunnelConfig.value))
            putString(KEY_SOCKS5_CONFIG, gson.toJson(socks5Config.value))
            putString(KEY_MISC_CONFIG, gson.toJson(miscConfig.value))
        }
    }

    // 加载HevSocks5Tunnel配置
    private fun loadTunnelConfigs() {
        try {
            tunnelPrefs.getString(KEY_TUNNEL_CONFIG, null)?.let {
                tunnelConfig.value = gson.fromJson(it, TunnelConfig::class.java) ?: TunnelConfig()
            }
            tunnelPrefs.getString(KEY_SOCKS5_CONFIG, null)?.let {
                socks5Config.value = gson.fromJson(it, Socks5Config::class.java) ?: Socks5Config()
            }
            tunnelPrefs.getString(KEY_MISC_CONFIG, null)?.let {
                miscConfig.value = gson.fromJson(it, MiscConfig::class.java) ?: MiscConfig()
            }
        } catch (e: Exception) {
            Log.e("Preferences", "加载HevSocks5Tunnel配置失败", e)
            // 使用默认配置
            tunnelConfig.value = TunnelConfig()
            socks5Config.value = Socks5Config()
            miscConfig.value = MiscConfig()
        }
    }

    // 重置HevSocks5Tunnel配置
    fun resetTunnelConfigs() {
        tunnelConfig.value = TunnelConfig()
        socks5Config.value = Socks5Config()
        miscConfig.value = MiscConfig()
        saveTunnelConfigs()
    }

    // Shadowsocks配置获取方法
    fun getShadowsocksConfig(): ShadowsocksConfig {
        // 只有当URL不为空时才包含online_config
        val onlineConfig = if (ssOnlineConfig.value.config_url.isBlank()) {
            // URL为空时返回null
            null
        } else {
            ssOnlineConfig.value
        }
        
        return ShadowsocksConfig(
            locals = ssLocals.value,
            servers = ssServers.value,
            balancer = ssBalancer.value,
            online_config = onlineConfig,
            log = ssLog.value,
            runtime = ssRuntime.value
        )
    }

    // 更新Shadowsocks配置
    fun updateShadowsocksConfig(config: ShadowsocksConfig) {
        // 处理online_config，如果URL为空则忽略此功能
        val safeOnlineConfig = if (config.online_config?.config_url.isNullOrBlank()) {
            // URL为空时使用空配置
            OnlineConfig()
        } else {
            config.online_config ?: OnlineConfig()
        }

        ssLocals.value = config.locals
        ssServers.value = config.servers
        ssBalancer.value = config.balancer
        ssOnlineConfig.value = safeOnlineConfig
        ssLog.value = config.log
        ssRuntime.value = config.runtime
        saveShadowsocksConfigs()
    }

    // 加载Shadowsocks配置
    private fun loadShadowsocksConfigs() {
        try {
            ssPrefs.getString(KEY_SS_LOCALS, null)?.let {
                ssLocals.value = gson.fromJson(it, object : TypeToken<List<LocalConfig>>() {}.type) ?: listOf()
            }
            ssPrefs.getString(KEY_SS_SERVERS, null)?.let {
                ssServers.value = gson.fromJson(it, object : TypeToken<List<ServerConfig>>() {}.type) ?: listOf()
            }
            ssPrefs.getString(KEY_SS_BALANCER, null)?.let {
                ssBalancer.value = gson.fromJson(it, BalancerConfig::class.java) ?: BalancerConfig()
            }
            
            // 只有当online_config存在且URL不为空时才加载
            val onlineConfigJson = ssPrefs.getString(KEY_SS_ONLINE_CONFIG, null)
            if (!onlineConfigJson.isNullOrBlank()) {
                val config = gson.fromJson(onlineConfigJson, OnlineConfig::class.java)
                // 只有当URL不为空时才设置值
                if (!config.config_url.isBlank()) {
                    ssOnlineConfig.value = config
                } else {
                    ssOnlineConfig.value = OnlineConfig() // 空配置
                }
            } else {
                ssOnlineConfig.value = OnlineConfig() // 默认空配置
            }
            
            ssPrefs.getString(KEY_SS_LOG, null)?.let {
                ssLog.value = gson.fromJson(it, LogConfig::class.java) ?: LogConfig()
            }
            ssPrefs.getString(KEY_SS_RUNTIME, null)?.let {
                ssRuntime.value = gson.fromJson(it, RuntimeConfig::class.java) ?: RuntimeConfig()
            }
        } catch (e: Exception) {
            Log.e("Preferences", "加载Shadowsocks配置失败", e)
            // 使用默认配置
            ssLocals.value = listOf()
            ssServers.value = listOf()
            ssBalancer.value = BalancerConfig()
            ssOnlineConfig.value = OnlineConfig() // 默认空配置
            ssLog.value = LogConfig()
            ssRuntime.value = RuntimeConfig()
        }
    }

    // 保存Shadowsocks配置
    private fun saveShadowsocksConfigs() {
        ssPrefs.edit {
            putString(KEY_SS_LOCALS, gson.toJson(ssLocals.value))
            putString(KEY_SS_SERVERS, gson.toJson(ssServers.value))
            putString(KEY_SS_BALANCER, gson.toJson(ssBalancer.value))
            
            // 只有当URL不为空时才保存online_config
            val onlineConfig = ssOnlineConfig.value
            if (!onlineConfig.config_url.isBlank()) {
                putString(KEY_SS_ONLINE_CONFIG, gson.toJson(onlineConfig))
            } else {
                // 如果URL为空，移除该键
                remove(KEY_SS_ONLINE_CONFIG)
            }
            
            putString(KEY_SS_LOG, gson.toJson(ssLog.value))
            putString(KEY_SS_RUNTIME, gson.toJson(ssRuntime.value))
        }
    }

    // 重置Shadowsocks配置
    fun resetShadowsocksConfigs() {
        ssLocals.value = listOf()
        ssServers.value = listOf()
        ssBalancer.value = BalancerConfig()
        ssOnlineConfig.value = OnlineConfig()
        ssLog.value = LogConfig()
        ssRuntime.value = RuntimeConfig()
        saveShadowsocksConfigs()
    }
    
    /**
     * 优选IP相关方法
     */
    
    /**
     * 保存测速配置
     */
    suspend fun saveSpeedTestConfig(config: SpeedTestConfig) {
        withContext(Dispatchers.IO) {
            try {
                val json = gson.toJson(config)
                optimalIpPrefs.edit().putString(KEY_SPEED_TEST_CONFIG, json).apply()
                Log.d("Preferences", "测速配置已保存")
            } catch (e: Exception) {
                Log.e("Preferences", "保存测速配置失败: ${e.message}", e)
            }
        }
    }
    
    /**
     * 加载测速配置
     * @return 保存的测速配置，如果没有则返回默认配置
     */
    suspend fun loadSpeedTestConfig(): SpeedTestConfig {
        return withContext(Dispatchers.IO) {
            try {
                val json = optimalIpPrefs.getString(KEY_SPEED_TEST_CONFIG, null)
                if (json != null) {
                    val config = gson.fromJson(json, SpeedTestConfig::class.java)
                    Log.d("Preferences", "加载测速配置: $config")
                    config
                } else {
                    Log.d("Preferences", "未找到保存的测速配置，使用默认配置")
                    SpeedTestConfig()
                }
            } catch (e: Exception) {
                Log.e("Preferences", "加载测速配置失败: ${e.message}", e)
                SpeedTestConfig()
            }
        }
    }
    
    /**
     * 保存导入的IP列表
     */
    suspend fun saveImportedIpList(ipList: List<String>) {
        withContext(Dispatchers.IO) {
            try {
                val json = gson.toJson(ipList)
                optimalIpPrefs.edit().putString(KEY_IMPORTED_IP_LIST, json).apply()
                Log.d("Preferences", "已保存 ${ipList.size} 个IP地址")
            } catch (e: Exception) {
                Log.e("Preferences", "保存IP列表失败: ${e.message}", e)
            }
        }
    }
    
    /**
     * 加载导入的IP列表
     * @return 保存的IP列表，如果没有则返回空列表
     */
    suspend fun loadImportedIpList(): List<String> {
        return withContext(Dispatchers.IO) {
            try {
                val json = optimalIpPrefs.getString(KEY_IMPORTED_IP_LIST, null)
                if (json != null) {
                    val type = object : TypeToken<List<String>>() {}.type
                    val ipList = gson.fromJson<List<String>>(json, type)
                    Log.d("Preferences", "加载IP列表: ${ipList.size} 个地址")
                    ipList
                } else {
                    Log.d("Preferences", "未找到保存的IP列表，返回空列表")
                    emptyList()
                }
            } catch (e: Exception) {
                Log.e("Preferences", "加载IP列表失败: ${e.message}", e)
                emptyList()
            }
        }
    }
    
    /**
     * 保存最后一次的网络信息
     */
    suspend fun saveNetworkInfo(networkInfo: NetworkInfo) {
        withContext(Dispatchers.IO) {
            try {
                val json = gson.toJson(networkInfo)
                optimalIpPrefs.edit().putString(KEY_LAST_NETWORK_INFO, json).apply()
                Log.d("Preferences", "网络信息已保存")
            } catch (e: Exception) {
                Log.e("Preferences", "保存网络信息失败: ${e.message}", e)
            }
        }
    }
    
    /**
     * 加载最后一次的网络信息
     * @return 保存的网络信息，如果没有则返回空对象
     */
    suspend fun loadNetworkInfo(): NetworkInfo {
        return withContext(Dispatchers.IO) {
            try {
                val json = optimalIpPrefs.getString(KEY_LAST_NETWORK_INFO, null)
                if (json != null) {
                    val networkInfo = gson.fromJson(json, NetworkInfo::class.java)
                    Log.d("Preferences", "加载网络信息: $networkInfo")
                    networkInfo
                } else {
                    Log.d("Preferences", "未找到保存的网络信息，返回默认值")
                    NetworkInfo()
                }
            } catch (e: Exception) {
                Log.e("Preferences", "加载网络信息失败: ${e.message}", e)
                NetworkInfo()
            }
        }
    }

    /**
     * 保存测速结果
     */
    suspend fun saveTestResults(results: List<SpeedTestResult>) {
        withContext(Dispatchers.IO) {
            try {
                val json = gson.toJson(results)
                optimalIpPrefs.edit().putString(KEY_TEST_RESULTS, json).apply()
                Log.d("Preferences", "已保存 ${results.size} 个测速结果")
            } catch (e: Exception) {
                Log.e("Preferences", "保存测速结果失败: ${e.message}", e)
            }
        }
    }
    
    /**
     * 加载保存的测速结果
     * @return 保存的测速结果，如果没有则返回空列表
     */
    suspend fun loadTestResults(): List<SpeedTestResult> {
        return withContext(Dispatchers.IO) {
            try {
                val json = optimalIpPrefs.getString(KEY_TEST_RESULTS, null)
                if (json != null) {
                    val type = object : TypeToken<List<SpeedTestResult>>() {}.type
                    val results = gson.fromJson<List<SpeedTestResult>>(json, type)
                    Log.d("Preferences", "加载测速结果: ${results.size} 个结果")
                    results
                } else {
                    Log.d("Preferences", "未找到保存的测速结果，返回空列表")
                    emptyList()
                }
            } catch (e: Exception) {
                Log.e("Preferences", "加载测速结果失败: ${e.message}", e)
                emptyList()
            }
        }
    }
} 