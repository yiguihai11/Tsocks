package com.yiguihai.tsocks

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.lifecycleScope
import com.yiguihai.tsocks.ui.theme.TSocksTheme
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface

class TrafficStatsActivity : ComponentActivity() {
    private var currentUploadSpeed by mutableStateOf("0 KB/s")
    private var currentDownloadSpeed by mutableStateOf("0 KB/s")
    private var currentUploadPackets by mutableIntStateOf(0)
    private var currentDownloadPackets by mutableIntStateOf(0)
    private var ssUploadSpeed by mutableStateOf("0 KB/s")
    private var ssDownloadSpeed by mutableStateOf("0 KB/s")
    private var job: kotlinx.coroutines.Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updateUI()
        observeTrafficStats()
    }

    private fun observeTrafficStats() {
        job = lifecycleScope.launch {
            try {
                TProxyService.trafficStats.collect { stats ->
                    if (isFinishing) return@collect
                    currentUploadSpeed = stats.uploadSpeed
                    currentDownloadSpeed = stats.downloadSpeed
                    currentUploadPackets = stats.uploadPackets
                    currentDownloadPackets = stats.downloadPackets
                    ssUploadSpeed = stats.ssUploadSpeed
                    ssDownloadSpeed = stats.ssDownloadSpeed
                    updateUI()
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) return@launch
                Log.e("TrafficStatsActivity", "接收流量统计失败", e)
            }
        }
    }

    override fun onDestroy() {
        job?.cancel()
        super.onDestroy()
    }

    private fun updateUI() {
        setContent {
            TSocksTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    TrafficStatsDialogContent(
                        uploadSpeed = currentUploadSpeed,
                        downloadSpeed = currentDownloadSpeed,
                        uploadPackets = currentUploadPackets,
                        downloadPackets = currentDownloadPackets,
                        ssUploadSpeed = ssUploadSpeed,
                        ssDownloadSpeed = ssDownloadSpeed,
                        onDismiss = { finish() },
                        onEnterApp = {
                            startActivity(Intent(this, MainActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                            })
                            finish()
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun TrafficStatsDialogContent(
    uploadSpeed: String,
    downloadSpeed: String,
    uploadPackets: Int,
    downloadPackets: Int,
    ssUploadSpeed: String,
    ssDownloadSpeed: String,
    onDismiss: () -> Unit,
    onEnterApp: () -> Unit
) {
    val context = LocalContext.current
    val networkInfo = remember { getNetworkInfo(context) }
    val localAddresses = remember { getLocalIPAddresses() }
    
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Dialog(onDismissRequest = onDismiss) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "网络类型: ${networkInfo.first}",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = when(networkInfo.first) {
                                "WIFI" -> Color(0xFF4CAF50)
                                "移动数据" -> Color(0xFF2196F3)
                                "VPN" -> Color(0xFFFF9800)
                                else -> Color.Gray
                            }
                        )
                    }
                    
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    
                    Text(
                        text = "TUN流量统计",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    
                    listOf(
                        "上传速度:" to uploadSpeed,
                        "下载速度:" to downloadSpeed,
                        "上传数据包:" to "$uploadPackets pkt/s",
                        "下载数据包:" to "$downloadPackets pkt/s"
                    ).forEach { (label, value) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = label)
                            Text(text = value, fontWeight = FontWeight.Bold)
                        }
                    }
                    
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    
                    Text(
                        text = "Shadowsocks流量",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                    
                    listOf(
                        "上传速度:" to ssUploadSpeed,
                        "下载速度:" to ssDownloadSpeed
                    ).forEach { (label, value) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = label)
                            Text(text = value, fontWeight = FontWeight.Bold)
                        }
                    }
                    
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    
                    Text(
                        text = "本地IP地址",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                    
                    localAddresses.first.takeIf { it.isNotEmpty() }?.let {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = "IPv4:")
                            Text(
                                text = it,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
                    }
                    
                    localAddresses.second.takeIf { it.isNotEmpty() }?.let {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = "IPv6:")
                            Text(
                                text = if (it.length > 20) "${it.take(20)}..." else it,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Button(
                        onClick = onEnterApp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("进入软件")
                    }
                }
            }
        }
    }
}

private fun getNetworkInfo(context: Context): Pair<String, String> {
    val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    val network = connectivityManager.activeNetwork ?: return Pair("未连接", "")
    val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return Pair("未连接", "")
    
    val interfaceName = connectivityManager.getLinkProperties(network)?.interfaceName ?: ""
    
    val networkType = when {
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 
            if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED)) 
                "移动数据" else "移动数据(低速)"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "有线网络"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "蓝牙网络"
        else -> "其他"
    }
    
    return Pair(networkType, interfaceName)
}

private fun getLocalIPAddresses(): Pair<String, String> = try {
    NetworkInterface.getNetworkInterfaces().asSequence()
        .filter { !it.isLoopback && it.isUp }
        .flatMap { it.inetAddresses.asSequence() }
        .filter { !it.isLoopbackAddress }
        .fold(Pair("", "")) { acc, address ->
            when (address) {
                is Inet4Address -> 
                    Pair(address.hostAddress ?: acc.first, acc.second)
                is Inet6Address -> 
                    if (!address.isLinkLocalAddress) 
                        Pair(acc.first, address.hostAddress?.split("%")?.firstOrNull() ?: acc.second)
                    else acc
                else -> acc
            }
        }
} catch (e: Exception) {
    Log.e("NetworkInfo", "获取IP地址失败", e)
    Pair("", "")
}