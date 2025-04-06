package com.yiguihai.tsocks

import android.Manifest
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.util.Log
import androidx.collection.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yiguihai.tsocks.utils.Preferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

// 应用图标缓存系统
object AppIconCache {
    private val iconCache = LruCache<String, ImageBitmap>(100)
    private val cacheMutex = Mutex()

    private suspend fun getIconFromCache(packageName: String): ImageBitmap? =
        cacheMutex.withLock { iconCache[packageName] }

    private suspend fun addIconToCache(packageName: String, bitmap: ImageBitmap) =
        cacheMutex.withLock { iconCache.put(packageName, bitmap) }

    suspend fun loadIcon(packageName: String, packageManager: PackageManager): ImageBitmap? =
        getIconFromCache(packageName) ?: withContext(Dispatchers.IO) {
            try {
                val drawable = packageManager.getApplicationIcon(packageName)
                val bitmap = drawable.toBitmap().asImageBitmap()
                addIconToCache(packageName, bitmap)
                bitmap
            } catch (e: Exception) {
                Log.e("AppIconCache", "加载应用图标失败: ${e.message}", e)
                null
            }
        }

    private fun Drawable.toBitmap(): Bitmap =
        Bitmap.createBitmap(intrinsicWidth.coerceAtLeast(1), intrinsicHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            .apply {
                val canvas = android.graphics.Canvas(this)
                setBounds(0, 0, canvas.width, canvas.height)
                draw(canvas)
            }
}

@Composable
fun ProxySplitScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val preferences = remember { Preferences.getInstance(context) }
    
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        ProxyTabScreen(preferences)
    }
}

@Composable
fun ProxyTabScreen(preferences: Preferences) {
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val tabTitles = listOf("分流模式", "APP", "排除IP")

    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex) {
            tabTitles.forEachIndexed { index, title ->
                Tab(selected = selectedTabIndex == index, onClick = { selectedTabIndex = index }, text = { Text(title) })
            }
        }
        when (selectedTabIndex) {
            0 -> ProxyModeTab(preferences)
            1 -> AppProxyTab(preferences)
            2 -> ExcludeIpTab(preferences)
        }
    }
}

@Composable
fun ProxyModeTab(preferences: Preferences) {
    var selectedMode by remember { mutableIntStateOf(preferences.getProxyModeIntValue()) }
    var ipv4Enabled by remember { mutableStateOf(preferences.isIPv4Enabled()) }
    var ipv6Enabled by remember { mutableStateOf(preferences.isIPv6Enabled()) }
    var dnsV4 by remember { mutableStateOf(preferences.getDnsV4String()) }
    var dnsV6 by remember { mutableStateOf(preferences.getDnsV6String()) }
    var excludeChinaIp by remember { mutableStateOf(preferences.getExcludeChinaIp()) }

    // 添加滚动功能
    val scrollState = rememberScrollState()

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scrollState)  // 确保整个内容可滚动
    ) {
        Text("VPN分流模式", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        Column(Modifier.fillMaxWidth()) {
            listOf("全局代理" to "所有连接均通过代理转发", "绕行模式" to "指定应用和IP地址不通过代理", "仅代理" to "仅代理指定的应用，其他应用直连")
                .forEachIndexed { index, (title, desc) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).clickable {
                            selectedMode = index
                            preferences.updateProxyMode(index)
                        }
                    ) {
                        RadioButton(selected = selectedMode == index, onClick = {
                            selectedMode = index
                            preferences.updateProxyMode(index)
                        })
                        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 8.dp))
                    }
                    Text(desc, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 40.dp, bottom = 8.dp))
                }
        }
        
        // 国内IP直连设置
        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))
        
        Text("国内IP直连设置", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                "排除国内IP段", 
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = excludeChinaIp,
                onCheckedChange = { 
                    excludeChinaIp = it
                    preferences.updateExcludeChinaIp(it)
                }
            )
        }
        Text(
            "启用后Shadowsocks将加载排除中国大陆IP段的ACL文件",
            style = MaterialTheme.typography.bodySmall, 
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(24.dp))
        Text("IP协议设置", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(16.dp))
        
        // IPv4设置
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically, 
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("启用IPv4", modifier = Modifier.weight(1f))
                Switch(
                    checked = ipv4Enabled,
                    onCheckedChange = { 
                        ipv4Enabled = it
                        preferences.updateIPv4Enabled(it)
                    }
                )
            }
            
            if (ipv4Enabled) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = dnsV4,
                    onValueChange = {
                        dnsV4 = it
                        preferences.updateDnsV4(it)
                    },
                    label = { Text("DNS v4服务器") },
                    placeholder = { Text("例如: 8.8.8.8") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(16.dp))
            }
        }
        
        // IPv6设置 - 添加足够的间距
        Spacer(Modifier.height(8.dp))
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically, 
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("启用IPv6", modifier = Modifier.weight(1f))
                Switch(
                    checked = ipv6Enabled,
                    onCheckedChange = { 
                        ipv6Enabled = it
                        preferences.updateIPv6Enabled(it)
                    }
                )
            }
            
            if (ipv6Enabled) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = dnsV6,
                    onValueChange = {
                        dnsV6 = it
                        preferences.updateDnsV6(it)
                    },
                    label = { Text("DNS v6服务器") },
                    placeholder = { Text("例如: 2001:4860:4860::8844") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        
        // 添加底部填充，确保滚动时内容底部有足够空间
        Spacer(Modifier.height(32.dp))
    }
}

