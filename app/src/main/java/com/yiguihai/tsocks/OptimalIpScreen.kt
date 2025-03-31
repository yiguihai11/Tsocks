package com.yiguihai.tsocks

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yiguihai.tsocks.utils.OptimalIpManager
import com.yiguihai.tsocks.utils.SpeedTestConfig
import com.yiguihai.tsocks.utils.SpeedTestResult
import com.yiguihai.tsocks.utils.SpeedTestUtils
import com.yiguihai.tsocks.utils.NetworkUtils
import com.yiguihai.tsocks.utils.Preferences
import com.yiguihai.tsocks.utils.ShadowsocksImporter
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 优选IP测速ViewModel
 */
class OptimalIpViewModel : ViewModel() {
    private val _speedTestConfig = MutableStateFlow(SpeedTestConfig())
    val speedTestConfig: StateFlow<SpeedTestConfig> = _speedTestConfig.asStateFlow()
    
    private val _ipInputText = MutableStateFlow("")
    val ipInputText: StateFlow<String> = _ipInputText.asStateFlow()
    
    // 当前测试作业
    private var testJob: Job? = null
    // 上下文引用，用于保存设置
    private var appContext: Context? = null

    /**
     * 初始化，加载保存的配置
     * 应该在ViewModel被创建后调用
     */
    fun initialize(context: Context) {
        appContext = context.applicationContext
        
        // 使用协程异步加载配置
        viewModelScope.launch {
            // 加载测速配置
            val savedConfig = Preferences.loadSpeedTestConfig(context)
            _speedTestConfig.value = savedConfig
            
            // 加载保存的IP列表
            val savedIpList = Preferences.loadImportedIpList(context)
            if (savedIpList.isNotEmpty()) {
                OptimalIpManager.importedIpList.clear()
                OptimalIpManager.importedIpList.addAll(savedIpList)
                _ipInputText.value = savedIpList.joinToString("\n")
            } else if (OptimalIpManager.importedIpList.isEmpty()) {
                // 如果没有保存的IP且当前列表为空，使用默认IP列表
                OptimalIpManager.importedIpList.addAll(DEFAULT_IP_LIST)
                _ipInputText.value = OptimalIpManager.importedIpList.joinToString("\n")
            }
            
            // 加载上次的网络信息
            val savedNetworkInfo = Preferences.loadNetworkInfo(context)
            if (savedNetworkInfo.externalIp.isNotEmpty()) {
                OptimalIpManager.networkInfo.value = savedNetworkInfo
            }
        }
    }
    
    /**
     * 更新测试配置
     */
    fun updateTestConfig(config: SpeedTestConfig) {
        _speedTestConfig.value = config
        
        // 异步保存配置
        appContext?.let { context ->
            viewModelScope.launch {
                Preferences.saveSpeedTestConfig(context, config)
            }
        }
    }
    
    /**
     * 更新IP输入文本
     */
    fun updateIpInputText(text: String) {
        _ipInputText.value = text
        val ips = text.split("\n").filter { it.isNotBlank() }
        OptimalIpManager.importedIpList.clear()
        OptimalIpManager.importedIpList.addAll(ips)
        
        // 异步保存IP列表
        appContext?.let { context ->
            viewModelScope.launch {
                Preferences.saveImportedIpList(context, ips)
            }
        }
    }
    
    /**
     * 从网络下载IP列表
     */
    fun downloadIpList(context: android.content.Context) {
        if (testJob?.isActive == true) return
        
        testJob = viewModelScope.launch {
            try {
                val ips = SpeedTestUtils.downloadIpList(context)
                if (ips.isNotEmpty()) {
                    OptimalIpManager.importedIpList.clear()
                    OptimalIpManager.importedIpList.addAll(ips)
                    _ipInputText.value = ips.joinToString("\n")
                    
                    // 保存下载的IP列表
                    Preferences.saveImportedIpList(context, ips)
                }
            } catch (e: Exception) {
                Log.e("OptimalIpViewModel", "下载IP列表失败", e)
                OptimalIpManager.statusMessage.value = "下载IP列表失败: ${e.message}"
            }
        }
    }
    
