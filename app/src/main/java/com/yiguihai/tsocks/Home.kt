package com.yiguihai.tsocks

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.util.Log
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.runtime.DisposableEffect

private const val TAG = "HomeScreen"

@Composable
fun HomeScreen(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier.weight(1f)
            ) {
                StatusCircle()
            }
            
            VpnSettings()
        }
    }
}

@Composable
fun StatusCircle() {
    val context = LocalContext.current
    var isRunning by remember { mutableStateOf(false) }
    
    // 检查VPN是否已在运行
    LaunchedEffect(Unit) {
        isRunning = ServiceReceiver.isVpnRunning(context)
    }
    
    // 注册广播接收器监听VPN状态变化
    DisposableEffect(Unit) {
        val vpnStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == ServiceReceiver.ACTION_VPN_STATE_CHANGED) {
                    val state = intent.getIntExtra(ServiceReceiver.EXTRA_VPN_STATE, ServiceReceiver.VPN_STATE_DISCONNECTED)
                    isRunning = state == ServiceReceiver.VPN_STATE_CONNECTED || state == ServiceReceiver.VPN_STATE_CONNECTING
                }
            }
        }
        
        val filter = IntentFilter(ServiceReceiver.ACTION_VPN_STATE_CHANGED)
        context.registerReceiver(vpnStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        
        // 清理时注销接收器
        onDispose {
            context.unregisterReceiver(vpnStateReceiver)
        }
    }
    
    // 添加旋转动画
    val rotation by animateFloatAsState(
        targetValue = if (isRunning) 360f else 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )
    
    // 添加发光效果大小动画
    val glowRadius by animateFloatAsState(
        targetValue = if (isRunning) 20f else 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow"
    )
    
    // 添加位置动画 - 从中间移到顶部
    val verticalPosition by animateDpAsState(
        targetValue = if (isRunning) 80.dp else 0.dp,
        animationSpec = tween(1000, delayMillis = 100, easing = EaseInOutSine),
        label = "position"
    )
    
    val borderColor = if (isRunning) Color.Green else Color.Red
    val statusText = if (isRunning) "运行中" else "已停止"
    
    Box(
        modifier = Modifier
            .fillMaxSize(),
        contentAlignment = if (isRunning) Alignment.TopCenter else Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .offset { 
                    androidx.compose.ui.unit.IntOffset(
                        0, 
                        if (isRunning) verticalPosition.roundToPx() else 0
                    )
                }
                .size(200.dp),
            contentAlignment = Alignment.Center
        ) {
            // 背景发光效果（仅在圆周围，不覆盖内部）
            if (isRunning) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawCircle(
                        color = Color.Green.copy(alpha = 0.1f),
                        radius = size.minDimension / 2 + glowRadius,
                        center = center,
                        blendMode = BlendMode.SrcOver
                    )
                }
                
                // 彗星追尾效果
                Box(
                    modifier = Modifier
                        .size(240.dp)
                        .rotate(rotation)
                ) {
                    // 绘制彗星
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        // 彗星头部位置（在圆环外围）
                        val radius = size.minDimension / 2 - 20
                        val headX = center.x + radius
                        val headY = center.y
                        
                        // 彗星头部
                        drawCircle(
                            color = Color.Green,
                            radius = 8f,
                            center = androidx.compose.ui.geometry.Offset(headX, headY)
                        )
                        
                        // 彗星尾巴（多个渐变透明度的圆）
                        for (i in 1..12) {
                            val angle = -i * 5 * (PI / 180f).toFloat()
                            val tailX = center.x + radius * cos(angle)
                            val tailY = center.y + radius * sin(angle)
                            
                            drawCircle(
                                color = Color.Green.copy(alpha = (1f - i * 0.08f).coerceIn(0f, 1f)),
                                radius = (8f - i * 0.5f).coerceAtLeast(2f),
                                center = androidx.compose.ui.geometry.Offset(tailX, tailY)
                            )
                        }
                        
                        // 第二个彗星（位置相反）
                        val head2X = center.x - radius
                        val head2Y = center.y
                        
                        // 第二个彗星头部
                        drawCircle(
                            color = Color.Green,
                            radius = 8f,
                            center = androidx.compose.ui.geometry.Offset(head2X, head2Y)
                        )
                        
                        // 第二个彗星尾巴
                        for (i in 1..12) {
                            val angle = PI.toFloat() + i * 5 * (PI / 180f).toFloat()
                            val tailX = center.x + radius * cos(angle)
                            val tailY = center.y + radius * sin(angle)
                            
                            drawCircle(
                                color = Color.Green.copy(alpha = (1f - i * 0.08f).coerceIn(0f, 1f)),
                                radius = (8f - i * 0.5f).coerceAtLeast(2f),
                                center = androidx.compose.ui.geometry.Offset(tailX, tailY)
                            )
                        }
                    }
                }
            }
            
            // 主圆圈 - 只有边框，没有背景色
            Box(
                modifier = Modifier
                    .size(160.dp)
                    .background(Color.White, CircleShape)
                    .border(
                        width = 3.dp,
                        color = borderColor,
                        shape = CircleShape
                    )
                    .clickable {
                        if (!isRunning) {
                            // 启动VPN服务
                            prepareAndStartVpnService(context)
                            // 更新首选项
                            Preferences(context).isEnabled = true
                        } else {
                            // 停止VPN服务
                            stopVpnService(context)
                            // 更新首选项
                            Preferences(context).isEnabled = false
                        }
                        
                        // 状态变更会通过广播接收器更新，无需在这里设置
                        // isRunning = !isRunning
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = statusText,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = borderColor
                )
            }
        }
    }
}

@Composable
fun VpnSettings() {
    val context = LocalContext.current
    val prefs = remember { Preferences(context) }
    
    var autoReconnect by remember { mutableStateOf(prefs.autoReconnect) }
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "自动重连",
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = autoReconnect,
                onCheckedChange = {
                    autoReconnect = it
                    prefs.autoReconnect = it
                }
            )
        }
        
        Spacer(modifier = Modifier.height(4.dp))
        
        Text(
            text = "上次连接: ${
                if (prefs.lastConnectionTime > 0) 
                    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                        .format(java.util.Date(prefs.lastConnectionTime))
                else 
                    "从未连接"
            }",
            style = MaterialTheme.typography.bodySmall
        )
        
        Text(
            text = "连接次数: ${prefs.connectionCount}",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

// 准备并启动VPN服务
private fun prepareAndStartVpnService(context: Context) {
    // 检查是否需要VPN请求
    val vpnIntent = VpnService.prepare(context)
    if (vpnIntent != null) {
        // 需要用户同意VPN权限，启动系统VPN授权界面
        context.startActivity(vpnIntent)
    } else {
        // 已有权限，直接启动VPN服务
        startVpnService(context)
    }
}

// 启动VPN服务
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

// 停止VPN服务
private fun stopVpnService(context: Context) {
    Intent(context, TProxyService::class.java).apply {
        action = TProxyService.ACTION_DISCONNECT
        try {
            context.startService(this)
            Log.d(TAG, "VPN服务停止成功")
        } catch (e: Exception) {
            Log.e(TAG, "停止VPN服务失败", e)
        }
    }
} 