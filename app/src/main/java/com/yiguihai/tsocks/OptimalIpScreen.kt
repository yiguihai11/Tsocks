package com.yiguihai.tsocks

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

data class SpeedTestResult(
    val ip: String,
    val port: Int,
    val speed: Double,
    val status: String
)

class OptimalIpViewModel : ViewModel() {
    private val _testResults = MutableStateFlow<List<SpeedTestResult>>(emptyList())
    val testResults: StateFlow<List<SpeedTestResult>> = _testResults.asStateFlow()

    private val _isTesting = MutableStateFlow(false)
    val isTesting: StateFlow<Boolean> = _isTesting.asStateFlow()

    fun startSpeedTest(ips: List<String>, port: Int, enableTLS: Boolean) {
        viewModelScope.launch {
            _isTesting.value = true
            _testResults.value = emptyList()

            val results = mutableListOf<SpeedTestResult>()
            val speedTestUrl = "speed.cloudflare.com/__down?bytes=500000000"
            val timeoutMs = 1000

            ips.forEach { ip ->
                val speed = withContext(Dispatchers.IO) {
                    getDownloadSpeed(ip, port, enableTLS, speedTestUrl, timeoutMs)
                }
                results.add(
                    SpeedTestResult(
                        ip = ip,
                        port = port,
                        speed = speed,
                        status = if (speed > 0) "成功" else "失败"
                    )
                )
                _testResults.value = results.toList()
            }

            _isTesting.value = false
        }
    }
}

@Composable
fun OptimalIpScreen(
    viewModel: OptimalIpViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val testResults by viewModel.testResults.collectAsState()
    val isTesting by viewModel.isTesting.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "IP 测速",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        Button(
            onClick = {
                // 这里替换为实际的IP列表
                val testIps = listOf(
                    "1.2.3.4",
                    "5.6.7.8",
                    "9.10.11.12"
                )
                viewModel.startSpeedTest(testIps, 443, true)
            },
            enabled = !isTesting,
            modifier = Modifier.padding(bottom = 16.dp)
        ) {
            Text(if (isTesting) "测速中..." else "开始测速")
        }

        if (isTesting) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            )
        }

        LazyColumn {
            items(testResults) { result ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth()
                    ) {
                        Text(
                            text = "IP: ${result.ip}:${result.port}",
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "速度: ${"%.2f".format(result.speed)} kB/s",
                            color = if (result.speed > 0) MaterialTheme.colorScheme.primary 
                                   else MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = "状态: ${result.status}",
                            color = if (result.status == "成功") MaterialTheme.colorScheme.primary 
                                   else MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

fun getDownloadSpeed(
    ip: String,
    port: Int,
    enableTLS: Boolean,
    speedTestUrl: String,
    timeoutMs: Int
): Double {
    val protocol = if (enableTLS) "https://" else "http://"
    val fullUrl = protocol + speedTestUrl

    val url = URL(fullUrl)
    val host = url.host
    val requestPath = if (url.file.isNullOrEmpty()) "/" else url.file

    val httpRequest = "GET $requestPath HTTP/1.1\r\n" +
            "Host: $host\r\n" +
            "User-Agent: Mozilla/5.0\r\n" +
            "Connection: close\r\n" +
            "\r\n"

    val socket = Socket()
    try {
        socket.connect(InetSocketAddress(ip, port), timeoutMs)
    } catch (e: Exception) {
        return 0.0
    }

    val startTime = System.currentTimeMillis()

    return try {
        val out: OutputStream = socket.getOutputStream()
        val input: InputStream = socket.getInputStream()
        out.write(httpRequest.toByteArray())
        out.flush()

        var totalBytes: Long = 0
        val buffer = ByteArray(8192)
        while (true) {
            val bytesRead = input.read(buffer)
            if (bytesRead <= 0) break
            totalBytes += bytesRead
        }

        val durationMs = System.currentTimeMillis() - startTime
        val durationSeconds = durationMs / 1000.0
        val speed = if (durationSeconds > 0) totalBytes / durationSeconds / 1024.0 else 0.0

        speed
    } catch (e: Exception) {
        0.0
    } finally {
        try {
            socket.close()
        } catch (ex: Exception) {
            // 忽略关闭异常
        }
    }
} 