data class AppInfo(
    val packageName: String,
    val appName: String,
    val uid: Int,
    val isSystemApp: Boolean,
    var isEnabled: Boolean = false
)


@Composable
fun AppProxyTab(preferences: Preferences) {
    val context = LocalContext.current
    val packageManager = context.packageManager
    val currentPackageName = context.packageName

    val allApps = remember { mutableStateListOf<AppInfo>() }
    val filteredApps = remember { mutableStateListOf<AppInfo>() }
    var searchQuery by remember { mutableStateOf("") }
    var showFilterMenu by remember { mutableStateOf(false) }
    var filterType by remember { mutableStateOf("全部应用") }
    var isLoading by remember { mutableStateOf(true) }

    var totalAppCount by remember { mutableIntStateOf(0) }
    var enabledAppCount by remember { mutableIntStateOf(0) }
    var filteredAppCount by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        isLoading = true
        withContext(Dispatchers.IO) {
            try {
                val tempApps = packageManager.getInstalledPackages(PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
                    .filter { it.packageName != currentPackageName && it.requestedPermissions?.contains(Manifest.permission.INTERNET) == true }
                    .map { pkg ->
                        val appInfo = pkg.applicationInfo
                        val packageName = pkg.packageName
                        val isEnabled = preferences.proxiedApps.find { it.packageName == packageName }?.isEnabled ?: false
                        AppInfo(
                            packageName = packageName,
                            appName = appInfo?.loadLabel(packageManager)?.toString() ?: packageName,
                            uid = appInfo?.uid ?: 0,
                            isSystemApp = appInfo?.let { it.flags and ApplicationInfo.FLAG_SYSTEM != 0 || it.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0 } ?: false,
                            isEnabled = isEnabled
                        )
                    }.sortedWith(compareByDescending<AppInfo> { it.isEnabled }.thenBy { it.appName })

                // 更新所有应用到preferences.proxiedApps
                val enabledApps = tempApps.filter { it.isEnabled }
                if (enabledApps.isNotEmpty()) {
                    // 先将已启用的应用添加到preferences.proxiedApps中
                    withContext(Dispatchers.Main) {
                        // 对于每个已启用的应用，确保它们存在于proxiedApps列表中
                        enabledApps.forEach { app ->
                            if (preferences.proxiedApps.find { it.packageName == app.packageName } == null) {
                                preferences.proxiedApps.add(app)
                            }
                        }
                        // 强制保存配置
                        preferences.updateAppProxyStatus(enabledApps.first().packageName, enabledApps.first().isEnabled)
                    }
                }

                withContext(Dispatchers.Main) {
                    allApps.clear()
                    allApps.addAll(tempApps)
                    filteredApps.clear()
                    filteredApps.addAll(tempApps)
                    totalAppCount = tempApps.size
                    enabledAppCount = tempApps.count { it.isEnabled }
                    filteredAppCount = tempApps.size
                    isLoading = false
                }
            } catch (e: Exception) {
                Log.e("ProxySplit", "获取应用列表失败: ${e.message}", e)
                isLoading = false
            }
        }
    }

    LaunchedEffect(searchQuery, filterType) {
        filteredApps.clear()
        filteredApps.addAll(allApps.filter { app ->
            (app.appName.contains(searchQuery, ignoreCase = true) || app.packageName.contains(searchQuery, ignoreCase = true)) &&
                    when (filterType) {
                        "系统应用" -> app.isSystemApp
                        "用户应用" -> !app.isSystemApp
                        else -> true
                    }
        }.sortedWith(compareByDescending<AppInfo> { it.isEnabled }.thenBy { it.appName }))
        filteredAppCount = filteredApps.size
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("搜索应用") },
                leadingIcon = { Icon(Icons.Filled.Search, "搜索") },
                trailingIcon = { if (searchQuery.isNotEmpty()) IconButton({ searchQuery = "" }) { Icon(Icons.Filled.Clear, "清除") } },
                singleLine = true
            )
            Spacer(Modifier.width(8.dp))
            Box {
                FilterChip(
                    selected = false,
                    onClick = { showFilterMenu = true },
                    leadingIcon = { Icon(Icons.Filled.FilterList, "过滤") },
                    label = { Text(filterType) }
                )
                DropdownMenu(
                    expanded = showFilterMenu,
                    onDismissRequest = { showFilterMenu = false },
                    modifier = Modifier.width(150.dp)
                ) {
                    listOf("全部应用", "系统应用", "用户应用").forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = { filterType = option; showFilterMenu = false }
                        )
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Text("总计: $totalAppCount | 显示: $filteredAppCount | 已选: $enabledAppCount", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            LazyColumn { 
                items(
                    items = filteredApps,
                    key = { app -> app.packageName }
                ) { app -> 
                    AppListItem(app, packageManager, preferences)
                    HorizontalDivider() 
                } 
            }
        }
    }
}

