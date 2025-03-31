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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 优选IP测速ViewModel
 */
class OptimalIpViewModel : ViewModel() {
    private val _speedTestConfig = MutableStateFlow(SpeedTestConfig())
    val speedTestConfig: StateFlow<SpeedTestConfig> = _speedTestConfig.asStateFlow()
    
    private val _ipInputText = MutableStateFlow("")
    val ipInputText: StateFlow<String> = _ipInputText.asStateFlow()
    
    // 增加一个状态流用于控制Tab索引
    private val _selectedTabIndex = MutableStateFlow(0)
    val selectedTabIndex: StateFlow<Int> = _selectedTabIndex.asStateFlow()
    
    // 当前测试作业
    private var testJob: Job? = null
    // 上下文引用，用于保存设置
    private var appContext: Context? = null

    /**
     * 设置当前选中的Tab索引
     */
    fun setSelectedTabIndex(index: Int) {
        _selectedTabIndex.value = index
    }

    /**
     * 初始化，加载保存的配置
     * 应该在ViewModel被创建后调用
     */
    fun initialize(context: Context) {
        appContext = context.applicationContext
        
        // 使用协程异步加载配置
        viewModelScope.launch {
            // 加载测速配置
            val preferences = Preferences.getInstance(context)
            val savedConfig = preferences.loadSpeedTestConfig()
            _speedTestConfig.value = savedConfig
            
            // 加载保存的IP列表
            val savedIpList = preferences.loadImportedIpList()
            if (savedIpList.isNotEmpty()) {
                OptimalIpManager.importedIpList.clear()
                OptimalIpManager.importedIpList.addAll(savedIpList)
                _ipInputText.value = savedIpList.joinToString("\n")
            } else if (OptimalIpManager.importedIpList.isEmpty()) {
                // 如果没有保存的IP且当前列表为空，使用默认IP列表
                OptimalIpManager.importedIpList.addAll(DEFAULT_IP_LIST)
                _ipInputText.value = OptimalIpManager.importedIpList.joinToString("\n")
            }
            
            // 加载上次的网络信息，但不主动获取新信息
            val savedNetworkInfo = preferences.loadNetworkInfo()
            if (savedNetworkInfo.externalIp.isNotEmpty() && 
                savedNetworkInfo.externalIp != "获取失败" && 
                savedNetworkInfo.externalIp != "未能获取") {
                OptimalIpManager.networkInfo.value = savedNetworkInfo
            }
            
            // 加载保存的测速结果
            val savedResults = preferences.loadTestResults()
            if (savedResults.isNotEmpty()) {
                Log.d("OptimalIpViewModel", "加载保存的测速结果: ${savedResults.size} 个")
                OptimalIpManager.savedTestResults.clear()
                OptimalIpManager.savedTestResults.addAll(savedResults)
            }
            
            // 不再主动获取网络信息，只在开始测速时获取
        }
    }
    
    /**
     * 更新网络信息（运营商和外网IP）
     */
    private suspend fun updateNetworkInfo(context: Context) {
        try {
            val carrier = NetworkUtils.getCarrierName(context)
            val networkType = NetworkUtils.getNetworkType(context)
            val carrierInfo = "$carrier - $networkType"
            
            // 尝试获取外网IP
            var externalIp = NetworkUtils.getExternalIpAddress()
            var retryCount = 0
            
            // 如果获取失败且未超过重试次数，进行重试
            while (externalIp.isEmpty() && retryCount < 3) {
                Log.d("OptimalIpViewModel", "外网IP获取失败，正在重试 ${retryCount + 1}/3")
                delay(1000) // 延迟1秒再重试
                externalIp = NetworkUtils.getExternalIpAddress()
                retryCount++
            }
            
            if (externalIp.isEmpty()) {
                externalIp = "未能获取" // 提供默认值表示获取失败
                Log.w("OptimalIpViewModel", "无法获取外网IP，已尝试3次")
            } else {
                Log.d("OptimalIpViewModel", "成功获取外网IP: $externalIp")
            }
            
            OptimalIpManager.updateNetworkInfo(carrierInfo, externalIp)
            
            // 保存网络信息
            Preferences.getInstance(context).saveNetworkInfo(OptimalIpManager.networkInfo.value)
        } catch (e: Exception) {
            Log.e("OptimalIpViewModel", "初始化时获取网络信息失败: ${e.message}", e)
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
                Preferences.getInstance(context).saveSpeedTestConfig(config)
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
                Preferences.getInstance(context).saveImportedIpList(ips)
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
                    Preferences.getInstance(context).saveImportedIpList(ips)
                }
            } catch (e: Exception) {
                Log.e("OptimalIpViewModel", "下载IP列表失败", e)
                OptimalIpManager.updateStatusMessage("下载IP列表失败: ${e.message}")
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
                Preferences.getInstance(context).saveImportedIpList(emptyList())
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
            OptimalIpManager.updateStatusMessage("请先添加IP地址")
            return
        }
        
