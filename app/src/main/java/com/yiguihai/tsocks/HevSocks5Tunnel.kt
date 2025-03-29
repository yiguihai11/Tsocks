package com.yiguihai.tsocks

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.yiguihai.tsocks.utils.Preferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File


// 数据类定义
data class TunnelConfig(
    var name: String = "tun0",
    var mtu: Int = 8500,
    var multiQueue: Boolean = false,
    var ipv4: String = "198.18.0.1",
    var ipv6: String = "fc00::1",
    var postUpScript: String = "up.sh",
    var preDownScript: String = "down.sh"
)

data class Socks5Config(
    var port: Int = 1080,
    var address: String = "127.0.0.1",
    var udp: String = "udp",
    var pipeline: Boolean = false,
    var username: String = "username",
    var password: String = "password",
    var mark: Int = 0
)

data class MiscConfig(
    var taskStackSize: Int = 86016,
    var tcpBufferSize: Int = 65536,
    var connectTimeout: Int = 5000,
    var readWriteTimeout: Int = 60000,
    var logFile: String = "stderr",
    var logLevel: String = "warn",
    var pidFile: String = "/run/hev-socks5-tunnel.pid",
    var limitNofile: Int = 65535
)

// 标签页枚举
enum class ConfigTab(val title: String) {
    TUNNEL("隧道"),
    SOCKS5("Socks5"),
    MISC("杂项"),
    YAML("YAML")
}

@Composable
fun HevSocks5TunnelScreen() {
    val context = LocalContext.current
    val preferences = remember { Preferences.getInstance(context) }

    // 状态变量
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    var yamlContent by remember { mutableStateOf("") }

    // 配置状态
    var tunnelConfig by remember { mutableStateOf(preferences.getTunnelConfig()) }
    var socks5Config by remember { mutableStateOf(preferences.getSocks5Config()) }
    var miscConfig by remember { mutableStateOf(preferences.getMiscConfig()) }

    // 标签页
    val tabs = ConfigTab.entries.toTypedArray()

    // 监听配置变化并生成 YAML 内容
    LaunchedEffect(tunnelConfig, socks5Config, miscConfig) {
        yamlContent = generateYamlContent(tunnelConfig, socks5Config, miscConfig)
        
        // 保存配置
        preferences.updateTunnelConfig(tunnelConfig)
        preferences.updateSocks5Config(socks5Config)
        preferences.updateMiscConfig(miscConfig)
        
        // 保存YAML文件
        withContext(Dispatchers.IO) {
            try {
                val configFile = File(context.filesDir, "hev-socks5-tunnel.yaml")
                configFile.writeText(yamlContent)
                Log.d("HevSocks5Tunnel", "配置文件已保存到: ${configFile.absolutePath}")
                
                // 验证文件内容
                if (configFile.exists() && configFile.canRead()) {
                    val savedContent = configFile.readText()
                    Log.d("HevSocks5Tunnel", "保存的配置文件内容:\n$savedContent")
                } else {
                    Log.e("HevSocks5Tunnel", "配置文件保存失败: 文件不可访问")
                }
            } catch (e: Exception) {
                Log.e("HevSocks5Tunnel", "保存配置文件失败", e)
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            ScrollableTabRow(
                selectedTabIndex = selectedTabIndex,
                edgePadding = 0.dp
            ) {
                tabs.forEachIndexed { index, tab ->
                    Tab(
                        selected = selectedTabIndex == index,
                        onClick = { selectedTabIndex = index },
                        text = { Text(tab.title) }
                    )
                }
            }

            when (selectedTabIndex) {
                0 -> TunnelTab(tunnelConfig) { tunnelConfig = it }
                1 -> Socks5Tab(socks5Config) { socks5Config = it }
                2 -> MiscTab(miscConfig) { miscConfig = it }
                3 -> YamlPreviewTab(yamlContent)
            }
        }
    }
}

@Composable
fun TunnelTab(config: TunnelConfig, onConfigChanged: (TunnelConfig) -> Unit) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scrollState)
    ) {
        LabeledTextField(
            label = "接口名称",
            value = config.name,
            onValueChange = { onConfigChanged(config.copy(name = it)) }
        )
        LabeledTextField(
            label = "接口MTU",
            value = config.mtu.toString(),
            onValueChange = { onConfigChanged(config.copy(mtu = it.toIntOrNull() ?: 8500)) },
            keyboardType = KeyboardType.Number
        )
        SwitchField(
            label = "多队列",
            checked = config.multiQueue,
            onCheckedChange = { onConfigChanged(config.copy(multiQueue = it)) }
        )
        LabeledTextField(
            label = "IPv4地址",
            value = config.ipv4,
            onValueChange = { onConfigChanged(config.copy(ipv4 = it)) }
        )
        LabeledTextField(
            label = "IPv6地址",
            value = config.ipv6,
            onValueChange = { onConfigChanged(config.copy(ipv6 = it)) }
        )
        LabeledTextField(
            label = "启动后脚本",
            value = config.postUpScript,
            onValueChange = { onConfigChanged(config.copy(postUpScript = it)) }
        )
        LabeledTextField(
            label = "停止前脚本",
            value = config.preDownScript,
            onValueChange = { onConfigChanged(config.copy(preDownScript = it)) }
        )
    }
}

