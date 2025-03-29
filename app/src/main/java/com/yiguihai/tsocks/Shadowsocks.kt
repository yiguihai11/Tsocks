package com.yiguihai.tsocks

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.gson.GsonBuilder
import com.google.gson.TypeAdapter
import com.google.gson.annotations.JsonAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import com.yiguihai.tsocks.utils.GeoIpUtils
import com.yiguihai.tsocks.utils.JsonTreeView
import com.yiguihai.tsocks.utils.NativeProgramExecutor
import com.yiguihai.tsocks.utils.Preferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Data models for the configuration
data class ShadowsocksConfig(
    val locals: List<LocalConfig> = listOf(),
    val servers: List<ServerConfig> = listOf(),
    val balancer: BalancerConfig = BalancerConfig(),
    val online_config: OnlineConfig? = null,
    val log: LogConfig = LogConfig(),
    val runtime: RuntimeConfig = RuntimeConfig()
)

data class LocalConfig(
    val protocol: String = "socks",
    val local_address: String = "127.0.0.1",
    val local_port: Int = 1081,
    val mode: String = "tcp_and_udp",
    val local_udp_address: String? = null,
    val local_udp_port: Int? = null,
    val forward_address: String? = null,
    val forward_port: Int? = null,
    val local_dns_address: String? = null,
    val local_dns_port: Int? = null,
    val remote_dns_address: String? = null,
    val remote_dns_port: Int? = null,
    val client_cache_size: Int? = null,
    val launchd_tcp_socket_name: String? = null
)

@JsonAdapter(ServerConfigAdapter::class)
data class ServerConfig(
    val disabled: Boolean = false,
    val address: String = "0.0.0.0",
    val port: Int = 8388,
    val method: String = "aes-256-gcm",
    val password: String = "",
    val plugin: String? = null,
    val plugin_opts: String? = null,
    val plugin_args: List<String> = listOf(),
    val plugin_mode: String = "tcp_only",
    val remark: String = ""
)

data class BalancerConfig(
    val max_server_rtt: Int = 5,
    val check_interval: Int = 10,
    val check_best_interval: Int? = null,
    val enable: Boolean = false
)

data class OnlineConfig(
    val config_url: String = "",
    val update_interval: Int = 3600
)

data class LogConfig(
    val level: Int = 1,
    val format: LogFormat = LogFormat(),
    val config_path: String? = null
)

data class LogFormat(
    val without_time: Boolean = false
)

data class RuntimeConfig(
    val mode: String = "single_thread",
    val worker_count: Int = 10
)

// 这些函数已被 Preferences 类替代
// Helper functions for saving and loading configuration 已被移除

// 自定义ServerConfig的TypeAdapter
class ServerConfigAdapter : TypeAdapter<ServerConfig>() {
    override fun write(out: JsonWriter, value: ServerConfig?) {
        if (value == null) {
            out.nullValue()
            return
        }
        out.beginObject()
        out.name("disabled").value(value.disabled)
        out.name("address").value(value.address)
        out.name("port").value(value.port)
        out.name("method").value(value.method)
        out.name("password").value(value.password)
        
        // 只有当plugin不为null时才输出plugin相关字段
        if (value.plugin != null) {
            out.name("plugin").value(value.plugin)
            if (value.plugin_opts != null) {
                out.name("plugin_opts").value(value.plugin_opts)
            }
            if (value.plugin_args.isNotEmpty()) {
                out.name("plugin_args").beginArray()
                for (arg in value.plugin_args) {
                    out.value(arg)
                }
                out.endArray()
            }
            out.name("plugin_mode").value(value.plugin_mode)
        }
        
        if (value.remark.isNotEmpty()) {
            out.name("remark").value(value.remark)
        }
        out.endObject()
    }

    override fun read(reader: JsonReader): ServerConfig {
        var disabled = false
        var address = "0.0.0.0"
        var port = 8388
        var method = "aes-256-gcm"
        var password = ""
        var plugin: String? = null
        var plugin_opts: String? = null
        var plugin_args = listOf<String>()
        var plugin_mode = "tcp_only"
        var remark = ""
        
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "disabled" -> disabled = reader.nextBoolean()
                "address" -> address = reader.nextString()
                "port" -> port = reader.nextInt()
                "method" -> method = reader.nextString()
                "password" -> password = reader.nextString()
                "plugin" -> plugin = reader.nextString()
                "plugin_opts" -> plugin_opts = reader.nextString()
                "plugin_args" -> {
                    val args = mutableListOf<String>()
                    reader.beginArray()
                    while (reader.hasNext()) {
                        args.add(reader.nextString())
                    }
                    reader.endArray()
                    plugin_args = args
                }
                "plugin_mode" -> plugin_mode = reader.nextString()
                "remark" -> remark = reader.nextString()
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        
        return ServerConfig(
            disabled = disabled,
            address = address,
            port = port,
            method = method,
            password = password,
            plugin = plugin,
            plugin_opts = plugin_opts,
            plugin_args = plugin_args,
            plugin_mode = plugin_mode,
            remark = remark
        )
    }
}

