/*
 ============================================================================
 Name        : ServiceReceiver.kt
 Author      : hev <r@hev.cc>
 Copyright   : Copyright (c) 2023 xyz
 Description : ServiceReceiver
 ============================================================================
 */

package com.yiguihai.tsocks

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log

class ServiceReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "ServiceReceiver"
        const val ACTION_VPN_STATE_CHANGED = "com.yiguihai.tsocks.VPN_STATE_CHANGED"
        const val EXTRA_VPN_STATE = "vpn_state"
        const val VPN_STATE_DISCONNECTED = 0
        const val VPN_STATE_CONNECTING = 1
        const val VPN_STATE_CONNECTED = 2
        const val VPN_STATE_FAILED = 3

        fun isVpnRunning(context: Context): Boolean {
            // 使用更现代的方式检查服务状态
            return try {
                // 检查VPN连接状态
                val preferences = Preferences.getInstance(context)
                preferences.isEnabled
            } catch (e: Exception) {
                Log.e("ServiceReceiver", "检查VPN状态失败", e)
                false
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "收到广播: ${intent.action}")
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> handleBootCompleted(context)
            Intent.ACTION_MY_PACKAGE_REPLACED -> handlePackageReplaced(context)
            ACTION_VPN_STATE_CHANGED -> handleVpnStateChanged(context, intent)
            Intent.ACTION_SHUTDOWN -> handleShutdown()
            Intent.ACTION_POWER_CONNECTED, Intent.ACTION_POWER_DISCONNECTED -> checkVpnStatus(context)
        }
    }

    private fun handleBootCompleted(context: Context) {
        if (!Preferences(context).isEnabled) return
        Log.d(TAG, "系统启动完成，准备启动VPN服务")
        startVpnServiceIfNeeded(context)
    }

    private fun handlePackageReplaced(context: Context) {
        val prefs = Preferences(context)
        if (prefs.isEnabled && prefs.autoReconnect) {
            Log.d(TAG, "应用已更新，重新启动VPN服务")
            startVpnServiceIfNeeded(context)
        }
    }

    private fun handleVpnStateChanged(context: Context, intent: Intent) {
        val state = intent.getIntExtra(EXTRA_VPN_STATE, VPN_STATE_DISCONNECTED)
        val prefs = Preferences(context)
        Log.d(TAG, "VPN状态变更: $state")
        when (state) {
            VPN_STATE_DISCONNECTED, VPN_STATE_FAILED -> {
                if (prefs.isEnabled && prefs.autoReconnect) {
                    Log.d(TAG, if (state == VPN_STATE_DISCONNECTED) "VPN已断开" else "VPN服务失败，尝试重连")
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        startVpnServiceIfNeeded(context)
                    }, if (state == VPN_STATE_FAILED) 3000 else 0)
                }
            }
        }
        // 更新首选项中的VPN状态
        prefs.isEnabled = state == VPN_STATE_CONNECTED || state == VPN_STATE_CONNECTING
    }

    private fun handleShutdown() {
        Log.d(TAG, "系统关机，保存状态")
    }

    private fun checkVpnStatus(context: Context) {
        val prefs = Preferences(context)
        if (prefs.isEnabled && prefs.autoReconnect && !isVpnRunning(context)) {
            Log.d(TAG, "检测到VPN未运行，尝试重启")
            startVpnServiceIfNeeded(context)
        }
    }

    private fun startVpnServiceIfNeeded(context: Context) {
        if (isVpnRunning(context)) return
        VpnService.prepare(context)?.let { intent ->
            Log.d(TAG, "需要VPN授权，等待用户操作")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } ?: run {
            Log.d(TAG, "已有VPN授权，直接启动服务")
            startVpnService(context)
        }
    }

    private fun startVpnService(context: Context) {
        Intent(context, TProxyService::class.java).apply {
            action = TProxyService.ACTION_CONNECT
            try {
                context.startForegroundService(this)
                Log.d(TAG, "VPN服务启动成功")
            } catch (e: Exception) {
                Log.e(TAG, "启动VPN服务失败", e)
            }
        }
    }
}