@Composable
fun Socks5Tab(config: Socks5Config, onConfigChanged: (Socks5Config) -> Unit) {
    val scrollState = rememberScrollState()
    var passwordVisible by remember { mutableStateOf(false) }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scrollState)
    ) {
        LabeledTextField(
            label = "Socks5服务器端口",
            value = config.port.toString(),
            onValueChange = { onConfigChanged(config.copy(port = it.toIntOrNull() ?: 1080)) },
            keyboardType = KeyboardType.Number
        )
        LabeledTextField(
            label = "Socks5服务器地址",
            value = config.address,
            onValueChange = { onConfigChanged(config.copy(address = it)) }
        )
        DropdownSelector(
            label = "Socks5 UDP中继模式",
            selectedOption = config.udp,
            options = listOf("tcp", "udp"),
            onOptionSelected = { onConfigChanged(config.copy(udp = it)) }
        )
        SwitchField(
            label = "Socks5握手使用管道模式",
            checked = config.pipeline,
            onCheckedChange = { onConfigChanged(config.copy(pipeline = it)) }
        )
        LabeledTextField(
            label = "Socks5服务器用户名",
            value = config.username,
            onValueChange = { onConfigChanged(config.copy(username = it)) }
        )
        OutlinedTextField(
            value = config.password,
            onValueChange = { onConfigChanged(config.copy(password = it)) },
            label = { Text("Socks5服务器密码") },
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(
                        imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (passwordVisible) "隐藏密码" else "显示密码"
                    )
                }
            }
        )
        LabeledTextField(
            label = "Socket标记",
            value = config.mark.toString(),
            onValueChange = { onConfigChanged(config.copy(mark = it.toIntOrNull() ?: 0)) },
            keyboardType = KeyboardType.Number
        )
    }
}

