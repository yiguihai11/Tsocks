package com.yiguihai.tsocks

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AboutScreen() {
    val context = LocalContext.current
    val packageInfo = remember {
        try {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(0)
            )
        } catch (e: Exception) {
            null
        }
    }
    
    // 获取版本信息
    val versionName = packageInfo?.versionName ?: "未知"
    val versionCode = packageInfo?.longVersionCode?.toString() ?: "未知"
    
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 应用图标和名称
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .clip(CircleShape)
                    .padding(8.dp),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_launcher_foreground),
                    contentDescription = "App Icon",
                    modifier = Modifier.fillMaxSize()
                )
            }
            
            Text(
                text = "TSocks",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp)
            )
            
            Text(
                text = "高级Android代理分流应用",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            
            // 版本信息
            InfoCard(
                title = "版本信息",
                icon = Icons.Default.Info
            ) {
                InfoItem(title = "版本名称", detail = versionName)
                InfoItem(title = "版本代码", detail = versionCode)
                InfoItem(title = "Android API", detail = "33+ (Android 13及以上)")
                InfoItem(title = "最后更新", detail = "2023年12月")
            }
            
            // 主要功能
            InfoCard(
                title = "主要功能",
                icon = Icons.Default.Star
            ) {
                InfoItem(title = "代理分流", detail = "全局代理、绕行模式、仅代理模式")
                InfoItem(title = "应用过滤", detail = "智能搜索和分类应用")
                InfoItem(title = "IP协议", detail = "支持IPv4和IPv6独立控制")
                InfoItem(title = "IP排除", detail = "支持IP地址排除和国内IP直连")
                InfoItem(title = "Shadowsocks", detail = "内置客户端，支持v2ray-plugin和SIP002")
            }
            
            // 开发者信息
            InfoCard(
                title = "开发者",
                icon = Icons.Default.Person
            ) {
                InfoItem(title = "开发者", detail = "yiguihai11")
                InfoItem(
                    title = "GitHub", 
                    detail = "https://github.com/yiguihai11/Tsocks",
                    isLink = true,
                    onClick = { 
                        openUrl(context, "https://github.com/yiguihai11/Tsocks") 
                    }
                )
            }
            
            // 开源信息
            InfoCard(
                title = "开源协议",
                icon = Icons.Default.Code
            ) {
                InfoItem(title = "许可证", detail = "GPLv3")
                InfoItem(
                    title = "源代码", 
                    detail = "查看源代码",
                    isLink = true,
                    onClick = { 
                        openUrl(context, "https://github.com/yiguihai11/Tsocks") 
                    }
                )
            }
            
            // 底部版权信息
            Text(
                text = "© 2023 yiguihai11. 保留所有权利",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 24.dp, bottom = 16.dp)
            )
        }
    }
}

@Composable
fun InfoCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 12.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            
            content()
        }
    }
}

@Composable
fun InfoItem(
    title: String,
    detail: String,
    isLink: Boolean = false,
    onClick: () -> Unit = {}
) {
    val textColor = if (isLink) 
        MaterialTheme.colorScheme.primary 
    else 
        MaterialTheme.colorScheme.onSurface
    
    val modifier = if (isLink) 
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onClick() }
    else 
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = detail,
            fontSize = 14.sp,
            color = textColor,
            fontWeight = if (isLink) FontWeight.Bold else FontWeight.Normal
        )
    }
}

private fun openUrl(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
    context.startActivity(intent)
} 