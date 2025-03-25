package com.yiguihai.tsocks

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.yiguihai.tsocks.ui.theme.TSocksTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var permissionManager: PermissionManager
    
    // 对外提供初始化状态标志，其他页面可以在使用前检查
    companion object {
        // 初始化状态标志
        @Volatile
        var isGeoIpInitialized = false
            private set
        
        @Volatile
        var isPreferencesInitialized = false
            private set
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // 初始化权限管理器
        permissionManager = PermissionManager(this)
        
        // 先执行一些必须立即完成的初始化
        // 确保Preferences在UI显示前可用
        Preferences.getInstance(applicationContext)
        isPreferencesInitialized = true
        
        // 立即设置UI，不等待其他初始化完成
        // 各个组件会在它们自己的Composable中处理加载状态
        setContent {
            TSocksTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // 使用原来的 MainApp 作为主界面
                    MainApp()
                    
                    // 添加权限检查
                    PermissionCheck(permissionManager)
                }
            }
        }
        
        // 先设置 UI，然后并行执行所有初始化工作
        lifecycleScope.launch {
            // 启动所有初始化任务，并行执行
            withContext(Dispatchers.IO) {
                val permissionJob = async { permissionManager.checkAndRequestAllPermissions() }
                val preferencesJob = async { initializePreferences() }
                val geoIpJob = async { 
                    GeoIpUtils.initialize(applicationContext)
                    isGeoIpInitialized = true
                    Log.d("MainActivity", "GeoIP数据库初始化完成")
                }
                
                // 等待关键任务完成
                permissionJob.await()
                preferencesJob.await()
                geoIpJob.await()
                
                // 其他可选的初始化步骤可以在这里添加
                Log.d("MainActivity", "所有初始化任务完成")
            }
        }
    }
    
    // 分离初始化首选项的逻辑到单独的函数
    private suspend fun initializePreferences() {
        withContext(Dispatchers.IO) {
            try {
                val preferences = Preferences.getInstance(applicationContext)
                val proxiedAppsJson = preferences.getProxiedAppsJson()
                
                // 如果发现损坏的JSON数据，则清除它
                if (proxiedAppsJson != null && proxiedAppsJson != "[]") {
                    try {
                        val gson = Gson()
                        val appsList = gson.fromJson<List<AppInfo>>(
                            proxiedAppsJson, 
                            object : TypeToken<List<AppInfo>>() {}.type
                        )
                        // 验证解析结果非空
                        if (appsList == null) {
                            Log.e("MainActivity", "JSON数据解析结果为空，正在清除")
                            preferences.saveProxiedAppsJson("[]")
                        } else {
                            // 数据有效，无需处理
                            Log.d("MainActivity", "JSON数据正常，包含${appsList.size}个应用")
                        }
                    } catch (e: Exception) {
                        Log.e("MainActivity", "检测到损坏的JSON数据，正在清除", e)
                        preferences.saveProxiedAppsJson("[]")
                    }
                } else {
                    // 空的JSON数组，不需要处理
                    Log.d("MainActivity", "首选项中没有应用数据或为空数组")
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "修复首选项数据时出错", e)
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // 释放GeoIP资源
        GeoIpUtils.release()
        isGeoIpInitialized = false
    }
}