@Composable
fun MiscTab(config: MiscConfig, onConfigChanged: (MiscConfig) -> Unit) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scrollState)
    ) {
        LabeledTextField(
            label = "任务栈大小 (字节)",
            value = config.taskStackSize.toString(),
            onValueChange = { onConfigChanged(config.copy(taskStackSize = it.toIntOrNull() ?: 86016)) },
            keyboardType = KeyboardType.Number
        )
        LabeledTextField(
            label = "TCP缓冲区大小 (字节)",
            value = config.tcpBufferSize.toString(),
            onValueChange = { onConfigChanged(config.copy(tcpBufferSize = it.toIntOrNull() ?: 65536)) },
            keyboardType = KeyboardType.Number
        )
        LabeledTextField(
            label = "连接超时 (毫秒)",
            value = config.connectTimeout.toString(),
            onValueChange = { onConfigChanged(config.copy(connectTimeout = it.toIntOrNull() ?: 5000)) },
            keyboardType = KeyboardType.Number
        )
        LabeledTextField(
            label = "读写超时 (毫秒)",
            value = config.readWriteTimeout.toString(),
            onValueChange = { onConfigChanged(config.copy(readWriteTimeout = it.toIntOrNull() ?: 60000)) },
            keyboardType = KeyboardType.Number
        )
        CustomDropdownSelector(
            label = "日志文件",
            value = config.logFile,
            options = listOf("stdout", "stderr"),
            onValueChange = { onConfigChanged(config.copy(logFile = it)) }
        )
        DropdownSelector(
            label = "日志级别",
            selectedOption = config.logLevel,
            options = listOf("debug", "info", "warn", "error"),
            onOptionSelected = { onConfigChanged(config.copy(logLevel = it)) }
        )
        LabeledTextField(
            label = "PID文件",
            value = config.pidFile,
            onValueChange = { onConfigChanged(config.copy(pidFile = it)) }
        )
        LabeledTextField(
            label = "文件描述符限制",
            value = config.limitNofile.toString(),
            onValueChange = { onConfigChanged(config.copy(limitNofile = it.toIntOrNull() ?: 65535)) },
            keyboardType = KeyboardType.Number
        )
    }
}

@Composable
fun LabeledTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType)
        )
    }
}