@Composable
fun ShadowsocksScreen() {
    val context = LocalContext.current
    val preferences = remember { Preferences.getInstance(context) }
    val coroutineScope = rememberCoroutineScope()

    var config by remember { mutableStateOf(preferences.getShadowsocksConfig()) }
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    
    // 初始化GeoIP数据库并在组件销毁时释放资源
    LaunchedEffect(Unit) { GeoIpUtils.initialize(context) }
    DisposableEffect(Unit) { onDispose { GeoIpUtils.release() } }

    // 使用扩展函数更新配置
    fun updateConfig(newConfig: ShadowsocksConfig) {
        config = newConfig
        coroutineScope.launch(Dispatchers.IO) {
            runCatching { preferences.updateShadowsocksConfig(newConfig) }
                .onFailure { e ->
                    Log.e("Shadowsocks", "保存配置失败: ${e.message}", e)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "保存配置失败", Toast.LENGTH_SHORT).show()
                    }
                }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            TabRow(selectedTabIndex = selectedTabIndex) {
                listOf("Locals", "Servers", "Balancer", "Advanced", "JSON").forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTabIndex == index,
                        onClick = { selectedTabIndex = index },
                        text = { Text(title) }
                    )
                }
            }

            when (selectedTabIndex) {
                0 -> LocalsTab(
                    locals = config.locals,
                    onLocalsChanged = { updateConfig(config.copy(locals = it)) }
                )
                1 -> ServersTab(
                    servers = config.servers,
                    balancer = config.balancer,
                    onServersChanged = { updateConfig(config.copy(servers = it)) },
                    onBalancerChanged = { updateConfig(config.copy(balancer = it)) }
                )
                2 -> BalancerTab(
                    balancer = config.balancer,
                    onBalancerChanged = { updateConfig(config.copy(balancer = it)) }
                )
                3 -> AdvancedTab(
                    onlineConfig = config.online_config,
                    logConfig = config.log,
                    runtimeConfig = config.runtime,
                    onOnlineConfigChanged = { updateConfig(config.copy(online_config = it)) },
                    onLogConfigChanged = { updateConfig(config.copy(log = it)) },
                    onRuntimeConfigChanged = { updateConfig(config.copy(runtime = it)) }
                )
                4 -> JsonViewTab(config = config)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalsTab(
    locals: List<LocalConfig>,
    onLocalsChanged: (List<LocalConfig>) -> Unit
) {
    var localsList by remember { mutableStateOf(locals) }
    var expandedItemIndex by remember { mutableStateOf<Int?>(null) }
    val context = LocalContext.current
    // 使用NativeProgramExecutor获取支持的协议列表
    val executor = remember { NativeProgramExecutor(context) }
    val protocols = remember { 
        runCatching { executor.getSupportedProtocols() }
            .getOrElse { 
                Log.e("Shadowsocks", "获取协议列表失败: ${it.message}", it)
                listOf("socks", "tunnel", "dns") // 默认值
            } 
    }
    // 备用的协议列表（当获取到的列表为空时使用）
    val defaultProtocols = listOf("socks", "tunnel", "dns")
    // 最终使用的协议列表
    val availableProtocols = protocols.ifEmpty { defaultProtocols }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "Local Configurations",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(localsList.size) { index ->
                val local = localsList[index]
                val isExpanded = expandedItemIndex == index
                val rotationDegree by animateFloatAsState(targetValue = if (isExpanded) 180f else 0f)

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        expandedItemIndex = if (isExpanded) null else index
                    },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${local.protocol} - ${local.local_address}:${local.local_port}",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                IconButton(onClick = {
                                    val newList = localsList.toMutableList()
                                    newList.removeAt(index)
                                    localsList = newList
                                    onLocalsChanged(newList)
                                }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete")
                                }
                            }
                            
                            // 居中下拉箭头
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = if (isExpanded) "折叠" else "展开",
                                modifier = Modifier.rotate(rotationDegree)
                            )
                        }

                        if (isExpanded) {
                            Spacer(modifier = Modifier.height(8.dp))

                            val updatedLocal = remember { mutableStateOf(local) }

                            Column(
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Protocol dropdown - 使用实际获取到的协议列表
                                var expandedProtocol by remember { mutableStateOf(false) }
                                ExposedDropdownMenuBox(
                                    expanded = expandedProtocol,
                                    onExpandedChange = { expandedProtocol = !expandedProtocol }
                                ) {
                                    TextField(
                                        value = updatedLocal.value.protocol,
                                        onValueChange = { },
                                        label = { Text("Protocol") },
                                        readOnly = true,
                                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedProtocol) },
                                        colors = ExposedDropdownMenuDefaults.textFieldColors(),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .menuAnchor()
                                    )

                                    ExposedDropdownMenu(
                                        expanded = expandedProtocol,
                                        onDismissRequest = { expandedProtocol = false }
                                    ) {
                                        availableProtocols.forEach { protocol ->
                                            DropdownMenuItem(
                                                text = { Text(protocol) },
                                                onClick = {
                                                    val newLocal = updatedLocal.value.copy(protocol = protocol)
                                                    updatedLocal.value = newLocal

                                                    val newList = localsList.toMutableList()
                                                    newList[index] = newLocal
                                                    localsList = newList
                                                    onLocalsChanged(newList)
                                                    expandedProtocol = false
                                                }
                                            )
                                        }
                                    }
                                }

                                // Address and port
                                TextField(
                                    value = updatedLocal.value.local_address,
                                    onValueChange = {
                                        val newLocal = updatedLocal.value.copy(local_address = it)
                                        updatedLocal.value = newLocal

                                        val newList = localsList.toMutableList()
                                        newList[index] = newLocal
                                        localsList = newList
                                        onLocalsChanged(newList)
                                    },
                                    label = { Text("Local Address") },
                                    modifier = Modifier.fillMaxWidth()
                                )

                                TextField(
                                    value = updatedLocal.value.local_port.toString(),
                                    onValueChange = {
                                        val port = it.toIntOrNull() ?: return@TextField
                                        val newLocal = updatedLocal.value.copy(local_port = port)
                                        updatedLocal.value = newLocal

                                        val newList = localsList.toMutableList()
                                        newList[index] = newLocal
                                        localsList = newList
                                        onLocalsChanged(newList)
                                    },
                                    label = { Text("Local Port") },
                                    modifier = Modifier.fillMaxWidth()
                                )

                                // Mode dropdown
                                val modes = listOf("tcp_only", "tcp_and_udp", "udp_only")
                                var expandedMode by remember { mutableStateOf(false) }
                                
                                // HTTP协议只支持TCP，不显示模式选择
                                if (updatedLocal.value.protocol != "http") {
                                    ExposedDropdownMenuBox(
                                        expanded = expandedMode,
                                        onExpandedChange = { expandedMode = !expandedMode }
                                    ) {
                                        TextField(
                                            value = updatedLocal.value.mode,
                                            onValueChange = { },
                                            label = { Text("Mode") },
                                            readOnly = true,
                                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedMode) },
                                            colors = ExposedDropdownMenuDefaults.textFieldColors(),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .menuAnchor()
                                        )

                                        ExposedDropdownMenu(
                                            expanded = expandedMode,
                                            onDismissRequest = { expandedMode = false }
                                        ) {
                                            modes.forEach { mode ->
                                                DropdownMenuItem(
                                                    text = { Text(mode) },
                                                    onClick = {
                                                        val newLocal = updatedLocal.value.copy(mode = mode)
                                                        updatedLocal.value = newLocal

                                                        val newList = localsList.toMutableList()
                                                        newList[index] = newLocal
                                                        localsList = newList
                                                        onLocalsChanged(newList)
                                                        expandedMode = false
                                                    }
                                                )
                                            }
                                        }
                                    }
                                } else {
                                    // 如果是HTTP协议，则强制设置为tcp_only且显示提示
                                    LaunchedEffect(updatedLocal.value.protocol) {
                                        if (updatedLocal.value.mode != "tcp_only") {
                                            val newLocal = updatedLocal.value.copy(mode = "tcp_only")
                                            updatedLocal.value = newLocal
                                            
                                            val newList = localsList.toMutableList()
                                            newList[index] = newLocal
                                            localsList = newList
                                            onLocalsChanged(newList)
                                        }
                                    }
                                    
                                    Text(
                                        text = "HTTP协议仅支持TCP模式",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(vertical = 8.dp)
                                    )
                                }

                                // Protocol-specific fields
                                when (updatedLocal.value.protocol) {
                                    "socks" -> {
                                        // UDP fields for SOCKS
                                        TextField(
                                            value = updatedLocal.value.local_udp_address ?: "",
                                            onValueChange = {
                                                val newLocal = updatedLocal.value.copy(local_udp_address = it.ifEmpty { null })
                                                updatedLocal.value = newLocal

                                                val newList = localsList.toMutableList()
                                                newList[index] = newLocal
                                                localsList = newList
                                                onLocalsChanged(newList)
                                            },
                                            label = { Text("Local UDP Address (Optional)") },
                                            modifier = Modifier.fillMaxWidth()
                                        )

                                        TextField(
                                            value = updatedLocal.value.local_udp_port?.toString() ?: "",
                                            onValueChange = {
                                                val port = it.toIntOrNull()
                                                val newLocal = updatedLocal.value.copy(local_udp_port = port)
                                                updatedLocal.value = newLocal

                                                val newList = localsList.toMutableList()
                                                newList[index] = newLocal
                                                localsList = newList
                                                onLocalsChanged(newList)
                                            },
                                            label = { Text("Local UDP Port (Optional)") },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                    "tunnel" -> {
                                        // Forward fields for Tunnel
                                        TextField(
                                            value = updatedLocal.value.forward_address ?: "",
                                            onValueChange = {
                                                val newLocal = updatedLocal.value.copy(forward_address = it.ifEmpty { null })
                                                updatedLocal.value = newLocal

                                                val newList = localsList.toMutableList()
                                                newList[index] = newLocal
                                                localsList = newList
                                                onLocalsChanged(newList)
                                            },
                                            label = { Text("Forward Address") },
                                            modifier = Modifier.fillMaxWidth()
                                        )

                                        TextField(
                                            value = updatedLocal.value.forward_port?.toString() ?: "",
                                            onValueChange = {
                                                val port = it.toIntOrNull()
                                                val newLocal = updatedLocal.value.copy(forward_port = port)
                                                updatedLocal.value = newLocal

                                                val newList = localsList.toMutableList()
                                                newList[index] = newLocal
                                                localsList = newList
                                                onLocalsChanged(newList)
                                            },
                                            label = { Text("Forward Port") },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                    "dns" -> {
                                        // DNS specific fields
                                        TextField(
                                            value = updatedLocal.value.local_dns_address ?: "",
                                            onValueChange = {
                                                val newLocal = updatedLocal.value.copy(local_dns_address = it.ifEmpty { null })
                                                updatedLocal.value = newLocal

                                                val newList = localsList.toMutableList()
                                                newList[index] = newLocal
                                                localsList = newList
                                                onLocalsChanged(newList)
                                            },
                                            label = { Text("Local DNS Address") },
                                            modifier = Modifier.fillMaxWidth()
                                        )

                                        TextField(
                                            value = updatedLocal.value.local_dns_port?.toString() ?: "",
                                            onValueChange = {
                                                val port = it.toIntOrNull()
                                                val newLocal = updatedLocal.value.copy(local_dns_port = port)
                                                updatedLocal.value = newLocal

                                                val newList = localsList.toMutableList()
                                                newList[index] = newLocal
                                                localsList = newList
                                                onLocalsChanged(newList)
                                            },
                                            label = { Text("Local DNS Port (Optional)") },
                                            modifier = Modifier.fillMaxWidth()
                                        )

                                        TextField(
                                            value = updatedLocal.value.remote_dns_address ?: "",
                                            onValueChange = {
                                                val newLocal = updatedLocal.value.copy(remote_dns_address = it.ifEmpty { null })
                                                updatedLocal.value = newLocal

                                                val newList = localsList.toMutableList()
                                                newList[index] = newLocal
                                                localsList = newList
                                                onLocalsChanged(newList)
                                            },
                                            label = { Text("Remote DNS Address") },
                                            modifier = Modifier.fillMaxWidth()
                                        )

                                        TextField(
                                            value = updatedLocal.value.remote_dns_port?.toString() ?: "",
                                            onValueChange = {
                                                val port = it.toIntOrNull()
                                                val newLocal = updatedLocal.value.copy(remote_dns_port = port)
                                                updatedLocal.value = newLocal

                                                val newList = localsList.toMutableList()
                                                newList[index] = newLocal
                                                localsList = newList
                                                onLocalsChanged(newList)
                                            },
                                            label = { Text("Remote DNS Port (Optional)") },
                                            modifier = Modifier.fillMaxWidth()
                                        )

                                        TextField(
                                            value = updatedLocal.value.client_cache_size?.toString() ?: "",
                                            onValueChange = {
                                                val size = it.toIntOrNull()
                                                val newLocal = updatedLocal.value.copy(client_cache_size = size)
                                                updatedLocal.value = newLocal

                                                val newList = localsList.toMutableList()
                                                newList[index] = newLocal
                                                localsList = newList
                                                onLocalsChanged(newList)
                                            },
                                            label = { Text("Client Cache Size (Optional)") },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                    "http" -> {
                                        // HTTP 协议特有的字段
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "HTTP代理配置",
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Bold
                                            )
                                            
                                            // 添加帮助按钮
                                            IconButton(
                                                onClick = {
                                                    // 显示HTTP代理设置帮助提示
                                                    Toast.makeText(
                                                        context,
                                                        "在Wi-Fi设置中找到代理设置，选择\"手动\"，输入此处的地址和端口",
                                                        Toast.LENGTH_LONG
                                                    ).show()
                                                }
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Help,
                                                    contentDescription = "查看帮助",
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                        
                                        // 在Android设备上提示HTTP协议信息
                                        Text(
                                            text = "HTTP代理模式只支持TCP连接，可作为浏览器或系统的HTTP代理服务器",
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.padding(top = 8.dp)
                                        )
                                        
                                        Text(
                                            text = "配置步骤：将此地址和端口设置为HTTP代理服务器即可使用",
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.padding(top = 4.dp)
                                        )
                                        
                                        // 清除协议切换时可能残留的无关字段
                                        LaunchedEffect(updatedLocal.value.protocol) {
                                            // 如果有残留的UDP字段、DNS字段或转发字段，清除它们
                                            val needsCleaning = updatedLocal.value.local_udp_address != null || 
                                                               updatedLocal.value.local_udp_port != null ||
                                                               updatedLocal.value.forward_address != null ||
                                                               updatedLocal.value.forward_port != null ||
                                                               updatedLocal.value.local_dns_address != null ||
                                                               updatedLocal.value.local_dns_port != null ||
                                                               updatedLocal.value.remote_dns_address != null ||
                                                               updatedLocal.value.remote_dns_port != null ||
                                                               updatedLocal.value.client_cache_size != null
                                            
                                            if (needsCleaning) {
                                                val newLocal = updatedLocal.value.copy(
                                                    local_udp_address = null,
                                                    local_udp_port = null,
                                                    forward_address = null,
                                                    forward_port = null,
                                                    local_dns_address = null,
                                                    local_dns_port = null,
                                                    remote_dns_address = null,
                                                    remote_dns_port = null,
                                                    client_cache_size = null
                                                )
                                                updatedLocal.value = newLocal
                                                
                                                val newList = localsList.toMutableList()
                                                newList[index] = newLocal
                                                localsList = newList
                                                onLocalsChanged(newList)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Button(
            onClick = {
                val newList = localsList.toMutableList()
                newList.add(LocalConfig())
                localsList = newList
                onLocalsChanged(newList)
                expandedItemIndex = newList.size - 1
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Add Local Configuration")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServersTab(
    servers: List<ServerConfig>,
    balancer: BalancerConfig,
    onServersChanged: (List<ServerConfig>) -> Unit,
    onBalancerChanged: (BalancerConfig) -> Unit
) {
    var serversList by remember { mutableStateOf(servers) }
    var expandedItemIndex by remember { mutableStateOf<Int?>(null) }
    var balancerConfig by remember { mutableStateOf(balancer) }
    val context = LocalContext.current
    // 存储IP地址对应的国旗资源ID
    val countryFlags = remember { mutableStateMapOf<String, Int?>() }
    // 存储IP地址对应的网络类型
    val networkTypes = remember { mutableStateMapOf<String, String?>() }
    // 存储IP地址对应的ping延迟结果
    val pingResults = remember { mutableStateMapOf<String, String>() }
    // 存储服务器的TCP连接延迟结果
    val tcpingResults = remember { mutableStateMapOf<String, String>() }
    val coroutineScope = rememberCoroutineScope()
    
    // 选中的服务器索引（在非负载均衡模式下使用）
    var selectedServerIndex by remember { mutableStateOf<Int?>(null) }
    
    // 测试状态变量
    var isPingTesting by remember { mutableStateOf(false) }
    var isTcpingTesting by remember { mutableStateOf(false) }
    var testJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    
    // 初始化选中服务器（在非负载均衡模式下）
    LaunchedEffect(balancerConfig.enable, serversList) {
        if (!balancerConfig.enable) {
            // 在非负载均衡模式下，查找第一个未禁用的服务器作为默认选中
            val enabledIndex = serversList.indexOfFirst { !it.disabled }
            selectedServerIndex = if (enabledIndex >= 0) enabledIndex else null
            
            // 更新服务器启用状态，确保只有一个服务器启用
            if (serversList.isNotEmpty()) {
                val updatedServers = serversList.mapIndexed { index, server ->
                    server.copy(disabled = index != selectedServerIndex)
                }
                serversList = updatedServers
                onServersChanged(updatedServers)
            }
        } else {
            // 在负载均衡模式下，启用所有服务器
            val allEnabled = serversList.map { server ->
                if (server.disabled) server.copy(disabled = false) else server
            }
            if (allEnabled != serversList) {
                serversList = allEnabled
                onServersChanged(allEnabled)
            }
        }
    }
    
    // 加载国旗信息
    LaunchedEffect(Unit) {
        coroutineScope.launch {
            serversList.forEach { server ->
                if (!countryFlags.containsKey(server.address)) {
                    val flagResId = GeoIpUtils.getCountryFlagResId(context, server.address)
                    countryFlags[server.address] = flagResId
                    
                    // 如果没有找到国旗资源，可能是特殊网络
                    if (flagResId == null) {
                        networkTypes[server.address] = GeoIpUtils.getNetworkType(server.address)
                    }
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Server Configurations",
                style = MaterialTheme.typography.headlineSmall
            )
            
            // 负载均衡开关
            Column(horizontalAlignment = Alignment.End) {
                Text(text = if (balancerConfig.enable) "负载均衡模式" else "单服务器模式",
                     style = MaterialTheme.typography.bodyMedium,
                     modifier = Modifier.padding(bottom = 4.dp))
                Switch(
                    checked = balancerConfig.enable,
                    onCheckedChange = { isEnabled ->
                        val newBalancer = balancerConfig.copy(enable = isEnabled)
                        balancerConfig = newBalancer
                        onBalancerChanged(newBalancer)
                        
                        if (isEnabled) {
                            // 进入负载均衡模式，启用所有服务器
                            val allEnabled = serversList.map { server ->
                                if (server.disabled) server.copy(disabled = false) else server
                            }
                            serversList = allEnabled
                            onServersChanged(allEnabled)
                        } else {
                            // 进入单服务器模式，选择第一个服务器，禁用其他所有服务器
                            if (serversList.isNotEmpty()) {
                                selectedServerIndex = 0
                                val singleEnabled = serversList.mapIndexed { index, server ->
                                    server.copy(disabled = index != 0)
                                }
                                serversList = singleEnabled
                                onServersChanged(singleEnabled)
                            }
                        }
                    }
                )
            }
        }

        // 添加测试按钮行
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Button(
                onClick = {
                    // Ping延迟测试逻辑
                    if (isPingTesting) {
                        // 如果已经在测试中，取消测试
                        testJob?.cancel()
                        testJob = null
                        isPingTesting = false
                        return@Button
                    }
                    
                    // 开始新测试
                    isPingTesting = true
                    
                    // 获取列表中的IP进行Ping测试
                    testJob = coroutineScope.launch {
                        try {
                            serversList.forEach { server ->
                                // 如果测试被取消，提前退出
                                if (!coroutineContext.isActive) return@launch
                                
                                val address = server.address
                                // 设置为"测试中..."
                                pingResults[address] = "测试中..."
                                
                                // 判断是IP还是域名
                                val isIpAddress = address.matches(Regex("^\\d{1,3}(\\.\\d{1,3}){3}$"))
                                
                                // 在后台线程中执行ping
                                withContext(Dispatchers.IO) {
                                    try {
                                        if (!coroutineContext.isActive) return@withContext // 检查是否取消
                                        
                                        val runtime = Runtime.getRuntime()
                                        // 使用ping命令，超时1秒，发送3个包
                                        val pingCmd = if (isIpAddress) {
                                            "ping -c 3 -W 1 $address"
                                        } else {
                                            "ping -c 3 -W 1 $address"
                                        }
                                        
                                        val process = runtime.exec(pingCmd)
                                        val inputStream = process.inputStream
                                        val reader = inputStream.bufferedReader()
                                        val output = StringBuilder()
                                        var line: String?
                                        
                                        while (reader.readLine().also { line = it } != null) {
                                            if (!coroutineContext.isActive) {
                                                process.destroy() // 如果测试被取消，销毁进程
                                                break
                                            }
                                            output.append(line).append("\n")
                                        }
                                        
                                        if (!coroutineContext.isActive) return@withContext // 再次检查是否取消
                                        
                                        val exitValue = process.waitFor()
                                        
                                        if (exitValue == 0) {
                                            // 解析ping结果，提取平均延迟
                                            val pingResultText = output.toString()
                                            val avgTimeRegex = "rtt min/avg/max/mdev = (\\d+\\.\\d+)/(\\d+\\.\\d+)/(\\d+\\.\\d+)/(\\d+\\.\\d+)".toRegex()
                                            val matchResult = avgTimeRegex.find(pingResultText)
                                            
                                            if (matchResult != null) {
                                                val avgTime = matchResult.groupValues[2].toDouble()
                                                // 保存平均延迟结果
                                                withContext(Dispatchers.Main) {
                                                    if (coroutineContext.isActive) pingResults[address] = "${avgTime.toInt()}ms"
                                                }
                                            } else {
                                                withContext(Dispatchers.Main) {
                                                    if (coroutineContext.isActive) pingResults[address] = "解析失败"
                                                }
                                            }
                                        } else {
                                            withContext(Dispatchers.Main) {
                                                if (coroutineContext.isActive) pingResults[address] = "超时"
                                            }
                                        }
                                    } catch (e: Exception) {
                                        withContext(Dispatchers.Main) {
                                            if (coroutineContext.isActive) pingResults[address] = "错误"
                                        }
                                    }
                                }
                            }
                        } finally {
                            // 无论如何完成后重置状态
                            withContext(Dispatchers.Main) {
                                isPingTesting = false
                                testJob = null
                            }
                        }
                    }
                },
                enabled = !balancerConfig.enable,
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isPingTesting) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            ) {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.DataUsage,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Text(
                        text = if (isPingTesting) "停止" else "Ping",
                        maxLines = 1,
                        overflow = TextOverflow.Visible,
                        fontSize = 13.sp
                    )
                }
            }
            
            Button(
                onClick = {
                    // Tcping延迟测试逻辑
                    if (isTcpingTesting) {
                        // 如果已经在测试中，取消测试
                        testJob?.cancel()
                        testJob = null
                        isTcpingTesting = false
                        return@Button
                    }
                    
                    // 开始新测试
                    isTcpingTesting = true
                    
                    // 使用TCP协议测试IP:端口的连通性和延迟
                    testJob = coroutineScope.launch {
                        try {
                            serversList.forEach { server ->
                                // 如果测试被取消，提前退出
                                if (!coroutineContext.isActive) return@launch
                                
                                val address = server.address
                                val port = server.port
                                val serverKey = "$address:$port"
                                
                                // 设置为"测试中..."
                                tcpingResults[serverKey] = "测试中..."
                                
                                // 在后台线程中执行TCP连接测试
                                withContext(Dispatchers.IO) {
                                    try {
                                        if (!coroutineContext.isActive) return@withContext // 检查是否取消
                                        
                                        val socket = java.net.Socket()
                                        val startTime = System.currentTimeMillis()
                                        
                                        // 尝试连接，超时为2秒
                                        try {
                                            socket.connect(java.net.InetSocketAddress(address, port), 2000)
                                            val endTime = System.currentTimeMillis()
                                            val duration = endTime - startTime
                                            
                                            // 成功连接，计算延迟
                                            withContext(Dispatchers.Main) {
                                                if (coroutineContext.isActive) tcpingResults[serverKey] = "${duration}ms"
                                            }
                                            
                                            // 关闭连接
                                            try {
                                                socket.close()
                                            } catch (e: Exception) {
                                                // 忽略关闭错误
                                            }
                                        } catch (e: java.net.SocketTimeoutException) {
                                            // 连接超时
                                            withContext(Dispatchers.Main) {
                                                if (coroutineContext.isActive) tcpingResults[serverKey] = "超时"
                                            }
                                        } catch (e: java.net.ConnectException) {
                                            // 连接被拒绝
                                            withContext(Dispatchers.Main) {
                                                if (coroutineContext.isActive) tcpingResults[serverKey] = "拒绝"
                                            }
                                        } catch (e: Exception) {
                                            // 其他连接错误
                                            withContext(Dispatchers.Main) {
                                                if (coroutineContext.isActive) tcpingResults[serverKey] = "错误"
                                            }
                                        }
                                    } catch (e: Exception) {
                                        // 创建Socket错误
                                        withContext(Dispatchers.Main) {
                                            if (coroutineContext.isActive) tcpingResults[serverKey] = "错误"
                                        }
                                    }
                                }
                            }
                        } finally {
                            // 无论如何完成后重置状态
                            withContext(Dispatchers.Main) {
                                isTcpingTesting = false
                                testJob = null
                            }
                        }
                    }
                },
                enabled = !balancerConfig.enable,
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isTcpingTesting) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            ) {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Router,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Text(
                        text = if (isTcpingTesting) "停止" else "TCPing",
                        maxLines = 1,
                        overflow = TextOverflow.Visible,
                        fontSize = 13.sp
                    )
                }
            }
            
            Button(
                onClick = {
                    // 显示提示框
                    Toast.makeText(context, "紧张开发中！", Toast.LENGTH_SHORT).show()
                },
                enabled = !balancerConfig.enable,
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Speed,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Text(
                        text = "连接测试",
                        maxLines = 1,
                        overflow = TextOverflow.Visible,
                        fontSize = 13.sp
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(serversList.size) { index ->
                val server = serversList[index]
                val isExpanded = expandedItemIndex == index
                val countryFlagResId = countryFlags[server.address]
                val networkType = networkTypes[server.address]
                val isSelected = selectedServerIndex == index && !balancerConfig.enable
                val rotationDegree by animateFloatAsState(targetValue = if (isExpanded) 180f else 0f)

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        if (!balancerConfig.enable) {
                            // 在单服务器模式下，只展开/折叠详情
                            expandedItemIndex = if (isExpanded) null else index
                        }
                        // 在负载均衡模式下，点击不做任何操作
                    },
                    // 在单服务器模式下，选中的服务器高亮显示
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) 
                            MaterialTheme.colorScheme.primaryContainer 
                        else 
                            MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // 显示国旗或网络类型
                                    if (countryFlagResId != null) {
                                        // 显示国旗图标，尺寸调整为72x72
                                        Box(
                                            modifier = Modifier
                                                .size(72.dp)
                                                .padding(end = 12.dp)
                                        ) {
                                            androidx.compose.foundation.Image(
                                                painter = painterResource(id = countryFlagResId),
                                                contentDescription = "国家旗帜",
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        }
                                    } else if (networkType != null) {
                                        // 显示特殊网络标识
                                        Text(
                                            text = networkType,
                                            fontSize = 16.sp,
                                            modifier = Modifier
                                                .background(
                                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                                    shape = CircleShape
                                                )
                                                .padding(horizontal = 12.dp, vertical = 6.dp)
                                                .padding(end = 12.dp)
                                        )
                                    }
                                    
                                    Column {
                                        // 如果有备注，显示备注作为标题
                                        if (server.remark.isNotEmpty()) {
                                            Text(
                                                text = server.remark,
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        
                                        Text(
                                            text = "${server.address}:${server.port}",
                                            style = MaterialTheme.typography.titleMedium.copy(
                                                // 如果有备注，IP地址字体稍小一些
                                                fontSize = if (server.remark.isNotEmpty()) 14.sp else 16.sp
                                            )
                                        )
                                        
                                        // 如果有插件，显示插件信息
                                        if (!server.plugin.isNullOrEmpty()) {
                                            Text(
                                                text = "Plugin: ${server.plugin}",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.secondary,
                                                fontSize = 12.sp
                                            )
                                        }
                                        
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = if (server.disabled) "禁用" else "启用",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = if (server.disabled) 
                                                    MaterialTheme.colorScheme.error
                                                else 
                                                    MaterialTheme.colorScheme.primary
                                            )
                                            
                                            // 显示ping延迟结果
                                            if (pingResults.containsKey(server.address)) {
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = "延迟: ${pingResults[server.address]}",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = when(pingResults[server.address]) {
                                                        "测试中..." -> MaterialTheme.colorScheme.secondary
                                                        "超时" -> MaterialTheme.colorScheme.error
                                                        "错误" -> MaterialTheme.colorScheme.error
                                                        "解析失败" -> MaterialTheme.colorScheme.error
                                                        else -> {
                                                            // 提取数字部分进行比较
                                                            try {
                                                                val ms = pingResults[server.address]?.replace("ms", "")?.toInt() ?: 0
                                                                when {
                                                                    ms < 100 -> Color(0xFF4CAF50) // 绿色，延迟低
                                                                    ms < 200 -> Color(0xFFFFC107) // 黄色，延迟中等
                                                                    else -> Color(0xFFF44336) // 红色，延迟高
                                                                }
                                                            } catch (e: Exception) {
                                                                MaterialTheme.colorScheme.error
                                                            }
                                                        }
                                                    }
                                                )
                                            }
                                            
                                            // 显示TCP连接延迟结果
                                            val serverKey = "${server.address}:${server.port}"
                                            if (tcpingResults.containsKey(serverKey)) {
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = "TCP: ${tcpingResults[serverKey]}",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = when(tcpingResults[serverKey]) {
                                                        "测试中..." -> MaterialTheme.colorScheme.secondary
                                                        "超时" -> MaterialTheme.colorScheme.error
                                                        "拒绝" -> MaterialTheme.colorScheme.error
                                                        "错误" -> MaterialTheme.colorScheme.error
                                                        else -> {
                                                            // 提取数字部分进行比较
                                                            try {
                                                                val ms = tcpingResults[serverKey]?.replace("ms", "")?.toInt() ?: 0
                                                                when {
                                                                    ms < 100 -> Color(0xFF4CAF50) // 绿色，延迟低
                                                                    ms < 200 -> Color(0xFFFFC107) // 黄色，延迟中等
                                                                    else -> Color(0xFFF44336) // 红色，延迟高
                                                                }
                                                            } catch (e: Exception) {
                                                                MaterialTheme.colorScheme.error
                                                            }
                                                        }
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                                
                                // 单服务器模式下，显示单选按钮；负载均衡模式下，显示删除按钮
                                if (!balancerConfig.enable) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // 删除按钮
                                        IconButton(onClick = {
                                            val newList = serversList.toMutableList()
                                            newList.removeAt(index)
                                            serversList = newList
                                            onServersChanged(newList)
                                            
                                            // 如果删除的是当前选中的服务器，需要重新选择
                                            if (selectedServerIndex == index) {
                                                // 找到第一个未禁用的服务器作为新的选择
                                                val newSelectedIndex = newList.indexOfFirst { !it.disabled }
                                                selectedServerIndex = if (newSelectedIndex >= 0) newSelectedIndex else null
                                                
                                                // 更新服务器状态
                                                val updatedServers = newList.mapIndexed { idx, srv ->
                                                    srv.copy(disabled = idx != selectedServerIndex)
                                                }
                                                serversList = updatedServers
                                                onServersChanged(updatedServers)
                                            }
                                        }) {
                                            Icon(Icons.Default.Delete, contentDescription = "删除")
                                        }
                                        
                                        // 单选按钮
                                        RadioButton(
                                            selected = isSelected,
                                            onClick = {
                                                selectedServerIndex = index
                                                
                                                // 更新所有服务器状态，只有选中的启用
                                                val updatedServers = serversList.mapIndexed { idx, srv ->
                                                    srv.copy(disabled = idx != index)
                                                }
                                                serversList = updatedServers
                                                onServersChanged(updatedServers)
                                            }
                                        )
                                    }
                                } else {
                                    IconButton(onClick = {
                                        val newList = serversList.toMutableList()
                                        newList.removeAt(index)
                                        serversList = newList
                                        onServersChanged(newList)
                                    }) {
                                        Icon(Icons.Default.Delete, contentDescription = "删除")
                                    }
                                }
                            }
                            
                            // 添加居中下拉箭头，只在单服务器模式下显示
                            if (!balancerConfig.enable) {
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowDown,
                                    contentDescription = if (isExpanded) "折叠" else "展开",
                                    modifier = Modifier.rotate(rotationDegree)
                                )
                            }
                        }

                        if (isExpanded) {
                            Spacer(modifier = Modifier.height(8.dp))

                            val updatedServer = remember { mutableStateOf(server) }

                            Column(
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Enabled/Disabled Switch (在负载均衡模式下不可编辑)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("启用")
                                    Switch(
                                        checked = !updatedServer.value.disabled,
                                        onCheckedChange = {
                                            // 在单服务器模式下，选中此服务器，禁用其他服务器
                                            if (!balancerConfig.enable) {
                                                selectedServerIndex = index
                                                val updatedServers = serversList.mapIndexed { idx, srv ->
                                                    srv.copy(disabled = idx != index)
                                                }
                                                serversList = updatedServers
                                                onServersChanged(updatedServers)
                                            }
                                        },
                                        enabled = !balancerConfig.enable
                                    )
                                }

                                // 备注字段
                                TextField(
                                    value = updatedServer.value.remark,
                                    onValueChange = {
                                        val newServer = updatedServer.value.copy(remark = it)
                                        updatedServer.value = newServer

                                        val newList = serversList.toMutableList()
                                        newList[index] = newServer
                                        serversList = newList
                                        onServersChanged(newList)
                                    },
                                    label = { Text("备注别名") },
                                    placeholder = { Text("自定义服务器名称") },
                                    modifier = Modifier.fillMaxWidth()
                                )

                                // Server address and port
                                TextField(
                                    value = updatedServer.value.address,
                                    onValueChange = {
                                        val newServer = updatedServer.value.copy(address = it)
                                        updatedServer.value = newServer

                                        // 更新IP地址时重新获取国旗
                                        coroutineScope.launch {
                                            val flagResId = GeoIpUtils.getCountryFlagResId(context, it)
                                            countryFlags[it] = flagResId
                                            
                                            // 如果没有找到国旗资源，可能是特殊网络
                                            if (flagResId == null) {
                                                networkTypes[it] = GeoIpUtils.getNetworkType(it)
                                            } else {
                                                networkTypes.remove(it)
                                            }
                                        }

                                        val newList = serversList.toMutableList()
                                        newList[index] = newServer
                                        serversList = newList
                                        onServersChanged(newList)
                                    },
                                    label = { Text("服务器地址") },
                                    modifier = Modifier.fillMaxWidth()
                                )

                                TextField(
                                    value = updatedServer.value.port.toString(),
                                    onValueChange = {
                                        val port = it.toIntOrNull() ?: return@TextField
                                        val newServer = updatedServer.value.copy(port = port)
                                        updatedServer.value = newServer

                                        val newList = serversList.toMutableList()
                                        newList[index] = newServer
                                        serversList = newList
                                        onServersChanged(newList)
                                    },
                                    label = { Text("服务器端口") },
                                    modifier = Modifier.fillMaxWidth()
                                )

                                // Encryption method dropdown
                                val executor = remember { NativeProgramExecutor(context) }
                                val methods = remember { 
                                    runCatching { executor.getSupportedEncryptMethods() }
                                        .getOrElse { 
                                            Log.e("Shadowsocks", "获取加密方法失败: ${it.message}", it)
                                            listOf("aes-256-gcm", "aes-128-gcm", "chacha20-ietf-poly1305") 
                                        } 
                                }
                                var expandedEncryptionMethod by remember { mutableStateOf(false) }
                                ExposedDropdownMenuBox(
                                    expanded = expandedEncryptionMethod,
                                    onExpandedChange = { expandedEncryptionMethod = !expandedEncryptionMethod }
                                ) {
                                    TextField(
                                        value = updatedServer.value.method,
                                        onValueChange = { },
                                        label = { Text("加密方式") },
                                        readOnly = true,
                                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedEncryptionMethod) },
                                        colors = ExposedDropdownMenuDefaults.textFieldColors(),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .menuAnchor()
                                    )

                                    ExposedDropdownMenu(
                                        expanded = expandedEncryptionMethod,
                                        onDismissRequest = { expandedEncryptionMethod = false }
                                    ) {
                                        methods.forEach { method ->
                                            DropdownMenuItem(
                                                text = { Text(text = method) },
                                                onClick = {
                                                    val newServer = updatedServer.value.copy(method = method)
                                                    updatedServer.value = newServer

                                                    val newList = serversList.toMutableList()
                                                    newList[index] = newServer
                                                    serversList = newList
                                                    onServersChanged(newList)
                                                    expandedEncryptionMethod = false
                                                }
                                            )
                                        }
                                    }
                                }

                                // Password field
                                var passwordVisible by remember { mutableStateOf(false) }
                                OutlinedTextField(
                                    value = updatedServer.value.password,
                                    onValueChange = {
                                        val newServer = updatedServer.value.copy(password = it)
                                        updatedServer.value = newServer

                                        val newList = serversList.toMutableList()
                                        newList[index] = newServer
                                        serversList = newList
                                        onServersChanged(newList)
                                    },
                                    label = { Text("密码") },
                                    modifier = Modifier.fillMaxWidth(),
                                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                    trailingIcon = {
                                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                            Icon(
                                                imageVector = if (passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                                contentDescription = if (passwordVisible) "隐藏密码" else "显示密码"
                                            )
                                        }
                                    }
                                )

                                // Plugin fields
                                CustomDropdownSelector(
                                    label = "插件",
                                    value = updatedServer.value.plugin ?: "",
                                    options = listOf("", "v2ray-plugin"),
                                    onValueChange = {
                                        val newServer = updatedServer.value.copy(plugin = it.ifEmpty { null })
                                        updatedServer.value = newServer

                                        val newList = serversList.toMutableList()
                                        newList[index] = newServer
                                        serversList = newList
                                        onServersChanged(newList)
                                    }
                                )

                                // 当选择了任何插件时显示插件选项
                                if (updatedServer.value.plugin != null) {
                                    TextField(
                                        value = updatedServer.value.plugin_opts ?: "",
                                        onValueChange = {
                                            val newServer = updatedServer.value.copy(plugin_opts = it.ifEmpty { null })
                                            updatedServer.value = newServer

                                            val newList = serversList.toMutableList()
                                            newList[index] = newServer
                                            serversList = newList
                                            onServersChanged(newList)
                                        },
                                        label = { Text("插件选项 (可选)") },
                                        modifier = Modifier.fillMaxWidth()
                                    )

                                    // Plugin mode dropdown
                                    val pluginModes = listOf("tcp_only", "tcp_and_udp", "udp_only")
                                    var expandedPluginMode by remember { mutableStateOf(false) }
                                    ExposedDropdownMenuBox(
                                        expanded = expandedPluginMode,
                                        onExpandedChange = { expandedPluginMode = !expandedPluginMode }
                                    ) {
                                        TextField(
                                            value = updatedServer.value.plugin_mode,
                                            onValueChange = { },
                                            label = { Text("插件模式") },
                                            readOnly = true,
                                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedPluginMode) },
                                            colors = ExposedDropdownMenuDefaults.textFieldColors(),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .menuAnchor()
                                        )

                                        ExposedDropdownMenu(
                                            expanded = expandedPluginMode,
                                            onDismissRequest = { expandedPluginMode = false }
                                        ) {
                                            pluginModes.forEach { mode ->
                                                DropdownMenuItem(
                                                    text = { Text(mode) },
                                                    onClick = {
                                                        val newServer = updatedServer.value.copy(plugin_mode = mode)
                                                        updatedServer.value = newServer

                                                        val newList = serversList.toMutableList()
                                                        newList[index] = newServer
                                                        serversList = newList
                                                        onServersChanged(newList)
                                                        expandedPluginMode = false
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 只在单服务器模式下显示添加服务器按钮
        if (!balancerConfig.enable) {
            Button(
                onClick = {
                    val newList = serversList.toMutableList()
                    newList.add(ServerConfig(disabled = false)) // 新添加的服务器默认启用
                    serversList = newList
                    onServersChanged(newList)
                    expandedItemIndex = newList.size - 1
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("添加服务器配置")
            }
        }
    }
}

@Composable
fun BalancerTab(
    balancer: BalancerConfig,
    onBalancerChanged: (BalancerConfig) -> Unit
) {
    var config by remember { mutableStateOf(balancer) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "负载均衡配置",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "服务器负载均衡设置",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(8.dp))
                
                // 最大RTT输入框
                TextField(
                    value = config.max_server_rtt.toString(),
                    onValueChange = { 
                        it.toIntOrNull()?.let { rtt ->
                            val newConfig = config.copy(max_server_rtt = rtt)
                            config = newConfig
                            onBalancerChanged(newConfig)
                        }
                    },
                    label = { Text("最大服务器RTT (秒)") },
                    modifier = Modifier.fillMaxWidth()
                )

                // 检查间隔输入框
                TextField(
                    value = config.check_interval.toString(),
                    onValueChange = { 
                        it.toIntOrNull()?.let { interval ->
                            val newConfig = config.copy(check_interval = interval)
                            config = newConfig
                            onBalancerChanged(newConfig)
                        }
                    },
                    label = { Text("检查间隔 (秒)") },
                    modifier = Modifier.fillMaxWidth()
                )

                // 最佳检查间隔(可选)
                TextField(
                    value = config.check_best_interval?.toString() ?: "",
                    onValueChange = { 
                        val interval = it.toIntOrNull()
                        val newConfig = config.copy(check_best_interval = interval)
                        config = newConfig
                        onBalancerChanged(newConfig)
                    },
                    label = { Text("最佳检查间隔 (秒, 可选)") },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "提示: 负载均衡器根据往返时间(RTT)选择最佳服务器",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedTab(
    onlineConfig: OnlineConfig?,
    logConfig: LogConfig,
    runtimeConfig: RuntimeConfig,
    onOnlineConfigChanged: (OnlineConfig) -> Unit,
    onLogConfigChanged: (LogConfig) -> Unit,
    onRuntimeConfigChanged: (RuntimeConfig) -> Unit
) {
    var expandedSection by remember { mutableStateOf("online") } // "online", "log", "runtime", "version"
    // 使用非空的OnlineConfig实例用于UI
    var updatedOnlineConfig by remember { mutableStateOf(onlineConfig ?: OnlineConfig()) }
    var updatedLogConfig by remember { mutableStateOf(logConfig) }
    var updatedRuntimeConfig by remember { mutableStateOf(runtimeConfig) }
    val context = LocalContext.current
    val executor = remember { NativeProgramExecutor(context) }
    
    // 异步加载版本信息
    var versionInfo by remember { mutableStateOf("加载中...") }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            versionInfo = try {
                executor.getVersion()
            } catch (e: Exception) {
                "版本获取失败: ${e.message}"
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "高级设置",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // 在线配置部分
        ExpandableCard(
            title = "SIP008 在线配置",
            expanded = expandedSection == "online",
            onExpandChanged = { 
                expandedSection = if (expandedSection == "online") "" else "online" 
            }
        ) {
            // 在线配置字段
            TextField(
                value = updatedOnlineConfig.config_url,
                onValueChange = {
                    val newConfig = updatedOnlineConfig.copy(config_url = it)
                    updatedOnlineConfig = newConfig
                    onOnlineConfigChanged(newConfig)
                },
                label = { Text("配置URL") },
                placeholder = { Text("留空表示禁用在线配置") },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            TextField(
                value = updatedOnlineConfig.update_interval.toString(),
                onValueChange = {
                    it.toIntOrNull()?.let { interval ->
                        val newConfig = updatedOnlineConfig.copy(update_interval = interval)
                        updatedOnlineConfig = newConfig
                        onOnlineConfigChanged(newConfig)
                    }
                },
                label = { Text("更新间隔 (秒)") },
                modifier = Modifier.fillMaxWidth(),
                enabled = updatedOnlineConfig.config_url.isNotBlank()
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "SIP008允许从URL自动更新服务器配置，留空表示禁用",
                style = MaterialTheme.typography.bodySmall
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        
        // 日志配置部分
        ExpandableCard(
            title = "日志配置",
            expanded = expandedSection == "log", 
            onExpandChanged = { 
                expandedSection = if (expandedSection == "log") "" else "log" 
            }
        ) {
            // 日志等级滑块
            Text("日志等级: ${updatedLogConfig.level}")
            Slider(
                value = updatedLogConfig.level.toFloat(),
                onValueChange = {
                    val level = it.toInt()
                    val newConfig = updatedLogConfig.copy(level = level)
                    updatedLogConfig = newConfig
                    onLogConfigChanged(newConfig)
                },
                valueRange = 0f..5f,
                steps = 4
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 不带时间选项开关
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("不带时间戳的日志")
                Switch(
                    checked = updatedLogConfig.format.without_time,
                    onCheckedChange = {
                        val newFormat = updatedLogConfig.format.copy(without_time = it)
                        val newConfig = updatedLogConfig.copy(format = newFormat)
                        updatedLogConfig = newConfig
                        onLogConfigChanged(newConfig)
                    }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 配置路径
            TextField(
                value = updatedLogConfig.config_path ?: "",
                onValueChange = {
                    val newConfig = updatedLogConfig.copy(config_path = it.ifEmpty { null })
                    updatedLogConfig = newConfig
                    onLogConfigChanged(newConfig)
                },
                label = { Text("日志配置路径 (可选)") },
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        
        // 运行时配置部分
        ExpandableCard(
            title = "运行时配置",
            expanded = expandedSection == "runtime",
            onExpandChanged = { 
                expandedSection = if (expandedSection == "runtime") "" else "runtime" 
            }
        ) {
            // 运行模式选择
            val modes = listOf("single_thread", "multi_thread")
            var expandedRuntimeMode by remember { mutableStateOf(false) }
            
            ExposedDropdownMenuBox(
                expanded = expandedRuntimeMode,
                onExpandedChange = { expandedRuntimeMode = !expandedRuntimeMode }
            ) {
                TextField(
                    value = updatedRuntimeConfig.mode,
                    onValueChange = { },
                    label = { Text("运行模式") },
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedRuntimeMode) },
                    colors = ExposedDropdownMenuDefaults.textFieldColors(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor()
                )

                ExposedDropdownMenu(
                    expanded = expandedRuntimeMode,
                    onDismissRequest = { expandedRuntimeMode = false }
                ) {
                    modes.forEach { mode ->
                        DropdownMenuItem(
                            text = { Text(mode) },
                            onClick = {
                                val newConfig = updatedRuntimeConfig.copy(mode = mode)
                                updatedRuntimeConfig = newConfig
                                onRuntimeConfigChanged(newConfig)
                                expandedRuntimeMode = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 工作线程数量
            TextField(
                value = updatedRuntimeConfig.worker_count.toString(),
                onValueChange = {
                    it.toIntOrNull()?.let { count ->
                        val newConfig = updatedRuntimeConfig.copy(worker_count = count)
                        updatedRuntimeConfig = newConfig
                        onRuntimeConfigChanged(newConfig)
                    }
                },
                label = { Text("工作线程数量 (多线程模式)") },
                enabled = updatedRuntimeConfig.mode == "multi_thread",
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "提示: 多线程模式可能为多连接提供更好的性能",
                style = MaterialTheme.typography.bodySmall
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // 版本信息部分
        ExpandableCard(
            title = "版本信息",
            expanded = expandedSection == "version",
            onExpandChanged = { 
                expandedSection = if (expandedSection == "version") "" else "version" 
            }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = "libsslocal",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = versionInfo,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = "libsslocal 是 shadowsocks-rust 的核心组件，提供 Shadowsocks 协议的本地客户端功能。",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
fun ExpandableCard(
    title: String,
    expanded: Boolean,
    onExpandChanged: () -> Unit,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onExpandChanged
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null
                )
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(16.dp))
                content()
            }
        }
    }
}

@Composable
fun JsonViewTab(config: ShadowsocksConfig) {
    // 创建一个用于显示的配置副本，过滤掉空URL的online_config
    val displayConfig = if (config.online_config == null || config.online_config.config_url.isBlank()) {
        // 如果online_config为null或者URL为空，则创建没有online_config的副本
        config.copy(online_config = null)
    } else {
        config
    }
    
    val gson = GsonBuilder().setPrettyPrinting().create()
    val jsonString = gson.toJson(displayConfig)
    val context = LocalContext.current
    var useJsonTreeView by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "JSON配置",
                    style = MaterialTheme.typography.headlineSmall
                )
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "树形视图")
                    Switch(
                        checked = useJsonTreeView,
                        onCheckedChange = { useJsonTreeView = it }
                    )
                }
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp
            ) {
                if (useJsonTreeView) {
                    JsonTreeView(
                        jsonString = jsonString,
                        onError = { error -> 
                            Log.e("JsonViewTab", "JSON解析错误: $error")
                        }
                    )
                } else {
                    Text(
                        text = jsonString,
                        modifier = Modifier
                            .padding(16.dp)
                            .verticalScroll(scrollState),
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Text(
                text = "配置已自动保存到应用私有存储中",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        
        // 浮动复制按钮
        FloatingActionButton(
            onClick = {
                val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("JSON配置", jsonString)
                clipboardManager.setPrimaryClip(clip)
                Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.ContentCopy,
                contentDescription = "复制JSON"
            )
        }
    }
}
