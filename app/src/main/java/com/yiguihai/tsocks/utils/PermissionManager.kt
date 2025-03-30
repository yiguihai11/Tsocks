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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.core.content.ContextCompat
import com.yiguihai.tsocks.R

class PermissionManager(private val activity: ComponentActivity) {
    private val vpnPermissionLauncher: ActivityResultLauncher<Intent> = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Log.d("PermissionManager", activity.getString(R.string.vpn_permission_granted))
        } else {
            Log.w("PermissionManager", activity.getString(R.string.vpn_permission_denied))
            _showVpnPermissionDialog = true
        }
    }

    private val notificationPermissionLauncher: ActivityResultLauncher<String> = activity.registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Log.d("PermissionManager", activity.getString(R.string.notification_permission_granted))
        } else {
            Log.w("PermissionManager", activity.getString(R.string.notification_permission_denied))
            _showNotificationPermissionDialog = true
        }
    }

    private val multiplePermissionsLauncher: ActivityResultLauncher<Array<String>> = activity.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions: Map<String, Boolean> ->
        permissions.forEach { (permission: String, isGranted: Boolean) ->
            if (isGranted) {
                Log.d("PermissionManager", activity.getString(R.string.permission_granted, permission))
            } else {
                Log.w("PermissionManager", activity.getString(R.string.permission_denied, permission))
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
            Log.d("PermissionManager", activity.getString(R.string.permission_already_exists, "VPN"))
        }
    }

    private fun checkNotificationPermission() {
        when {
            ContextCompat.checkSelfPermission(
                activity,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED -> {
                Log.d("PermissionManager", activity.getString(R.string.permission_already_exists, activity.getString(R.string.notification_permission_required)))
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
    confirmText: String = stringResource(R.string.go_to_settings),
    dismissText: String = stringResource(R.string.cancel)
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
            title = stringResource(R.string.vpn_permission_required),
            message = stringResource(R.string.vpn_permission_message),
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
            title = stringResource(R.string.notification_permission_required),
            message = stringResource(R.string.notification_permission_message),
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
            title = stringResource(R.string.network_permission_required),
            message = stringResource(R.string.network_permission_message),
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
            title = stringResource(R.string.service_permission_required),
            message = stringResource(R.string.service_permission_message),
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
            title = stringResource(R.string.battery_permission_required),
            message = stringResource(R.string.battery_permission_message),
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
            title = stringResource(R.string.boot_permission_required),
            message = stringResource(R.string.boot_permission_message),
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
            title = stringResource(R.string.wakelock_permission_required),
            message = stringResource(R.string.wakelock_permission_message),
            onDismiss = { permissionManager.updateDialogState("wakelock", false) },
            onConfirm = { 
                permissionManager.updateDialogState("wakelock", false)
                permissionManager.openAppSettings()
            }
        )
    }
} 