@Composable
fun SwitchField(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DropdownSelector(
    label: String,
    selectedOption: String,
    options: List<String>,
    onOptionSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = selectedOption,
                onValueChange = {},
                readOnly = true,
                label = { Text(label) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .menuAnchor()
                    .fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            onOptionSelected(option)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun YamlPreviewTab(yamlContent: String) {
    val verticalScrollState = rememberScrollState()
    val horizontalScrollState = rememberScrollState()
    val context = LocalContext.current
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(verticalScrollState)
        ) {
            Text(
                text = highlightYaml(yamlContent),
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(horizontalScrollState)
                    .padding(8.dp)
            )
        }
        
        // 浮动复制按钮
        FloatingActionButton(
            onClick = {
                val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("YAML配置", yamlContent)
                clipboardManager.setPrimaryClip(clip)
                Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.ContentCopy,
                contentDescription = "复制YAML"
            )
        }
    }
}

@Composable
fun highlightYaml(yaml: String): AnnotatedString {
    return buildAnnotatedString {
        val lines = yaml.split("\n")
        for (line in lines) {
            if (line.trimStart().startsWith("#")) {
                withStyle(SpanStyle(color = Color.Gray)) {
                    append(line)
                }
            } else if (line.contains(":")) {
                val parts = line.split(":", limit = 2)
                if (parts.size == 2) {
                    val key = parts[0]
                    val indentLength = key.length - key.trimStart().length
                    val indent = key.substring(0, indentLength)
                    val trimmedKey = key.trimStart()
                    append(indent)
                    withStyle(SpanStyle(color = Color(0xFF008080))) {
                        append(trimmedKey)
                    }
                    append(":")
                    val value = parts[1]
                    if (value.trim().startsWith("'") && value.trim().endsWith("'")) {
                        withStyle(SpanStyle(color = Color(0xFF008000))) {
                            append(value)
                        }
                    } else if (value.trim() == "true" || value.trim() == "false") {
                        withStyle(SpanStyle(color = Color(0xFF0000FF))) {
                            append(value)
                        }
                    } else if (value.trim().matches(Regex("\\d+"))) {
                        withStyle(SpanStyle(color = Color(0xFFFF0000))) {
                            append(value)
                        }
                    } else {
                        append(value)
                    }
                } else {
                    append(line)
                }
            } else {
                append(line)
            }
            append("\n")
        }
    }
}

fun generateYamlContent(
    tunnelConfig: TunnelConfig,
    socks5Config: Socks5Config,
    miscConfig: MiscConfig
): String {
    Log.d("HevSocks5Tunnel", "开始生成YAML配置")
    Log.d("HevSocks5Tunnel", "隧道配置: $tunnelConfig")
    
    val content = buildString {
        append("tunnel:\n")
        append("  # Interface name\n")
        append("  name: ${tunnelConfig.name}\n")
        append("  # Interface MTU\n")
        append("  mtu: ${tunnelConfig.mtu}\n")
        append("  # Multi-queue\n")
        append("  multi-queue: ${tunnelConfig.multiQueue}\n")
        append("  # IPv4 address\n")
        append("  ipv4: ${tunnelConfig.ipv4}\n")
        append("  # IPv6 address\n")
        append("  ipv6: '${tunnelConfig.ipv6}'\n")
        if (tunnelConfig.postUpScript != "up.sh") {
            append("  # Post up script\n")
            append("  post-up-script: ${tunnelConfig.postUpScript}\n")
        }
        if (tunnelConfig.preDownScript != "down.sh") {
            append("  # Pre down script\n")
            append("  pre-down-script: ${tunnelConfig.preDownScript}\n")
        }
        append("\n")
        append("socks5:\n")
        append("  # Socks5 server port\n")
        append("  port: ${socks5Config.port}\n")
        append("  # Socks5 server address (ipv4/ipv6)\n")
        append("  address: ${socks5Config.address}\n")
        append("  # Socks5 UDP relay mode (tcp|udp)\n")
        append("  udp: '${socks5Config.udp}'\n")
        if (socks5Config.pipeline) {
            append("  # Socks5 handshake using pipeline mode\n")
            append("  pipeline: ${socks5Config.pipeline}\n")
        }
        if (socks5Config.username != "username") {
            append("  # Socks5 server username\n")
            append("  username: '${socks5Config.username}'\n")
        }
        if (socks5Config.password != "password") {
            append("  # Socks5 server password\n")
            append("  password: '${socks5Config.password}'\n")
        }
        if (socks5Config.mark != 0) {
            append("  # Socket mark\n")
            append("  mark: ${socks5Config.mark}\n")
        }
        append("\n")
        append("misc:\n")
        append("  # task stack size (bytes)\n")
        append("  task-stack-size: ${miscConfig.taskStackSize}\n")
        
        if (miscConfig.tcpBufferSize != 65536) {
            append("  # tcp buffer size (bytes)\n")
            append("  tcp-buffer-size: ${miscConfig.tcpBufferSize}\n")
        }
        if (miscConfig.connectTimeout != 5000) {
            append("  # connect timeout (ms)\n")
            append("  connect-timeout: ${miscConfig.connectTimeout}\n")
        }
        if (miscConfig.readWriteTimeout != 60000) {
            append("  # read-write timeout (ms)\n")
            append("  read-write-timeout: ${miscConfig.readWriteTimeout}\n")
        }
        if (miscConfig.logFile != "stderr") {
            append("  # stdout, stderr or file-path\n")
            append("  log-file: ${miscConfig.logFile}\n")
        }
        if (miscConfig.logLevel != "warn") {
            append("  # debug, info, warn or error\n")
            append("  log-level: ${miscConfig.logLevel}\n")
        }
        if (miscConfig.pidFile != "/run/hev-socks5-tunnel.pid") {
            append("  # If present, run as a daemon with this pid file\n")
            append("  pid-file: ${miscConfig.pidFile}\n")
        }
        if (miscConfig.limitNofile != 65535) {
            append("  # If present, set rlimit nofile; else use default value\n")
            append("  limit-nofile: ${miscConfig.limitNofile}\n")
        }
    }
    
    Log.d("HevSocks5Tunnel", "生成的YAML配置:\n$content")
    return content
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomDropdownSelector(
    label: String,
    value: String,
    options: List<String>,
    onValueChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var textFieldValue by remember(value) { mutableStateOf(value) }
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = textFieldValue,
                onValueChange = { 
                    textFieldValue = it
                    onValueChange(it)
                },
                label = { Text(label) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .menuAnchor()
                    .fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            textFieldValue = option
                            onValueChange(option)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}