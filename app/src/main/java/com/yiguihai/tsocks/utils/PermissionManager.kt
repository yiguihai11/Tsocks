package com.yiguihai.tsocks.utils

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.text.style.TextAlign
import androidx.core.content.ContextCompat

class PermissionManager(private val activity: ComponentActivity) {
    private val vpnPermissionLauncher: ActivityResultLauncher<Intent> = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Log.d("PermissionManager", "VPN 权限已授予")
        } else {
            Log.w("PermissionManager", "VPN 权限被拒绝")
            _showVpnPermissionDialog = true
        }
    }

    private val notificationPermissionLauncher: ActivityResultLauncher<String> = activity.registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Log.d("PermissionManager", "通知权限已授予")
        } else {
            Log.w("PermissionManager", "通知权限被拒绝")
            _showNotificationPermissionDialog = true
        }
    }

    private val multiplePermissionsLauncher: ActivityResultLauncher<Array<String>> = activity.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions: Map<String, Boolean> ->
        permissions.forEach { (permission: String, isGranted: Boolean) ->
            if (isGranted) {
                Log.d("PermissionManager", "$permission 权限已授予")
            } else {
                Log.w("PermissionManager", "$permission 权限被拒绝")
                when (permission) {
                    Manifest.permission.ACCESS_NETWORK_STATE,
                    Manifest.permission.INTERNET -> _showNetworkPermissionDialog = true
                    Manifest.permission.FOREGROUND_SERVICE -> _showServicePermissionDialog = true
                    Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS -> _showBatteryPermissionDialog = true
                    Manifest.permission.RECEIVE_BOOT_COMPLETED -> _showBootPermissionDialog = true
                    Manifest.permission.WAKE_LOCK -> _showWakeLockPermissionDialog = true
                }
            }
        }
    }

    // 权限对话框状态
    private var _showVpnPermissionDialog by mutableStateOf(false)
    private var _showNotificationPermissionDialog by mutableStateOf(false)
    private var _showNetworkPermissionDialog by mutableStateOf(false)
    private var _showServicePermissionDialog by mutableStateOf(false)
    private var _showBatteryPermissionDialog by mutableStateOf(false)
    private var _showBootPermissionDialog by mutableStateOf(false)
    private var _showWakeLockPermissionDialog by mutableStateOf(false)

    // 公开的只读属性
    val showVpnPermissionDialog: Boolean get() = _showVpnPermissionDialog
    val showNotificationPermissionDialog: Boolean get() = _showNotificationPermissionDialog
    val showNetworkPermissionDialog: Boolean get() = _showNetworkPermissionDialog
    val showServicePermissionDialog: Boolean get() = _showServicePermissionDialog
    val showBatteryPermissionDialog: Boolean get() = _showBatteryPermissionDialog
    val showBootPermissionDialog: Boolean get() = _showBootPermissionDialog
    val showWakeLockPermissionDialog: Boolean get() = _showWakeLockPermissionDialog

    fun checkAndRequestAllPermissions() {
        checkVpnPermission()
        checkNotificationPermission()
        checkOtherPermissions()
    }

    private fun checkVpnPermission() {
        val vpnIntent = VpnService.prepare(activity)
        if (vpnIntent != null) {
            vpnPermissionLauncher.launch(vpnIntent)
        } else {
            Log.d("PermissionManager", "VPN 权限已存在")
        }
    }

    private fun checkNotificationPermission() {
        when {
            ContextCompat.checkSelfPermission(
                activity,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED -> {
                Log.d("PermissionManager", "通知权限已存在")
            }
            else -> {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun checkOtherPermissions() {
        val permissions = mutableListOf<String>()
        
        // 检查网络相关权限
        if (ContextCompat.checkSelfPermission(
                activity,
                Manifest.permission.ACCESS_NETWORK_STATE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(Manifest.permission.ACCESS_NETWORK_STATE)
        }

        if (ContextCompat.checkSelfPermission(
                activity,
                Manifest.permission.INTERNET
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(Manifest.permission.INTERNET)
        }

        // 检查前台服务权限
        if (ContextCompat.checkSelfPermission(
                activity,
                Manifest.permission.FOREGROUND_SERVICE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(Manifest.permission.FOREGROUND_SERVICE)
        }

        // 检查电池优化权限
        if (ContextCompat.checkSelfPermission(
                activity,
                Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        }

        // 检查开机自启动权限
        if (ContextCompat.checkSelfPermission(
                activity,
                Manifest.permission.RECEIVE_BOOT_COMPLETED
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(Manifest.permission.RECEIVE_BOOT_COMPLETED)
        }

        // 检查 Wake Lock 权限
        if (ContextCompat.checkSelfPermission(
                activity,
                Manifest.permission.WAKE_LOCK
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(Manifest.permission.WAKE_LOCK)
        }

        // 如果有需要申请的权限，则请求
        if (permissions.isNotEmpty()) {
            multiplePermissionsLauncher.launch(permissions.toTypedArray())
        }
    }

    fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.fromParts("package", activity.packageName, null)
        }
        activity.startActivity(intent)
    }

    // 用于更新对话框状态的方法
    fun updateDialogState(dialogType: String, show: Boolean) {
        when (dialogType) {
            "vpn" -> _showVpnPermissionDialog = show
            "notification" -> _showNotificationPermissionDialog = show
            "network" -> _showNetworkPermissionDialog = show
            "service" -> _showServicePermissionDialog = show
            "battery" -> _showBatteryPermissionDialog = show
            "boot" -> _showBootPermissionDialog = show
            "wakelock" -> _showWakeLockPermissionDialog = show
        }
    }

    // 请求VPN权限的方法，单独提取出来以便在其他地方直接调用
    fun requestVpnPermission() {
        checkVpnPermission()
    }

    // 所有可能的权限检查，适合首次启动时使用
}

/**
 * 权限请求对话框
 */
@Composable
fun PermissionDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    confirmText: String = "去设置",
    dismissText: String = "取消"
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                textAlign = TextAlign.Center
            )
        },
        text = {
            Text(
                text = message,
                textAlign = TextAlign.Center
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = confirmText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = dismissText)
            }
        }
    )
}

// Composable 函数用于检查权限状态和显示对话框
@Composable
fun PermissionCheck(permissionManager: PermissionManager) {
    // VPN 权限对话框
    if (permissionManager.showVpnPermissionDialog) {
        PermissionDialog(
            title = "需要 VPN 权限",
            message = "VPN 权限是应用的核心功能，请在系统设置中授予权限。",
            onDismiss = { permissionManager.updateDialogState("vpn", false) },
            onConfirm = { 
                permissionManager.updateDialogState("vpn", false)
                permissionManager.openAppSettings()
            }
        )
    }

    // 通知权限对话框
    if (permissionManager.showNotificationPermissionDialog) {
        PermissionDialog(
            title = "需要通知权限",
            message = "通知权限用于显示 VPN 状态和重要信息。",
            onDismiss = { permissionManager.updateDialogState("notification", false) },
            onConfirm = { 
                permissionManager.updateDialogState("notification", false)
                permissionManager.openAppSettings()
            }
        )
    }

    // 网络权限对话框
    if (permissionManager.showNetworkPermissionDialog) {
        PermissionDialog(
            title = "需要网络权限",
            message = "网络权限用于 VPN 连接和网络访问。",
            onDismiss = { permissionManager.updateDialogState("network", false) },
            onConfirm = { 
                permissionManager.updateDialogState("network", false)
                permissionManager.openAppSettings()
            }
        )
    }

    // 前台服务权限对话框
    if (permissionManager.showServicePermissionDialog) {
        PermissionDialog(
            title = "需要前台服务权限",
            message = "前台服务权限用于保持 VPN 服务在后台运行。",
            onDismiss = { permissionManager.updateDialogState("service", false) },
            onConfirm = { 
                permissionManager.updateDialogState("service", false)
                permissionManager.openAppSettings()
            }
        )
    }

    // 电池优化权限对话框
    if (permissionManager.showBatteryPermissionDialog) {
        PermissionDialog(
            title = "需要电池优化权限",
            message = "电池优化权限用于确保 VPN 服务不会被系统优化关闭。",
            onDismiss = { permissionManager.updateDialogState("battery", false) },
            onConfirm = { 
                permissionManager.updateDialogState("battery", false)
                permissionManager.openAppSettings()
            }
        )
    }

    // 开机自启动权限对话框
    if (permissionManager.showBootPermissionDialog) {
        PermissionDialog(
            title = "需要开机自启动权限",
            message = "开机自启动权限用于在设备重启后自动启动 VPN 服务。",
            onDismiss = { permissionManager.updateDialogState("boot", false) },
            onConfirm = { 
                permissionManager.updateDialogState("boot", false)
                permissionManager.openAppSettings()
            }
        )
    }

    // Wake Lock 权限对话框
    if (permissionManager.showWakeLockPermissionDialog) {
        PermissionDialog(
            title = "需要 Wake Lock 权限",
            message = "Wake Lock 权限用于保持 VPN 服务稳定运行。",
            onDismiss = { permissionManager.updateDialogState("wakelock", false) },
            onConfirm = { 
                permissionManager.updateDialogState("wakelock", false)
                permissionManager.openAppSettings()
            }
        )
    }
} 