        // 设置测试状态为进行中 - 放在前面，这样配置区域会立即隐藏
        OptimalIpManager.isTestingInProgress.value = true
        OptimalIpManager.clearCurrentResults()
        OptimalIpManager.updateTotalIps(ips.size)
        
        testJob = viewModelScope.launch {
            // 获取网络信息
            try {
                val carrier = NetworkUtils.getCarrierName(context)
                val networkType = NetworkUtils.getNetworkType(context)
                val carrierInfo = "$carrier - $networkType"
                
                // 尝试获取外网IP，添加重试和错误处理
                var externalIp = NetworkUtils.getExternalIpAddress()
                var retryCount = 0
                
                // 如果获取失败且未超过重试次数，进行重试
                while (externalIp.isEmpty() && retryCount < 3 && OptimalIpManager.isTestingInProgress.value) {
                    Log.d("OptimalIpViewModel", "外网IP获取失败，正在重试 ${retryCount + 1}/3")
                    OptimalIpManager.updateStatusMessage("正在重试获取外网IP...(${retryCount + 1}/3)")
                    delay(1000) // 延迟1秒再重试
                    externalIp = NetworkUtils.getExternalIpAddress()
                    retryCount++
                }
                
                if (externalIp.isEmpty()) {
                    externalIp = "未能获取" // 提供默认值表示获取失败
                    Log.w("OptimalIpViewModel", "无法获取外网IP，已尝试3次")
                    OptimalIpManager.updateStatusMessage("无法获取外网IP，继续测试中...")
                }
                
                OptimalIpManager.updateNetworkInfo(carrierInfo, externalIp)
                
                // 保存网络信息
                Preferences.getInstance(context).saveNetworkInfo(OptimalIpManager.networkInfo.value)
                
                Log.d("OptimalIpViewModel", "获取到网络信息: 运营商=$carrierInfo, 外网IP=$externalIp")
            } catch (e: Exception) {
                Log.e("OptimalIpViewModel", "获取网络信息失败", e)
                OptimalIpManager.updateStatusMessage("获取网络信息失败：${e.message}")
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
                        OptimalIpManager.updateProgress(completed)
                    },
                    onResultUpdate = { result ->
                        // 将结果添加到列表中
                        latencyResults.add(result)
                        OptimalIpManager.addTestResult(result)
                    }
                )
                
                // 过滤出延迟测试成功的结果，按延迟排序，最多测试前20个IP的下载速度
                val sortedResults = latencyResults
                    .filter { it.isSuccessful }
                    .sortedBy { it.latencyMs }
                    .take(20)
                
                // 进行下载速度测试
                if (sortedResults.isNotEmpty() && OptimalIpManager.isTestingInProgress.value) {
                    OptimalIpManager.updateProgress(0)
                    OptimalIpManager.updateTotalIps(sortedResults.size)
                    
                    SpeedTestUtils.startSpeedTest(
                        config = config,
                        results = sortedResults,
                        onProgressUpdate = { completed, total ->
                            OptimalIpManager.updateProgress(completed)
                        },
                        onResultUpdate = { result ->
                            // 更新结果列表中的元素
                            OptimalIpManager.updateTestResult(result)
                        }
                    )
                }
                
                // 保存测试结果到历史记录
                OptimalIpManager.saveCurrentResultsToHistory()
                