    /**
     * 清空IP列表
     */
    fun clearIpList() {
        OptimalIpManager.importedIpList.clear()
        _ipInputText.value = ""
        
        // 保存空IP列表
        appContext?.let { context ->
            viewModelScope.launch {
                Preferences.saveImportedIpList(context, emptyList())
            }
        }
    }
    
    /**
     * 开始测速
     */
    fun startSpeedTest(context: android.content.Context) {
        if (testJob?.isActive == true) {
            // 如果已经在测速，则停止测速
            OptimalIpManager.stopTesting()
            testJob?.cancel()
            testJob = null
            return
        }
        
        val ips = OptimalIpManager.importedIpList.toList()
        if (ips.isEmpty()) {
            OptimalIpManager.statusMessage.value = "请先添加IP地址"
            return
        }
        
        // 设置测试状态为进行中 - 放在前面，这样配置区域会立即隐藏
        OptimalIpManager.isTestingInProgress.value = true
        OptimalIpManager.clearCurrentResults()
        OptimalIpManager.totalIps.value = ips.size
        
        testJob = viewModelScope.launch {
            // 获取网络信息
            try {
                val carrier = NetworkUtils.getCarrierName(context)
                val networkType = NetworkUtils.getNetworkType(context)
                val carrierInfo = "$carrier - $networkType"
                val externalIp = NetworkUtils.getExternalIpAddress()
                
                OptimalIpManager.updateNetworkInfo(carrierInfo, externalIp)
                
                // 保存网络信息
                Preferences.saveNetworkInfo(context, OptimalIpManager.networkInfo.value)
                
                Log.d("OptimalIpViewModel", "获取到网络信息: 运营商=$carrierInfo, 外网IP=$externalIp")
            } catch (e: Exception) {
                Log.e("OptimalIpViewModel", "获取网络信息失败", e)
            }
            
            // 初始化位置数据
            OptimalIpManager.loadLocations(context)
            
            // 先进行延迟测试
            val config = _speedTestConfig.value
            val latencyResults = mutableListOf<SpeedTestResult>()
            
            try {
                SpeedTestUtils.startLatencyTest(
                    config = config,
                    ips = ips,
                    onProgressUpdate = { completed, total ->
                        OptimalIpManager.currentProgress.value = completed
                    },
                    onResultUpdate = { result ->
                        // 将结果添加到列表中
                        latencyResults.add(result)
                        OptimalIpManager.testResults.add(result)
                    }
                )
                
                // 过滤出延迟测试成功的结果，按延迟排序，最多测试前20个IP的下载速度
                val sortedResults = latencyResults
                    .filter { it.isSuccessful }
                    .sortedBy { it.latencyMs }
                    .take(20)
                
                // 进行下载速度测试
                if (sortedResults.isNotEmpty() && OptimalIpManager.isTestingInProgress.value) {
                    OptimalIpManager.currentProgress.value = 0
                    OptimalIpManager.totalIps.value = sortedResults.size
                    
                    SpeedTestUtils.startSpeedTest(
                        config = config,
                        results = sortedResults,
                        onProgressUpdate = { completed, total ->
                            OptimalIpManager.currentProgress.value = completed
                        },
                        onResultUpdate = { result ->
                            // 更新结果列表中的元素
                            val index = OptimalIpManager.testResults.indexOfFirst { it.ip == result.ip }
                            if (index >= 0) {
                                OptimalIpManager.testResults[index] = result
                            }
                        }
                    )
                }
                
                // 保存测试结果到历史记录
                OptimalIpManager.saveCurrentResultsToHistory()
            } catch (e: Exception) {
                Log.e("OptimalIpViewModel", "测速失败", e)
                OptimalIpManager.statusMessage.value = "测速失败: ${e.message}"
            } finally {
                OptimalIpManager.isTestingInProgress.value = false
            }
        }
    }
    
