package com.yiguihai.tsocks

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yiguihai.tsocks.utils.PermissionCheck
import com.yiguihai.tsocks.utils.Preferences
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private const val TAG = "HomeScreen"

@Composable
fun HomeScreen(modifier: Modifier = Modifier) {

    // 从应用上下文中获取权限管理器实例，而不是在这里创建
    val permissionManager = LocalPermissionManager.current

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier.weight(1f)
            ) {
                StatusCircle()
            }

            // 显示权限检查UI
            PermissionCheck(permissionManager)
        }
    }
}

@Composable
fun StatusCircle() {
    val context = LocalContext.current
    val prefs = remember { Preferences(context) }

    // 检查VPN是否真正在运行
    fun checkVpnRunning(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false

        // 同时检查VPN服务是否启用及网络是否真正建立
        return prefs.isEnabled && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    // 使用preferences.isEnabled替代广播判断VPN状态
    var isRunning by remember { mutableStateOf(prefs.isEnabled) }

    // 初始化和定期更新isRunning状态
    LaunchedEffect(Unit) {
        Log.d(TAG, "初始化VPN状态: isEnabled=${prefs.isEnabled}")
        isRunning = prefs.isEnabled
            while (true) {
                kotlinx.coroutines.delay(2000)
                val currentStatus = prefs.isEnabled
                if (isRunning != currentStatus) {
                    Log.d(TAG, "检测到VPN状态变更: $isRunning -> $currentStatus")
                    isRunning = currentStatus
                }
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

    // 添加心跳动画
    val heartbeatScale by animateFloatAsState(
        targetValue = if (isRunning) 1.2f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "heartbeat"
    )

    // 添加呼吸灯效果
    val breathingAlpha by animateFloatAsState(
        targetValue = if (!isRunning) 1f else 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathing"
    )

    // 添加位置动画 - 从中间移到顶部
    val verticalPosition by animateDpAsState(
        targetValue = if (isRunning) 80.dp else 0.dp,
        animationSpec = tween(1000, delayMillis = 100, easing = EaseInOutSine),
        label = "position"
    )

    // 运行中为绿色，停止为紫色
    val borderColor = if (isRunning) Color.Green else Color(0xFFEE82EE)

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

            // 主圆圈
            Box(
                modifier = Modifier
                    .size(160.dp)
                    .background(Color.White, CircleShape)
                    .border(
                        width = 3.dp,
                        color = if (!isRunning) borderColor.copy(alpha = breathingAlpha) else borderColor,
                        shape = CircleShape
                    )
                    .clickable {
                        Log.d(TAG, "点击圆圈，当前 isRunning 状态: $isRunning, isEnabled: ${prefs.isEnabled}")

                        // 检查状态不一致情况
                        val isReallyRunning = checkVpnRunning()
                        if (prefs.isEnabled && !isReallyRunning) {
                            Log.d(TAG, "检测到状态不一致: isEnabled=true 但VPN未实际运行，发送停止命令")
                            val intent = Intent(context, TProxyService::class.java)
                            intent.action = TProxyService.ACTION_DISCONNECT
                            context.startService(intent)
                            return@clickable
                        }

                        if (isRunning) {
                            // 停止VPN
                            val intent = Intent(context, TProxyService::class.java)
                            intent.action = TProxyService.ACTION_DISCONNECT
                            Log.d(TAG, "发送停止VPN请求: ACTION_DISCONNECT")

                            // 只更新UI状态，服务负责更新preferences状态
                            isRunning = false

                            // 发送停止命令
                            context.startService(intent)
                        } else {
                            // 启动VPN
                            val vpnIntent = VpnService.prepare(context)
                            if (vpnIntent != null) {
                                try {
                                    context.startActivity(vpnIntent)
                                } catch (e: Exception) {
                                    Log.e(TAG, "VPN权限请求失败", e)
                                }
                            } else {
                                // 已有权限，直接启动
                                val intent = Intent(context, TProxyService::class.java)
                                intent.action = TProxyService.ACTION_CONNECT

                                // 设置isEnabled=true会在服务中完成
                                context.startService(intent)
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                if (!isRunning) {
                    // 已停止 - 显示紫色文字
                    Text(
                        text = "已停止",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFEE82EE).copy(alpha = breathingAlpha)
                    )
                } else {
                    // 运行中 - 显示简单的心跳线
                    Canvas(
                        modifier = Modifier
                            .size(140.dp, 40.dp)
                            .scale(heartbeatScale)
                    ) {
                        val width = size.width
                        val height = size.height
                        val midY = height / 2

                        // 绘制基线
                        drawLine(
                            color = Color.Green,
                            start = androidx.compose.ui.geometry.Offset(0f, midY),
                            end = androidx.compose.ui.geometry.Offset(width, midY),
                            strokeWidth = 3f
                        )

                        // 起始点
                        var lastX = 0f
                        var lastY = midY

                        // 简化的心跳波形 - 只有一个明显的波峰
                        val points = listOf(
                            Pair(0f, midY), // 开始点
                            Pair(0.35f * width, midY), // 平稳期
                            Pair(0.4f * width, midY - height * 0.4f), // 上升
                            Pair(0.45f * width, midY), // 回到基线
                            Pair(0.5f * width, midY + height * 0.4f), // 下降
                            Pair(0.55f * width, midY), // 回到基线
                            Pair(width, midY) // 结束点
                        )

                        // 绘制波形线
                        for (point in points) {
                            drawLine(
                                color = Color.Green,
                                start = androidx.compose.ui.geometry.Offset(lastX, lastY),
                                end = androidx.compose.ui.geometry.Offset(point.first, point.second),
                                strokeWidth = 4f
                            )
                            lastX = point.first
                            lastY = point.second
                        }
                    }
                }
            }
        }
    }
}