                // 保存测试结果到持久化存储
                appContext?.let { ctx ->
                    viewModelScope.launch {
                        try {
                            // 保存测试结果到SharedPreferences
                            Preferences.getInstance(ctx).saveTestResults(OptimalIpManager.savedTestResults.toList())
                            Log.d("OptimalIpViewModel", "测速结果已保存到存储")
                        } catch (e: Exception) {
                            Log.e("OptimalIpViewModel", "保存测速结果失败: ${e.message}", e)
                        }
                    }
                }
                
                // 测试完成后，自动切换到测速结果Tab
                _selectedTabIndex.value = 1
            } catch (e: Exception) {
                Log.e("OptimalIpViewModel", "测速失败", e)
                OptimalIpManager.updateStatusMessage("测速失败: ${e.message}")
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
    
    // 从ViewModel中获取选项卡索引
    val selectedTabIndex by viewModel.selectedTabIndex.collectAsState()
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
                    onClick = { viewModel.setSelectedTabIndex(index) },
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
    
    // 创建派生状态，而不是直接引用OptimalIpManager中的mutableState
    val isTesting by remember { derivedStateOf { OptimalIpManager.isTestingInProgress.value } }
    val currentProgress by remember { derivedStateOf { OptimalIpManager.currentProgress.value } }
    val totalIps by remember { derivedStateOf { OptimalIpManager.totalIps.value } }
    val statusMessage by remember { derivedStateOf { OptimalIpManager.statusMessage.value } }
    val networkInfo by remember { derivedStateOf { OptimalIpManager.networkInfo.value } }
    
    // 用于自动滚动到底部
    val listState = rememberLazyListState()
    
    // 创建测试结果的派生状态，防止直接依赖可变集合
    val testResults by remember { derivedStateOf { OptimalIpManager.testResults.toList() } }
    
    // 自动滚动到底部
    LaunchedEffect(testResults.size) {
        if (testResults.isNotEmpty()) {
            try {
                // 修复索引越界问题：确保索引在有效范围内
                val lastIndex = (testResults.size - 1).coerceAtLeast(0)
                listState.animateScrollToItem(lastIndex)
            } catch (e: Exception) {
                Log.e("SpeedTestTab", "滚动到底部失败: ${e.message}", e)
            }
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
                        Column {
                            TextField(
                                value = speedTestConfig.maxTimeout.toString(),
                                onValueChange = { value ->
                                    val timeout = value.toIntOrNull() ?: 2000
                                    viewModel.updateTestConfig(speedTestConfig.copy(maxTimeout = timeout))
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            Text(
                                text = "单个IP测试的超时时间，ping模式建议1000ms，tcping模式建议2000ms",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }
                    
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Text("并发线程数: ", modifier = Modifier.width(80.dp))
                        Column {
                            TextField(
                                value = speedTestConfig.maxThreads.toString(),
                                onValueChange = { value ->
                                    val threads = value.toIntOrNull() ?: 100
                                    viewModel.updateTestConfig(speedTestConfig.copy(maxThreads = threads))
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            Text(
                                text = "延迟测试的并发线程数，数值越大测试速度越快，但可能导致网络拥堵",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }
                    
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Text("测速线程数: ", modifier = Modifier.width(80.dp))
                        Column {
                            TextField(
                                value = speedTestConfig.speedTestThreads.toString(),
                                onValueChange = { value ->
                                    val threads = value.toIntOrNull() ?: 5
                                    viewModel.updateTestConfig(speedTestConfig.copy(speedTestThreads = threads))
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            Text(
                                text = "下载速度测试的并发线程数，建议保持较小值(5-10)以免网络拥堵",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }
                    
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Text("测速URL: ", modifier = Modifier.width(80.dp))
                        Column {
                            TextField(
                                value = speedTestConfig.speedTestUrl,
                                onValueChange = { value ->
                                    viewModel.updateTestConfig(speedTestConfig.copy(speedTestUrl = value))
                                },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            Text(
                                text = "用于下载速度测试的目标URL，建议使用Cloudflare或其他CDN资源",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
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
                        progress = { if (totalIps > 0) currentProgress.toFloat() / totalIps.toFloat() else 0f },
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
            items(
                items = testResults,
                key = { result -> result.ip + result.port + result.timestamp }
            ) { result ->
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
    
    // 使用派生状态，防止直接依赖可变集合
    val savedTestResults by remember { derivedStateOf { OptimalIpManager.savedTestResults.toList() } }
    val networkInfo by remember { derivedStateOf { OptimalIpManager.networkInfo.value } }
    
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    
    val sortedResults by remember(savedTestResults) {
        // 按照下载速度和延迟排序，过滤掉下载速度为0的结果
        derivedStateOf {
            savedTestResults
                .filter { it.downloadSpeed > 0 }
                .sortedWith(
                    compareByDescending<SpeedTestResult> { it.downloadSpeed }
                        .thenBy { it.latencyMs }
                )
        }
    }
    
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (sortedResults.isNotEmpty()) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    ActionButtonWithLabel(
                        onClick = {
                            // 在Composable上下文中获取字符串资源
                            val clearSuccessMsg = context.getString(R.string.clear_success)
                            
                            coroutineScope.launch {
                                OptimalIpManager.savedTestResults.clear()
                                snackbarHostState.showSnackbar(clearSuccessMsg)
                            }
                        },
                        icon = Icons.Default.Clear,
                        contentDescription = stringResource(R.string.clear_results),
                        label = stringResource(R.string.clear_results),
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                    
                    ActionButtonWithLabel(
                        onClick = {
                            // 在这里获取字符串资源，确保在Composable上下文中访问
                            val refreshFlagsNotice = context.getString(R.string.refresh_flags_notice)
                            val importSuccessFormat = context.getString(R.string.import_success_format)
                            val noMatchingServersMsg = context.getString(R.string.no_matching_servers)
                            val importFailedFormat = context.getString(R.string.import_failed_format)
                            
                            coroutineScope.launch {
                                // 先移除try-catch包裹，将整个操作逻辑放在协程中处理
                                val result = runCatching {
                                    val importedCount = ShadowsocksImporter.importOptimalIpsToConfigFile(context, sortedResults)
                                    if (importedCount > 0) {
                                        snackbarHostState.showSnackbar(String.format(importSuccessFormat, importedCount))
                                        // 将Toast放在协程中调用，不直接在Composable上下文中调用
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(context, refreshFlagsNotice, Toast.LENGTH_LONG).show()
                                        }
                                        true
                                    } else {
                                        snackbarHostState.showSnackbar(noMatchingServersMsg)
                                        false
                                    }
                                }.getOrElse { e ->
                                    Log.e("SpeedTestResultsTab", "导入IP失败", e)
                                    snackbarHostState.showSnackbar(String.format(importFailedFormat, e.message))
                                    false
                                }
                            }
                        },
                        icon = Icons.Default.SaveAlt,
                        contentDescription = stringResource(R.string.import_to_shadowsocks),
                        label = stringResource(R.string.import_to_shadowsocks),
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
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
                    items(
                        items = sortedResults,
                        key = { result -> "${result.ip}:${result.port}:${result.timestamp}" }
                    ) { result ->
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
    // 使用更现代的java.time API
    val instant = java.time.Instant.ofEpochMilli(timestamp)
    val localDateTime = java.time.LocalDateTime.ofInstant(
        instant, 
        java.time.ZoneId.systemDefault()
    )
    val formatter = java.time.format.DateTimeFormatter.ofPattern(
        "yyyy-MM-dd HH:mm:ss", 
        java.util.Locale.getDefault()
    )
    return localDateTime.format(formatter)
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
    
    // 使用派生状态
    val isTesting by remember { derivedStateOf { OptimalIpManager.isTestingInProgress.value } }
    
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
                                    try {
                                        scrollState.animateScrollTo(0)
                                    } catch (e: Exception) {
                                        Log.e("IpListTab", "滚动到顶部失败: ${e.message}", e)
                                    }
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
                                    try {
                                        scrollState.animateScrollTo(scrollState.maxValue)
                                    } catch (e: Exception) {
                                        Log.e("IpListTab", "滚动到底部失败: ${e.message}", e)
                                    }
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
                        text = "从网络获取",
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

/**
 * 带有标签的操作按钮
 */
@Composable
private fun ActionButtonWithLabel(
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String,
    label: String,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.padding(vertical = 4.dp)
    ) {
        FloatingActionButton(
            onClick = onClick,
            modifier = Modifier.size(56.dp),
            containerColor = containerColor
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
} 