    companion object {
        private val DEFAULT_IP_LIST = listOf(
            "1.1.1.1",
            "1.0.0.1",
            "104.16.0.1",
            "104.16.1.1"
        )
    }
}

@Composable
fun OptimalIpScreen(
    viewModel: OptimalIpViewModel = viewModel()
) {
    val context = LocalContext.current
    
    var selectedTabIndex by remember { mutableStateOf(0) }
    val tabs = listOf("测速", "测速结果", "待测速IP列表")
    
    // 初始化ViewModel，加载保存的配置
    LaunchedEffect(Unit) {
        viewModel.initialize(context)
        // 加载Cloudflare位置数据
        OptimalIpManager.loadLocations(context)
    }
    
    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTabIndex) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTabIndex == index,
                    onClick = { selectedTabIndex = index },
                    text = { Text(title) }
                )
            }
        }
        
        when (selectedTabIndex) {
            0 -> SpeedTestTab(viewModel)
            1 -> SpeedTestResultsTab()
            2 -> IpListTab(viewModel)
        }
    }
}

@Composable
fun SpeedTestTab(viewModel: OptimalIpViewModel) {
    val context = LocalContext.current
    val speedTestConfig by viewModel.speedTestConfig.collectAsState()
    
    val isTestingInProgress = remember { OptimalIpManager.isTestingInProgress }
    val isTesting by isTestingInProgress
    val currentProgressState = remember { OptimalIpManager.currentProgress }
    val currentProgress by currentProgressState
    val totalIpsState = remember { OptimalIpManager.totalIps }
    val totalIps by totalIpsState
    val statusMessageState = remember { OptimalIpManager.statusMessage }
    val statusMessage by statusMessageState
    val networkInfoState = remember { OptimalIpManager.networkInfo }
    val networkInfo by networkInfoState
    
    // 用于自动滚动到底部
    val listState = rememberLazyListState()
    val testResults = remember { OptimalIpManager.testResults }
    
    // 自动滚动到底部
    LaunchedEffect(testResults.size) {
        if (testResults.isNotEmpty()) {
            listState.animateScrollToItem(testResults.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // 配置部分 - 仅在非测试状态下显示
        if (!isTesting) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth()
    ) {
        Text(
                        text = "测速配置",
            fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Text("测速模式: ", modifier = Modifier.width(80.dp))
                        SpeedTestModeSelector(
                            selectedMode = speedTestConfig.testMode,
                            onModeSelected = { mode ->
                                viewModel.updateTestConfig(
                                    speedTestConfig.copy(
                                        testMode = mode,
                                        maxTimeout = if (mode == "ping") 1000 else 2000
                                    )
                                )
                            }
                        )
                    }
                    
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Text("超时(毫秒): ", modifier = Modifier.width(80.dp))
                        TextField(
                            value = speedTestConfig.maxTimeout.toString(),
                            onValueChange = { value ->
                                val timeout = value.toIntOrNull() ?: 2000
                                viewModel.updateTestConfig(speedTestConfig.copy(maxTimeout = timeout))
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                    }
                    
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Text("测速线程数: ", modifier = Modifier.width(80.dp))
                        TextField(
                            value = speedTestConfig.speedTestThreads.toString(),
                            onValueChange = { value ->
                                val threads = value.toIntOrNull() ?: 5
                                viewModel.updateTestConfig(speedTestConfig.copy(speedTestThreads = threads))
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                    }
                    
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Text("测速URL: ", modifier = Modifier.width(80.dp))
                        TextField(
                            value = speedTestConfig.speedTestUrl,
                            onValueChange = { value ->
                                viewModel.updateTestConfig(speedTestConfig.copy(speedTestUrl = value))
                            },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                    }
                }
            }
        } else if (networkInfo.externalIp.isNotEmpty()) {
            // 测试进行中且有网络信息，显示网络信息卡片
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "网络信息",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        
                        Text(
                            text = formatTimestamp(networkInfo.timestamp),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "外网IP: ${networkInfo.externalIp}",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        
                        Text(
                            text = "运营商: ${networkInfo.carrier}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
        
        // 测试控制按钮
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
        ) {
            Button(
                onClick = { viewModel.startSpeedTest(context) },
                colors = if (isTesting) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                } else {
                    ButtonDefaults.buttonColors()
                }
            ) {
                Text(if (isTesting) "停止测速" else "开始测速")
            }
            
            if (isTesting) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("$currentProgress/$totalIps")
                    Spacer(modifier = Modifier.width(8.dp))
                    LinearProgressIndicator(
                        progress = if (totalIps > 0) currentProgress.toFloat() / totalIps.toFloat() else 0f,
                        modifier = Modifier.width(100.dp)
                    )
                }
            }
        }
        
        if (statusMessage.isNotEmpty()) {
            Text(
                text = statusMessage,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
        
        // 测试结果列表
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f)
        ) {
            items(testResults) { result ->
                SpeedTestResultItem(result)
            }
        }
    }
}

