package com.yiguihai.tsocks

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import com.yiguihai.tsocks.ui.theme.TSocksTheme
import com.yiguihai.tsocks.utils.PermissionManager

// 创建CompositionLocal用于提供PermissionManager
val LocalPermissionManager = compositionLocalOf<PermissionManager> { 
    error("未提供PermissionManager") 
}

class MainActivity : ComponentActivity() {
    // 在Activity级别创建PermissionManager，确保生命周期正确
    private lateinit var permissionManager: PermissionManager
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // 初始化PermissionManager，在Activity创建时完成
        permissionManager = PermissionManager(this)
        
        setContent {
            TSocksTheme {
                // 使用CompositionLocalProvider提供PermissionManager给整个界面树
                CompositionLocalProvider(LocalPermissionManager provides permissionManager) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        MainApp()
                    }
                }
            }
        }
        
        // 应用启动后检查权限
        permissionManager.checkAndRequestAllPermissions()
    }
    
    // 提供获取PermissionManager实例的方法
    //fun getPermissionManager(): PermissionManager = permissionManager
}