@Composable
fun AppListItem(app: AppInfo, packageManager: PackageManager, preferences: Preferences) {
    var isEnabled by remember { mutableStateOf(app.isEnabled) }
    val coroutineScope = rememberCoroutineScope()
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    // 当app.isEnabled变化时更新本地状态
    LaunchedEffect(app.isEnabled) {
        isEnabled = app.isEnabled
    }

    LaunchedEffect(app.packageName) {
        bitmap = AppIconCache.loadIcon(app.packageName, packageManager)
        isLoading = false
    }

    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(if (isLoading) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center
        ) {
            when {
                isLoading -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                bitmap != null -> Image(bitmap!!, "应用图标", Modifier.size(40.dp).clip(CircleShape).background(Color.White).padding(4.dp))
                else -> Text(
                    app.appName.take(1).uppercase(), color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).padding(8.dp), fontWeight = FontWeight.Bold
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                app.appName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = if (isEnabled) FontWeight.Bold else FontWeight.Normal,
                color = if (isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
            Row {
                Text("UID: ${app.uid}", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (app.isSystemApp) "系统应用" else "用户应用", style = MaterialTheme.typography.bodySmall,
                    color = if (app.isSystemApp) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
                )
            }
            Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Switch(checked = isEnabled, onCheckedChange = {
            isEnabled = it
            app.isEnabled = it // 直接更新app对象的状态
            coroutineScope.launch(Dispatchers.IO) { 
                preferences.updateAppProxyStatus(app.packageName, it)
                Log.d("ProxySplit", "应用 ${app.packageName} 状态已更新为: $it")
            }
        })
    }
}

@Composable
fun ExcludeIpTab(preferences: Preferences) {
    val excludedIPs = remember { preferences.excludedIps }
    var showAddDialog by remember { mutableStateOf(false) }
    var ipInput by remember { mutableStateOf("") }
    var inputError by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = { showAddDialog = true }, modifier = Modifier.padding(vertical = 16.dp).size(48.dp)) {
            Icon(Icons.Filled.Add, "添加IP", tint = MaterialTheme.colorScheme.primary)
        }
        if (excludedIPs.isNotEmpty()) {
            LazyColumn(Modifier.fillMaxWidth()) {
                items(excludedIPs) { ip ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text(ip, Modifier.weight(1f))
                        IconButton(onClick = { preferences.removeExcludedIp(ip) }) {
                            Icon(Icons.Filled.Clear, "删除", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                    HorizontalDivider()
                }
            }
        } else {
            Spacer(Modifier.height(32.dp))
            Text("暂无排除IP地址", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false; ipInput = ""; inputError = null },
            title = { Text("添加排除IP") },
            text = {
                Column {
                    TextField(
                        value = ipInput,
                        onValueChange = { ipInput = it; inputError = null },
                        label = { Text("输入IPv4或IPv6地址") },
                        isError = inputError != null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                        modifier = Modifier.fillMaxWidth()
                    )
                    inputError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 16.dp, top = 4.dp)) }
                }
            },
            confirmButton = {
                Button(onClick = {
                    try {
                        val parsedIP = InetAddress.getByName(ipInput)
                        if (parsedIP is Inet4Address || parsedIP is Inet6Address) {
                            preferences.addExcludedIp(ipInput)
                            showAddDialog = false
                            ipInput = ""
                            inputError = null
                        } else inputError = "无效的IP地址格式"
                    } catch (e: Exception) {
                        inputError = "无效的IP地址格式"
                    }
                }) { Text("确定") }
            },
            dismissButton = { Button(onClick = { showAddDialog = false; ipInput = ""; inputError = null }) { Text("取消") } }
        )
    }
} 