@Composable
fun SpeedTestModeSelector(
    selectedMode: String,
    onModeSelected: (String) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(
                selected = selectedMode == "tcping",
                onClick = { onModeSelected("tcping") }
            )
            Text("TCP Ping")
        }
        Spacer(modifier = Modifier.width(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(
                selected = selectedMode == "ping",
                onClick = { onModeSelected("ping") }
            )
            Text("ICMP Ping")
        }
    }
}

@Composable
fun SpeedTestResultItem(result: SpeedTestResult) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    
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
            // IP地址和位置信息
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = result.ip,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                
                // 复制IP按钮
                IconButton(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(result.ip))
                        Toast.makeText(context, "已复制IP: ${result.ip}", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "复制IP",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                
                if (result.dataCenter.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "数据中心: ${result.dataCenter}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            
            // 位置信息
            if (result.city.isNotEmpty() || result.region.isNotEmpty()) {
                Text(
                    text = buildString {
                        if (result.city.isNotEmpty()) append(result.city)
                        if (result.region.isNotEmpty()) {
                            if (isNotEmpty()) append(", ")
                            append(result.region)
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            
            Spacer(modifier = Modifier.height(4.dp))
            
            // 延迟和下载速度
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "延迟: ${result.latency}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                
                Text(
                    text = "速度: ${formatSpeed(result.downloadSpeed)}",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            
            // 测试时间
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Text(
                    text = formatTimestamp(result.timestamp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
fun SpeedTestResultsTab() {
    val context = LocalContext.current
    val savedTestResults = remember { OptimalIpManager.savedTestResults }
    val networkInfoState = remember { OptimalIpManager.networkInfo }
    val networkInfo by networkInfoState
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    
    val sortedResults = remember(savedTestResults) {
        // 按照下载速度和延迟排序，过滤掉下载速度为0的结果
        savedTestResults
            .filter { it.downloadSpeed > 0 }
            .sortedWith(
                compareByDescending<SpeedTestResult> { it.downloadSpeed }
                    .thenBy { it.latencyMs }
            )
    }
    
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (sortedResults.isNotEmpty()) {
                FloatingActionButton(
                    onClick = {
                        coroutineScope.launch {
                            try {
                                val importedCount = ShadowsocksImporter.importOptimalIpsToConfigFile(context, sortedResults)
                                if (importedCount > 0) {
                                    snackbarHostState.showSnackbar("成功导入 $importedCount 个IP地址到Shadowsocks配置")
                                } else {
                                    snackbarHostState.showSnackbar("未找到符合条件的服务器配置")
                                }
                            } catch (e: Exception) {
                                Log.e("SpeedTestResultsTab", "导入IP失败", e)
                                snackbarHostState.showSnackbar("导入失败: ${e.message}")
                            }
                        }
                    }
                ) {
                    Icon(Icons.Default.SaveAlt, contentDescription = "导入到Shadowsocks")
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .padding(padding)
        ) {
            // 网络信息卡片
            if (networkInfo.externalIp.isNotEmpty()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth()
                    ) {
                        Text(
                            text = "网络信息",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        
                        // 外网IP
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Text(
                                text = "外网IP: ${networkInfo.externalIp}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        
                        // 运营商
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Text(
                                text = "运营商: ${networkInfo.carrier}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        
                        // 测试时间
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Text(
                                text = "测试时间: ${formatTimestamp(networkInfo.timestamp)}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
            
            // 标题和统计信息
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Text(
                    text = "测速结果 (${sortedResults.size})",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "按下载速度排序，已过滤0速度结果",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            
            if (sortedResults.isEmpty()) {
                // 如果没有测试结果，显示提示信息
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f)
                ) {
                    Text("暂无测速数据，请先进行测速")
                }
            } else {
                // 显示测试结果列表
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    items(sortedResults) { result ->
                        SpeedTestResultItem(result)
                    }
                }
            }
        }
    }
}

/**
 * 格式化时间戳为可读时间
 */
private fun formatTimestamp(timestamp: Long): String {
    val date = java.util.Date(timestamp)
    val formatter = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
    return formatter.format(date)
}

/**
 * 格式化下载速度显示
 */
private fun formatSpeed(speedKbps: Double): String {
    return when {
        speedKbps >= 1024 -> String.format("%.2f MB/s", speedKbps / 1024)
        else -> String.format("%.2f KB/s", speedKbps)
    }
}

@Composable
fun IpListTab(viewModel: OptimalIpViewModel) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val ipInputText by viewModel.ipInputText.collectAsState()
    
    val isTestingInProgress = remember { OptimalIpManager.isTestingInProgress }
    val isTesting by isTestingInProgress
    
    // 滚动状态
    val scrollState = rememberScrollState()
    // 使用Compose的协程作用域
    val coroutineScope = rememberCoroutineScope()
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "待测速IP列表",
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        
        Box(modifier = Modifier.weight(1f)) {
            TextField(
                value = ipInputText,
                onValueChange = { viewModel.updateIpInputText(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .verticalScroll(scrollState),
                placeholder = { Text("每行输入一个IP地址") },
                enabled = !isTesting
            )
            
            // 顶部/底部导航按钮
            if (scrollState.maxValue > 0) {
                Column(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 8.dp)
                ) {
                    if (scrollState.value > 0) {
                        IconButton(
                            onClick = { 
                                coroutineScope.launch {
                                    scrollState.animateScrollTo(0)
                                }
                            },
                            modifier = Modifier
                                .size(40.dp)
                                .background(
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    shape = androidx.compose.foundation.shape.CircleShape
                                )
                        ) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowUp,
                                contentDescription = "滚动到顶部"
                            )
                        }
                    }
                    
                    // 增加按钮之间的间隔
                    Spacer(modifier = Modifier.height(20.dp))
                    
                    if (scrollState.value < scrollState.maxValue) {
                        IconButton(
                            onClick = { 
                                coroutineScope.launch {
                                    scrollState.animateScrollTo(scrollState.maxValue)
                                }
                            },
                            modifier = Modifier
                                .size(40.dp)
                                .background(
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    shape = androidx.compose.foundation.shape.CircleShape
                                )
                        ) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = "滚动到底部"
                            )
                        }
                    }
                }
            }
        }
        
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
        ) {
            Text(
                text = "共 ${ipInputText.split("\n").filter { it.isNotBlank() }.size} 个IP地址",
                style = MaterialTheme.typography.bodySmall
            )
            
            Row(
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    IconButton(
                        onClick = { viewModel.downloadIpList(context) },
                        enabled = !isTesting
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = "从网络获取"
                        )
                    }
                    Text(
                        text = "获取",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                
                Spacer(modifier = Modifier.width(8.dp))
                
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    IconButton(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(ipInputText))
                        },
                        enabled = ipInputText.isNotEmpty()
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "复制"
                        )
                    }
                    Text(
                        text = "复制",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                
                Spacer(modifier = Modifier.width(8.dp))
                
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    IconButton(
                        onClick = { viewModel.clearIpList() },
                        enabled = ipInputText.isNotEmpty() && !isTesting
                    ) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = "清空"
                        )
                    }
                    Text(
                        text = "清空",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
} 