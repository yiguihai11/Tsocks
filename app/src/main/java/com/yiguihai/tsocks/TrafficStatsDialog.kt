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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.lifecycleScope
import com.yiguihai.tsocks.ui.theme.TSocksTheme
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface

class TrafficStatsActivity : ComponentActivity() {
    private var currentUploadSpeed by mutableStateOf("0 KB/s")
    private var currentDownloadSpeed by mutableStateOf("0 KB/s")
    private var currentUploadPackets by mutableIntStateOf(0)
    private var currentDownloadPackets by mutableIntStateOf(0)
    private var currentTotalUploadBytes by mutableStateOf("0 B")
    private var currentTotalDownloadBytes by mutableStateOf("0 B")
    private var job: kotlinx.coroutines.Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                        totalUploadBytes = currentTotalUploadBytes,
                        totalDownloadBytes = currentTotalDownloadBytes,
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
        observeTrafficStats()
    }

    private fun observeTrafficStats() {
        job = lifecycleScope.launch {
            TProxyService.trafficStats
                .catch { e -> 
                    if (e !is kotlinx.coroutines.CancellationException) {
                        Log.e("TrafficStats", "流量统计接收失败", e)
                    }
                }
                .collect { stats ->
                    if (isFinishing) return@collect
                    currentUploadSpeed = stats.uploadSpeed
                    currentDownloadSpeed = stats.downloadSpeed
                    currentUploadPackets = stats.uploadPackets
                    currentDownloadPackets = stats.downloadPackets
                    currentTotalUploadBytes = stats.totalUploadBytes
                    currentTotalDownloadBytes = stats.totalDownloadBytes
                }
        }
    }

    override fun onDestroy() {
        job?.cancel()
        super.onDestroy()
    }
}

@Composable
fun TrafficStatsDialogContent(
    uploadSpeed: String,
    downloadSpeed: String,
    uploadPackets: Int,
    downloadPackets: Int,
    totalUploadBytes: String,
    totalDownloadBytes: String,
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
                    Text(
                        text = stringResource(R.string.network_type, networkInfo.first),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = when(networkInfo.first) {
                            "WIFI" -> Color(0xFF4CAF50)
                            stringResource(R.string.mobile_data) -> Color(0xFF2196F3)
                            "VPN" -> Color(0xFFFF9800)
                            else -> Color.Gray
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp)
                    )
                    
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    
                    Text(
                        text = stringResource(R.string.tun_traffic_stats),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    
                    listOf(
                        Pair(stringResource(R.string.upload_speed), "(${stringResource(R.string.total)}: $totalUploadBytes) $uploadSpeed"),
                        Pair(stringResource(R.string.download_speed), "(${stringResource(R.string.total)}: $totalDownloadBytes) $downloadSpeed"),
                        Pair(stringResource(R.string.upload_packets), "$uploadPackets pkt/s"),
                        Pair(stringResource(R.string.download_packets), "$downloadPackets pkt/s")
                    ).forEach { (label, value) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = label)
                            Text(text = value, fontWeight = FontWeight.Bold)
                        }
                    }
                    
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    
                    Text(
                        text = stringResource(R.string.local_ip_address),
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
                        Text(stringResource(R.string.enter_app))
                    }
                }
            }
        }
    }
}

private fun getNetworkInfo(context: Context): Pair<String, String> {
    val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    val network = connectivityManager.activeNetwork ?: return Pair(context.getString(R.string.not_connected), "")
    val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return Pair(context.getString(R.string.not_connected), "")
    
    val interfaceName = connectivityManager.getLinkProperties(network)?.interfaceName ?: ""
    
    val networkType = when {
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> context.getString(R.string.wifi)
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 
            if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED)) 
                context.getString(R.string.mobile_data) else context.getString(R.string.mobile_data_slow)
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> context.getString(R.string.ethernet)
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> context.getString(R.string.bluetooth)
        else -> context.getString(R.string.other_network)
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
                is Inet4Address -> Pair(address.hostAddress ?: acc.first, acc.second)
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