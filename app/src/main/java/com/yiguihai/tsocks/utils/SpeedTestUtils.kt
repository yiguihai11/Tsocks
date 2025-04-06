package com.yiguihai.tsocks.utils

import android.content.Context
import android.util.Log
import com.yiguihai.tsocks.utils.OptimalIpManager.statusMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern
import javax.net.ssl.HttpsURLConnection
import kotlin.math.min
import java.io.File
import java.util.ArrayDeque
import java.net.SocketTimeoutException
import java.lang.StringBuilder
import javax.net.ssl.SSLContext
import javax.net.ssl.SNIHostName
import java.io.BufferedReader
import java.io.InputStreamReader
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLParameters

/**
 * IP测速工具类
 */
object SpeedTestUtils {
    private const val TAG = "SpeedTestUtils"
    private val ipPattern = Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b")
    
    /**
     * 解析文本中的IP地址
     */
    fun extractIpAddresses(text: String): List<String> {
        val matcher = ipPattern.matcher(text)
        val ipList = mutableListOf<String>()
        
        while (matcher.find()) {
            val ip = matcher.group()
            if (isValidIpv4(ip) && ip !in ipList) {
                ipList.add(ip)
            }
        }
        
        return ipList
    }
    
    /**
     * 校验IPv4地址格式是否正确
     */
    private fun isValidIpv4(ip: String): Boolean {
        return try {
            val parts = ip.split(".")
            if (parts.size != 4) return false
            
            parts.forEach {
                val num = it.toInt()
                if (num < 0 || num > 255) return false
            }
            true
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * 从网络下载IP列表
     */
    suspend fun downloadIpList(context: Context): List<String> = withContext(Dispatchers.IO) {
        val ipList = mutableListOf<String>()
        val urls = listOf(
            // 文本源
            "https://ghfast.top/https://raw.githubusercontent.com/ip-scanner/cloudflare/main/ip-all.txt",
            "https://ghfast.top/https://raw.githubusercontent.com/ip-scanner/cloudflare/main/ip-all-cn.txt",
            // tar.gz 存档源
            "https://ghfast.top/https://github.com/ip-scanner/cloudflare/archive/refs/heads/main.tar.gz",
            "https://ghfast.top/https://github.com/ymyuuu/IPDB/archive/refs/heads/main.tar.gz"
        )
        
        statusMessage.value = "正在从网络获取IP列表..."
        Log.d(TAG, "开始下载IP列表，尝试URLs: $urls")
        
        // 先尝试从文本源直接获取IP
        for (url in urls.take(3)) {
            try {
                Log.d(TAG, "正在连接URL: $url")
                statusMessage.value = "正在连接: $url"
                
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", "Mozilla/5.0")
                connection.connectTimeout = 10000
                connection.readTimeout = 30000
                
                Log.d(TAG, "连接已建立，正在获取响应...")
                val responseCode = connection.responseCode
                Log.d(TAG, "响应码: $responseCode")
                statusMessage.value = "已连接: $url, 响应码: $responseCode"
                
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    Log.d(TAG, "正在读取内容...")
                    statusMessage.value = "正在读取内容..."
                    
                    // 检查文件扩展名
                    val fileName = url.substringAfterLast("/")
                    val extension = fileName.substringAfterLast(".", "")
                    
                    if (extension.equals("txt", ignoreCase = true) || extension.equals("csv", ignoreCase = true)) {
                        val content = connection.inputStream.bufferedReader().use { it.readText() }
                        Log.d(TAG, "内容读取完成，大小: ${content.length} 字节")
                        
                        val extractedIps = extractIpAddresses(content)
                        Log.d(TAG, "从内容提取到 ${extractedIps.size} 个IP地址")
                        
                        if (extractedIps.isNotEmpty()) {
                            ipList.addAll(extractedIps)
                            statusMessage.value = "已从文本源获取 ${ipList.size} 个IP地址"
                            // 如果已经获取到足够的IP，可以提前结束
                            if (ipList.size > 100) {
                                Log.d(TAG, "已获取足够IP地址，跳过后续下载")
                                break
                            }
                        } else {
                            statusMessage.value = "未从 $url 提取到IP地址"
                        }
                    } else {
                        statusMessage.value = "跳过非文本文件: $url"
                        Log.d(TAG, "跳过非文本文件: $url")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "处理文本源失败: ${e.message}", e)
                statusMessage.value = "下载文本源失败: ${e.message}"
            }
        }
        
        // 如果从文本源获取的IP不够，尝试从tar.gz文件获取
        if (ipList.size < 20) {
            Log.d(TAG, "从文本源获取的IP不足，尝试从tar.gz文件获取")
            statusMessage.value = "正在从压缩文件获取IP..."
            
            // 创建缓存目录
            val cacheDir = File(context.cacheDir, "cloudflare_ips")
            if (!cacheDir.exists()) cacheDir.mkdirs()
            
            // 遍历下载压缩文件源
            for (url in urls.takeLast(2)) {
                try {
                    Log.d(TAG, "正在下载压缩文件: $url")
                    statusMessage.value = "正在下载: $url"
                    
                    // 生成唯一文件名
                    val fileName = "archive_${System.currentTimeMillis()}.tar.gz"
                    val downloadFile = File(cacheDir, fileName)
                    
                    // 下载文件
                    val connection = URL(url).openConnection() as HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.setRequestProperty("User-Agent", "Mozilla/5.0")
                    connection.connectTimeout = 10000
                    connection.readTimeout = 60000
                    
                    Log.d(TAG, "开始下载文件...")
                    val responseCode = connection.responseCode
                    if (responseCode == HttpURLConnection.HTTP_OK) {
                        // 下载到文件
                        Log.d(TAG, "响应码正常，开始保存文件...")
                        connection.inputStream.use { input ->
                            downloadFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        Log.d(TAG, "文件下载完成: ${downloadFile.length()} 字节")
                        statusMessage.value = "已下载文件，大小: ${downloadFile.length()} 字节"
                        
                        // 解压文件并提取所有IP地址
                        val extractDir = File(cacheDir, "extract_${System.currentTimeMillis()}")
                        if (!extractDir.exists()) extractDir.mkdirs()
                        
                        try {
                            Log.d(TAG, "正在解压文件...")
                            statusMessage.value = "正在解压文件..."
                            
                            // 使用Android可用的解压方法 - 这里通过ProcessBuilder调用系统的tar命令
                            val process = ProcessBuilder("tar", "-xzf", downloadFile.absolutePath, "-C", extractDir.absolutePath)
                                .redirectErrorStream(true)
                                .start()
                            
                            val exitCode = process.waitFor()
                            if (exitCode == 0) {
                                Log.d(TAG, "文件解压成功")
                                statusMessage.value = "文件解压成功，正在提取IP..."
                                
                                // 遍历所有解压的文件提取IP
                                val foundIps = extractIpsFromDirectory(extractDir)
                                Log.d(TAG, "从解压文件中提取到 ${foundIps.size} 个IP地址")
                                
                                if (foundIps.isNotEmpty()) {
                                    ipList.addAll(foundIps)
                                    statusMessage.value = "已从压缩文件提取 ${foundIps.size} 个IP地址"
                                    
                                    // 如果IP已足够，则停止处理
                                    if (ipList.size > 100) {
                                        Log.d(TAG, "已获取足够IP地址，跳过后续处理")
                                        break
                                    }
                                }
                            } else {
                                Log.e(TAG, "解压文件失败，退出码: $exitCode")
                                statusMessage.value = "解压文件失败，尝试直接读取内容..."
                                
                                // 即使无法解压，也尝试直接从下载文件中提取IP
                                val rawContent = downloadFile.readText()
                                val extractedIps = extractIpAddresses(rawContent)
                                if (extractedIps.isNotEmpty()) {
                                    ipList.addAll(extractedIps)
                                    Log.d(TAG, "直接从压缩文件中提取到 ${extractedIps.size} 个IP地址")
                                    statusMessage.value = "从压缩文件直接提取到 ${extractedIps.size} 个IP地址"
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "解压或提取过程失败: ${e.message}", e)
                            statusMessage.value = "处理压缩文件失败: ${e.message}"
                            
                            // 尝试直接从下载的文件中提取IP
                            try {
                                val rawContent = downloadFile.readText()
                                val extractedIps = extractIpAddresses(rawContent)
                                if (extractedIps.isNotEmpty()) {
                                    ipList.addAll(extractedIps)
                                    Log.d(TAG, "直接从压缩文件中提取到 ${extractedIps.size} 个IP地址")
                                    statusMessage.value = "从压缩文件直接提取到 ${extractedIps.size} 个IP地址"
                                }
                            } catch (e2: Exception) {
                                Log.e(TAG, "直接提取失败: ${e2.message}", e2)
                            }
                        } finally {
                            // 清理临时文件
                            try {
                                downloadFile.delete()
                                extractDir.deleteRecursively()
                            } catch (e: Exception) {
                                Log.e(TAG, "清理临时文件失败: ${e.message}", e)
                            }
                        }
                    } else {
                        Log.e(TAG, "下载压缩文件失败，响应码: $responseCode")
                        statusMessage.value = "下载压缩文件失败，响应码: $responseCode"
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "处理压缩文件源失败: ${e.message}", e)
                    statusMessage.value = "处理压缩文件源失败: ${e.message}"
                }
            }
        }
        
        // 如果没有获取到IP，显示错误信息
        if (ipList.isEmpty()) {
            Log.e(TAG, "未能从网络获取任何IP地址")
            statusMessage.value = "未能从网络获取IP地址，请稍后重试或手动添加"
        } else {
            val distinctIps = ipList.distinct()
            Log.d(TAG, "最终获取到 ${distinctIps.size} 个不重复IP地址")
            statusMessage.value = "最终获取到 ${distinctIps.size} 个IP地址"
            return@withContext distinctIps
        }
        
        // 返回空列表
        emptyList()
    }
    
    /**
     * 递归遍历目录，从所有文件中提取IP地址
     */
    private fun extractIpsFromDirectory(directory: File): List<String> {
        val result = mutableListOf<String>()
        val queue = ArrayDeque<File>()
        queue.add(directory)
        
        var processedFiles = 0
        val maxFiles = 100 // 限制处理的最大文件数
        
        while (queue.isNotEmpty() && processedFiles < maxFiles) {
            val file = queue.removeFirst()
            
            if (file.isDirectory) {
                file.listFiles()?.forEach { queue.add(it) }
            } else if (file.isFile && file.length() < 10 * 1024 * 1024) { // 限制文件大小 < 10MB
                try {
                    // 只从txt和csv文件中提取IP
                    val extension = file.extension.lowercase()
                    if (extension == "txt" || extension == "csv") {
                        val content = file.readText()
                        val ips = extractIpAddresses(content)
                        if (ips.isNotEmpty()) {
                            Log.d(TAG, "从${extension}文件 ${file.name} 中提取到 ${ips.size} 个IP")
                            result.addAll(ips)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "读取文件 ${file.name} 失败: ${e.message}", e)
                }
                processedFiles++
            }
        }
        
        return result
    }
    
    /**
     * 开始IP可用性和延迟测试
     */
    suspend fun startLatencyTest(
        config: SpeedTestConfig,
        ips: List<String>,
        onProgressUpdate: (Int, Int) -> Unit,
        onResultUpdate: (SpeedTestResult) -> Unit
    ) = withContext(Dispatchers.IO) {
        val totalIps = ips.size
        val processedCount = AtomicInteger(0)
        val batchSize = min(config.maxThreads, totalIps)
        
        statusMessage.value = "正在测试IP延迟和可用性..."
        
        coroutineScope {
            ips.chunked(batchSize).forEach { batch ->
                if (!OptimalIpManager.isTestingInProgress.value) return@coroutineScope
                
                val deferreds = batch.map { ip ->
                    async {
                        if (!OptimalIpManager.isTestingInProgress.value) return@async
                        
                        try {
                            val result = if (config.testMode == "ping") {
                                pingTest(ip, config.maxTimeout)
                            } else {
                                tcpingTest(ip, 0, config.maxTimeout, config.speedTestUrl)
                            }
                            
                            if (result.isSuccessful) {
                                // 添加位置信息
                                // 这里尝试根据IP获取位置信息
                                // 假设IP是Cloudflare的三字码IP，我们可以提取三字码
                                // 例如104.17.209.9 -> 如果是Cloudflare的IP，我们可以从某些特征尝试提取数据中心代码
                                val updatedResult = getLocationFromIp(result)
                                onResultUpdate(updatedResult)
                            }
                            
                            val completed = processedCount.incrementAndGet()
                            onProgressUpdate(completed, totalIps)
                            
                        } catch (e: Exception) {
                            Log.e(TAG, "测试IP失败: $ip, ${e.message}", e)
                            val completed = processedCount.incrementAndGet()
                            onProgressUpdate(completed, totalIps)
                        }
                    }
                }
                
                deferreds.awaitAll()
            }
        }
        
        statusMessage.value = "延迟测试完成，共测试 $totalIps 个IP"
    }
    
    /**
     * 根据IP地址尝试获取位置信息
     */
    private fun getLocationFromIp(result: SpeedTestResult): SpeedTestResult {
        // 已知的Cloudflare数据中心三字码对应
        val knownIpRanges = mapOf(
            "ATL" to listOf("162.158.128.", "162.158.129."),
            "DFW" to listOf("172.69.68.", "172.69.69.", "108.162.238."),
            "IAD" to listOf("162.158.76.", "162.158.77.", "108.162.226."),
            "LAX" to listOf("172.69.34.", "172.69.35.", "108.162.236."),
            "MIA" to listOf("162.158.90.", "162.158.91.", "108.162.232."),
            "ORD" to listOf("172.69.48.", "172.69.49.", "108.162.216."),
            "SJC" to listOf("162.158.186.", "162.158.187.", "108.162.220."),
            "SEA" to listOf("172.69.54.", "172.69.55.", "108.162.228."),
            "EWR" to listOf("162.158.84.", "162.158.85.", "108.162.212."),
            "YYZ" to listOf("172.69.52.", "172.69.53.", "108.162.230."),
            "AMS" to listOf("162.158.88.", "162.158.89.", "108.162.210."),
            "FRA" to listOf("162.158.94.", "162.158.95.", "108.162.242."),
            "LHR" to listOf("162.158.92.", "162.158.93.", "108.162.246."),
            "CDG" to listOf("172.69.40.", "172.69.41.", "108.162.244."),
            "ARN" to listOf("162.158.86.", "162.158.87.", "108.162.214."),
            "BRU" to listOf("172.69.50.", "172.69.51.", "108.162.222."),
            "MAD" to listOf("162.158.82.", "162.158.83.", "108.162.240."),
            "MXP" to listOf("172.69.56.", "172.69.57.", "108.162.218."),
            "MUC" to listOf("172.69.42.", "172.69.43.", "108.162.234.")
        )
        
        // 检查是否是已知的Cloudflare IP范围
        for ((iata, prefixes) in knownIpRanges) {
            for (prefix in prefixes) {
                if (result.ip.startsWith(prefix)) {
                    val location = OptimalIpManager.getLocationByIata(iata)
                    if (location != null) {
                        return result.copy(
                            dataCenter = iata,
                            region = location.region,
                            city = location.city
                        )
                    }
                    // 即使没找到location对象，也返回数据中心代码
                    return result.copy(dataCenter = iata)
                }
            }
        }
        
        // 通用的Cloudflare IP范围检查
        // 104.16.0.0/12, 172.64.0.0/13, 131.0.72.0/22
        if (result.ip.startsWith("104.") || 
            (result.ip.startsWith("172.") && (64..127).contains(result.ip.split(".")[1].toInt())) ||
            result.ip.startsWith("131.0.72.") || 
            result.ip.startsWith("131.0.73.") ||
            result.ip.startsWith("1.1.1.") ||
            result.ip.startsWith("1.0.0.")) {
            return result.copy(dataCenter = "CF")
        }
        
        return result
    }
    
    /**
     * 开始下载速度测试
     */
    suspend fun startSpeedTest(
        config: SpeedTestConfig,
        results: List<SpeedTestResult>,
        onProgressUpdate: (Int, Int) -> Unit,
        onResultUpdate: (SpeedTestResult) -> Unit
    ) = withContext(Dispatchers.IO) {
        val totalResults = results.size
        if (totalResults == 0) return@withContext
        
        val processedCount = AtomicInteger(0)
        val batchSize = min(config.speedTestThreads, totalResults)
        
        statusMessage.value = "正在测试下载速度..."
        Log.i(TAG, "【批量测速】开始进行下载速度测试，共 $totalResults 个IP，批次大小: $batchSize")
        
        // 验证测速URL
        val testUrl = config.speedTestUrl.trim()
        Log.i(TAG, "【批量测速】使用测速URL: $testUrl")
        
        // 如果URL不合法，返回错误
        if (testUrl.isEmpty() || (!testUrl.startsWith("http://") && !testUrl.startsWith("https://"))) {
            Log.e(TAG, "【批量测速】测速URL无效: $testUrl")
            statusMessage.value = "测速URL无效，请检查配置"
            return@withContext
        }
        
        // 测试下载服务器连通性
        try {
            Log.d(TAG, "【批量测速】正在检查测速服务器连通性: $testUrl")
            val connection = URL(testUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.instanceFollowRedirects = true
            connection.requestMethod = "HEAD"
            
            val responseCode = connection.responseCode
            Log.d(TAG, "【批量测速】测速服务器响应码: $responseCode")
            
            if (responseCode >= 400) {
                Log.e(TAG, "【批量测速】测速服务器返回错误: $responseCode")
                statusMessage.value = "测速服务器返回错误，请检查URL"
                return@withContext
            }
        } catch (e: Exception) {
            Log.e(TAG, "【批量测速】无法连接到测速服务器: ${e.message}", e)
            statusMessage.value = "无法连接到测速服务器，请检查URL和网络连接"
            return@withContext
        }
        
        coroutineScope {
            results.chunked(batchSize).forEach { batch ->
                if (!OptimalIpManager.isTestingInProgress.value) return@coroutineScope
                
                Log.d(TAG, "【批量测速】开始处理新批次，大小: ${batch.size} 个IP")
                val deferreds = batch.map { result ->
                    async {
                        if (!OptimalIpManager.isTestingInProgress.value) return@async
                        
                        try {
                            Log.d(TAG, "【批量测速】开始测试IP: ${result.ip}:${result.port}")
                            val startTime = System.currentTimeMillis()
                            
                            // 进行速度测试，增加重试逻辑
                            var speed = 0.0
                            var attemptCount = 0
                            val maxAttempts = 2 // 最大尝试次数
                            
                            while (attemptCount < maxAttempts) {
                                attemptCount++
                                speed = getDownloadSpeed(
                                    result.ip,
                                    result.port,
                                    testUrl,
                                    config.maxTimeout
                                )
                                
                                // 如果速度太低，通常是下载过快导致的，尝试进行重测
                                if (speed > 0.1) { // 如果速度超过0.1KB/s则视为有效
                                    break
                                } else if (attemptCount < maxAttempts) {
                                    Log.w(TAG, "【批量测速】IP ${result.ip} 测试结果异常 (${speed} KB/s)，尝试重新测试 (${attemptCount+1}/$maxAttempts)")
                                    delay(500) // 短暂延迟后重试
                                }
                            }
                            
                            val duration = System.currentTimeMillis() - startTime
                            Log.d(TAG, "【批量测速】IP ${result.ip} 测试完成，速度: $speed KB/s, 耗时: ${duration/1000.0}秒, 尝试次数: $attemptCount")
                            
                            val updatedResult = result.copy(downloadSpeed = speed)
                            onResultUpdate(updatedResult)
                            
                            val completed = processedCount.incrementAndGet()
                            onProgressUpdate(completed, totalResults)
                            
                            Log.d(TAG, "【批量测速】进度更新: $completed/$totalResults (${(completed * 100 / totalResults)}%)")
                            
                        } catch (e: Exception) {
                            Log.e(TAG, "【批量测速】测试下载速度失败: ${result.ip}, ${e.message}", e)
                            val completed = processedCount.incrementAndGet()
                            onProgressUpdate(completed, totalResults)
                        }
                    }
                }
                
                deferreds.awaitAll()
                Log.d(TAG, "【批量测速】批次处理完成")
            }
        }
        
        Log.i(TAG, "【批量测速】全部测试完成，共测试 $totalResults 个IP")
        statusMessage.value = "速度测试完成，共测试 $totalResults 个IP"
    }
    
    /**
     * Ping测试方法
     */
    private suspend fun pingTest(ip: String, timeoutMs: Int): SpeedTestResult = withContext(Dispatchers.IO) {
        try {
            val startTime = System.currentTimeMillis()
            val reachable = InetAddress.getByName(ip).isReachable(timeoutMs)
            val endTime = System.currentTimeMillis()
            val latency = endTime - startTime
            
            if (reachable) {
                SpeedTestResult(
                    ip = ip,
                    port = 0,
                    latency = "${latency}ms",
                    latencyMs = latency,
                    status = "成功",
                    isSuccessful = true
                )
            } else {
                SpeedTestResult(
                    ip = ip,
                    port = 0,
                    status = "失败",
                    isSuccessful = false
                )
            }
        } catch (e: Exception) {
            SpeedTestResult(
                ip = ip,
                port = 0,
                status = "失败: ${e.message}",
                isSuccessful = false
            )
        }
    }
    
    /**
     * TCP连接测试
     */
    private suspend fun tcpingTest(ip: String, port: Int, timeoutMs: Int, url: String): SpeedTestResult = withContext(Dispatchers.IO) {
        try {
            // 从URL解析端口
            val actualPort = if (port > 0) {
                port
            } else {
                val urlObj = URL(url)
                when {
                    urlObj.port > 0 -> urlObj.port
                    urlObj.protocol == "https" -> 443
                    urlObj.protocol == "http" -> 80
                    else -> 443 // 默认使用HTTPS端口
                }
            }
            
            Log.d(TAG, "执行TCP测试: $ip:$actualPort")
            val socket = Socket()
            val startTime = System.currentTimeMillis()
            socket.connect(InetSocketAddress(ip, actualPort), timeoutMs)
            val endTime = System.currentTimeMillis()
            val latency = endTime - startTime
            
            socket.close()
            
            SpeedTestResult(
                ip = ip,
                port = actualPort,
                latency = "${latency}ms",
                latencyMs = latency,
                status = "成功",
                isSuccessful = true
            )
        } catch (e: Exception) {
            Log.e(TAG, "TCP测试失败: $ip:$port - ${e.message}")
            SpeedTestResult(
                ip = ip,
                port = port,
                status = "失败",
                isSuccessful = false
            )
        }
    }
    
    /**
     * 获取下载速度 - 根据URL自动选择HTTP或HTTPS连接
     */
    private suspend fun getDownloadSpeed(
        ip: String,
        port: Int,
        url: String,
        timeoutMs: Int,
        attemptDirectIpAccess: Boolean = true
    ): Double = withContext(Dispatchers.IO) {
        // 解析测试URL
        val testUrl = try {
            URL(url)
        } catch (e: Exception) {
            // 如果URL不包含协议，默认添加https://
            URL(if (url.startsWith("http")) url else "https://$url")
        }
        
        // 获取主机名和路径
        val host = testUrl.host
        val path = testUrl.path.takeIf { it.isNotEmpty() } ?: "/__down?bytes=10000000"
        val query = testUrl.query?.let { "?$it" } ?: ""
        
        // 确定端口和协议
        val isHttps = testUrl.protocol == "https"
        val defaultPort = if (isHttps) 443 else 80
        val actualPort = if (port > 0) port else (testUrl.port.takeIf { it > 0 } ?: defaultPort)
        
        Log.d(TAG, "【测速开始】IP: $ip:$actualPort, 协议: ${testUrl.protocol}, Host: $host, Path: $path$query, 超时: ${timeoutMs}ms")
        
        try {
            if (isHttps) {
                return@withContext getDownloadSpeedHttps(ip, actualPort, host, path, query, timeoutMs, attemptDirectIpAccess)
            } else {
                return@withContext getDownloadSpeedHttp(ip, actualPort, host, path, query, timeoutMs)
            }
        } catch (e: Exception) {
            Log.e(TAG, "【测速失败】IP: $ip:$actualPort, 错误: ${e.message}", e)
            return@withContext 0.0
        }
    }
    
    /**
     * 使用HTTPS连接进行下载速度测试
     */
    private suspend fun getDownloadSpeedHttps(
        ip: String,
        port: Int,
        host: String,
        path: String,
        query: String,
        timeoutMs: Int,
        attemptDirectIpAccess: Boolean
    ): Double = withContext(Dispatchers.IO) {
        try {
            // 创建SSLSocketFactory
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, null, null)
            val sslSocketFactory = sslContext.socketFactory
            
            // 连接阶段
            val connectionStartTime = System.currentTimeMillis()
            Log.d(TAG, "【测速HTTPS】正在连接到 $ip:$port...")
            
            // 创建socket并连接
            val socket = sslSocketFactory.createSocket() as SSLSocket
            socket.connect(InetSocketAddress(ip, port), timeoutMs)
            socket.soTimeout = 3000 // 初始设置为3秒超时，确保快速判断无响应的IP
            
            // 设置SNI
            socket.sslParameters = socket.sslParameters.apply {
                serverNames = listOf(SNIHostName(host))
            }
            
            // 开始TLS握手
            socket.startHandshake()
            
            val connectionTime = System.currentTimeMillis() - connectionStartTime
            Log.d(TAG, "【测速HTTPS】SSL连接建立成功，耗时: ${connectionTime}ms，准备发送HTTP请求")
            
            // 记录开始时间
            val startTime = System.currentTimeMillis()
            
            // 构建HTTP请求 
            val httpRequest = "GET $path$query HTTP/1.1\r\n" +
                    "Host: $host\r\n" +
                    "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36\r\n" +
                    "Accept: */*\r\n" +
                    "Connection: close\r\n" +
                    "\r\n"
            
            // 发送请求
            val out = socket.getOutputStream()
            out.write(httpRequest.toByteArray())
            out.flush()
            Log.d(TAG, "【测速HTTPS】HTTP请求已发送，等待服务器响应...")
            
            // 读取响应
            val input = socket.getInputStream()
            val bufferReader = BufferedReader(InputStreamReader(input))
            
            // 读取HTTP头
            var isHeaderParsed = false
            val headerBuilder = StringBuilder()
            var statusCode = 0
            var contentLength = -1L
            
            // 读取并解析头部
            while (!isHeaderParsed && isActive) {
                val line = bufferReader.readLine() ?: break
                
                if (line.isEmpty()) {
                    isHeaderParsed = true // 头部结束
                } else {
                    headerBuilder.append(line).append("\r\n")
                    
                    // 解析状态码
                    if (line.startsWith("HTTP/")) {
                        val parts = line.split(" ", limit = 3)
                        if (parts.size >= 2) {
                            statusCode = parts[1].toIntOrNull() ?: 0
                        }
                    }
                    // 解析Content-Length
                    else if (line.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = line.substringAfter(":", "").trim().toLongOrNull() ?: -1L
                    }
                }
            }
            
            // 记录头部信息
            val headerContent = headerBuilder.toString()
            Log.d(TAG, "【测速HTTPS】IP: $ip, HTTP头部解析完成 (${headerContent.length}字节):\n$headerContent")
            
            // 检查状态码
            if (statusCode != 200) {
                Log.w(TAG, "【测速HTTPS】IP: $ip, 服务器返回非200状态码: $statusCode")
                
                if (statusCode >= 300 && statusCode < 400) {
                    // 处理重定向
                    val locationHeader = Regex("Location:\\s*(.+)", RegexOption.IGNORE_CASE).find(headerContent)
                    if (locationHeader != null) {
                        val redirectUrl = locationHeader.groupValues[1].trim()
                        Log.d(TAG, "【测速HTTPS】IP: $ip 收到重定向到: $redirectUrl")
                    }
                }
                
                socket.close()
                if (statusCode == 400 && attemptDirectIpAccess) {
                    // 失败后尝试用CloudFlare专用测速URL
                    Log.d(TAG, "【测速HTTPS】尝试使用CloudFlare专用测速URL")
                    return@withContext getDownloadSpeedWithCloudflare(ip, port, timeoutMs)
                }
                return@withContext 0.0
            }
            
            // 读取响应体
            var totalBytes = 0L
            val buffer = ByteArray(65536)
            val initialWaitTime = 3000 // 最初等待3秒，如果没有数据则失败
            val maxTestTime = 30000 // 一旦开始接收数据，最长测试30秒
            val minDownloadSize = 500000 // 至少下载500KB才计算速度
            var lastLogTime = startTime
            var lastBytesRead = 0L
            var dataReceived = false // 是否已经接收到数据
            
            // 如果已知内容长度，记录下来
            if (contentLength > 0) {
                Log.d(TAG, "【测速HTTPS】IP: $ip, 响应内容大小: $contentLength 字节")
            }
            
            // 读取响应体
            while (isActive && 
                  ((dataReceived && System.currentTimeMillis() - startTime < maxTestTime) || 
                   (!dataReceived && System.currentTimeMillis() - startTime < initialWaitTime)) && 
                  totalBytes < 10 * 1024 * 1024) {
                try {
                    val bytesRead = input.read(buffer)
                    if (bytesRead <= 0) {
                        Log.d(TAG, "【测速HTTPS】IP: $ip, 数据接收完成")
                        break
                    }
                    
                    if (!dataReceived) {
                        dataReceived = true // 标记已经开始接收数据
                        Log.d(TAG, "【测速HTTPS】IP: $ip, 开始接收数据，耗时: ${System.currentTimeMillis() - startTime}ms")
                        socket.soTimeout = 30000 // 接收到数据后延长超时时间
                    }
                    
                    totalBytes += bytesRead
                    
                    // 定期记录下载进度
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastLogTime > 1000) { 
                        val currentBytesRead = totalBytes
                        val bytesReadInInterval = currentBytesRead - lastBytesRead
                        val intervalSeconds = (currentTime - lastLogTime) / 1000.0
                        val currentSpeed = bytesReadInInterval / intervalSeconds / 1024.0
                        
                        if (bytesReadInInterval > 0) {
                            Log.d(TAG, "【测速HTTPS进度】IP: $ip, 已下载: ${totalBytes/1024} KB, 当前速度: $currentSpeed KB/s, 总耗时: ${(currentTime-startTime)/1000.0}秒")
                        }
                        
                        lastLogTime = currentTime
                        lastBytesRead = currentBytesRead
                    }
                } catch (e: SocketTimeoutException) {
                    if (!dataReceived) {
                        Log.e(TAG, "【测速HTTPS】IP: $ip, 连接成功但3秒内没有响应数据: ${e.message}")
                        break
                    } else {
                        Log.e(TAG, "【测速HTTPS】IP: $ip, 下载中断: ${e.message}")
                        break
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "【测速HTTPS】IP: $ip, 读取数据失败: ${e.message}")
                    break
                }
            }
            
            // 计算速度
            val durationMs = System.currentTimeMillis() - startTime
            val durationSeconds = durationMs / 1000.0
            
            // 确保测试结果有效性
            val speedInKBps = when {
                // 理想条件: 下载了足够数据且测试时间足够长
                durationSeconds >= 1.0 && totalBytes >= minDownloadSize -> {
                    val speed = totalBytes / durationSeconds / 1024.0
                    Log.i(TAG, "【测速HTTPS结果】IP: $ip, 有效测试 - 下载: ${totalBytes/1024}KB, 时间: ${durationSeconds}秒, 速度: $speed KB/s")
                    speed
                }
                
                // 次优条件: 虽然测试时间短但下载了一些数据
                totalBytes > 0 && durationSeconds > 0 -> {
                    val speed = totalBytes / durationSeconds / 1024.0
                    Log.w(TAG, "【测速HTTPS警告】IP: $ip, 测试条件不理想 - 下载: ${totalBytes/1024}KB, 时间: ${durationSeconds}秒, 速度: $speed KB/s")
                    speed
                }
                
                // 无效条件: 几乎没有数据或时间太短
                else -> {
                    Log.e(TAG, "【测速HTTPS失败】IP: $ip, 测试无效 - 下载: ${totalBytes/1024}KB, 时间: ${durationSeconds}秒")
                    0.0
                }
            }
                
            // 关闭连接
            try {
                socket.close()
            } catch (e: IOException) {
                // 忽略关闭异常
            }
            
            return@withContext speedInKBps
            
        } catch (e: Exception) {
            Log.e(TAG, "【测速HTTPS失败】IP: $ip:$port, 错误: ${e.message}", e)
            return@withContext 0.0
        }
    }
    
    /**
     * 使用HTTP连接进行下载速度测试
     */
    private suspend fun getDownloadSpeedHttp(
        ip: String,
        port: Int,
        host: String,
        path: String,
        query: String,
        timeoutMs: Int
    ): Double = withContext(Dispatchers.IO) {
        try {
            // 连接阶段
            val connectionStartTime = System.currentTimeMillis()
            Log.d(TAG, "【测速HTTP】正在连接到 $ip:$port...")
            
            // 创建socket并连接
            val socket = Socket()
            socket.connect(InetSocketAddress(ip, port), timeoutMs)
            socket.soTimeout = 3000 // 初始设置为3秒超时，确保快速判断无响应的IP
            
            val connectionTime = System.currentTimeMillis() - connectionStartTime
            Log.d(TAG, "【测速HTTP】连接建立成功，耗时: ${connectionTime}ms，准备发送HTTP请求")
            
            // 记录开始时间
            val startTime = System.currentTimeMillis()
            
            // 构建HTTP请求 
            val httpRequest = "GET $path$query HTTP/1.1\r\n" +
                    "Host: $host\r\n" +
                    "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36\r\n" +
                    "Accept: */*\r\n" +
                    "Connection: close\r\n" +
                    "\r\n"
            
            // 发送请求
            val out = socket.getOutputStream()
            out.write(httpRequest.toByteArray())
            out.flush()
            Log.d(TAG, "【测速HTTP】HTTP请求已发送，等待服务器响应...")
            
            // 读取响应
            val input = socket.getInputStream()
            val bufferReader = BufferedReader(InputStreamReader(input))
            
            // 读取HTTP头
            var isHeaderParsed = false
            val headerBuilder = StringBuilder()
            var statusCode = 0
            var contentLength = -1L
            
            // 读取并解析头部
            while (!isHeaderParsed && isActive) {
                val line = bufferReader.readLine() ?: break
                
                if (line.isEmpty()) {
                    isHeaderParsed = true // 头部结束
                } else {
                    headerBuilder.append(line).append("\r\n")
                    
                    // 解析状态码
                    if (line.startsWith("HTTP/")) {
                        val parts = line.split(" ", limit = 3)
                        if (parts.size >= 2) {
                            statusCode = parts[1].toIntOrNull() ?: 0
                        }
                    }
                    // 解析Content-Length
                    else if (line.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = line.substringAfter(":", "").trim().toLongOrNull() ?: -1L
                    }
                }
            }
            
            // 记录头部信息
            val headerContent = headerBuilder.toString()
            Log.d(TAG, "【测速HTTP】IP: $ip, HTTP头部解析完成 (${headerContent.length}字节):\n$headerContent")
            
            // 检查状态码
            if (statusCode != 200) {
                Log.w(TAG, "【测速HTTP】IP: $ip, 服务器返回非200状态码: $statusCode")
                
                if (statusCode >= 300 && statusCode < 400) {
                    // 处理重定向
                    val locationHeader = Regex("Location:\\s*(.+)", RegexOption.IGNORE_CASE).find(headerContent)
                    if (locationHeader != null) {
                        val redirectUrl = locationHeader.groupValues[1].trim()
                        Log.d(TAG, "【测速HTTP】IP: $ip 收到重定向到: $redirectUrl")
                    }
                }
                
                socket.close()
                return@withContext 0.0
            }
            
            // 读取响应体
            var totalBytes = 0L
            val buffer = ByteArray(65536)
            val initialWaitTime = 3000 // 最初等待3秒，如果没有数据则失败
            val maxTestTime = 30000 // 一旦开始接收数据，最长测试30秒
            val minDownloadSize = 500000 // 至少下载500KB才计算速度
            var lastLogTime = startTime
            var lastBytesRead = 0L
            var dataReceived = false // 是否已经接收到数据
            
            // 如果已知内容长度，记录下来
            if (contentLength > 0) {
                Log.d(TAG, "【测速HTTP】IP: $ip, 响应内容大小: $contentLength 字节")
            }
            
            // 读取响应体
            while (isActive && 
                  ((dataReceived && System.currentTimeMillis() - startTime < maxTestTime) || 
                   (!dataReceived && System.currentTimeMillis() - startTime < initialWaitTime)) && 
                  totalBytes < 10 * 1024 * 1024) {
                try {
                    val bytesRead = input.read(buffer)
                    if (bytesRead <= 0) {
                        Log.d(TAG, "【测速HTTP】IP: $ip, 数据接收完成")
                        break
                    }
                    
                    if (!dataReceived) {
                        dataReceived = true // 标记已经开始接收数据
                        Log.d(TAG, "【测速HTTP】IP: $ip, 开始接收数据，耗时: ${System.currentTimeMillis() - startTime}ms")
                        socket.soTimeout = 30000 // 接收到数据后延长超时时间
                    }
                    
                    totalBytes += bytesRead
                    
                    // 定期记录下载进度
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastLogTime > 1000) { 
                        val currentBytesRead = totalBytes
                        val bytesReadInInterval = currentBytesRead - lastBytesRead
                        val intervalSeconds = (currentTime - lastLogTime) / 1000.0
                        val currentSpeed = bytesReadInInterval / intervalSeconds / 1024.0
                        
                        if (bytesReadInInterval > 0) {
                            Log.d(TAG, "【测速HTTP进度】IP: $ip, 已下载: ${totalBytes/1024} KB, 当前速度: $currentSpeed KB/s, 总耗时: ${(currentTime-startTime)/1000.0}秒")
                        }
                        
                        lastLogTime = currentTime
                        lastBytesRead = currentBytesRead
                    }
                } catch (e: SocketTimeoutException) {
                    if (!dataReceived) {
                        Log.e(TAG, "【测速HTTP】IP: $ip, 连接成功但3秒内没有响应数据: ${e.message}")
                        break
                    } else {
                        Log.e(TAG, "【测速HTTP】IP: $ip, 下载中断: ${e.message}")
                        break
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "【测速HTTP】IP: $ip, 读取数据失败: ${e.message}")
                    break
                }
            }
            
            // 计算速度
            val durationMs = System.currentTimeMillis() - startTime
            val durationSeconds = durationMs / 1000.0
            
            // 确保测试结果有效性
            val speedInKBps = when {
                // 理想条件: 下载了足够数据且测试时间足够长
                durationSeconds >= 1.0 && totalBytes >= minDownloadSize -> {
                    val speed = totalBytes / durationSeconds / 1024.0
                    Log.i(TAG, "【测速HTTP结果】IP: $ip, 有效测试 - 下载: ${totalBytes/1024}KB, 时间: ${durationSeconds}秒, 速度: $speed KB/s")
                    speed
                }
                
                // 次优条件: 虽然测试时间短但下载了一些数据
                totalBytes > 0 && durationSeconds > 0 -> {
                    val speed = totalBytes / durationSeconds / 1024.0
                    Log.w(TAG, "【测速HTTP警告】IP: $ip, 测试条件不理想 - 下载: ${totalBytes/1024}KB, 时间: ${durationSeconds}秒, 速度: $speed KB/s")
                    speed
                }
                
                // 无效条件: 几乎没有数据或时间太短
                else -> {
                    Log.e(TAG, "【测速HTTP失败】IP: $ip, 测试无效 - 下载: ${totalBytes/1024}KB, 时间: ${durationSeconds}秒")
                    0.0
                }
            }
                
            // 关闭连接
            try {
                socket.close()
            } catch (e: IOException) {
                // 忽略关闭异常
            }
            
            return@withContext speedInKBps
            
        } catch (e: Exception) {
            Log.e(TAG, "【测速HTTP失败】IP: $ip:$port, 错误: ${e.message}", e)
            return@withContext 0.0
        }
    }
    
    /**
     * 使用CloudFlare专用测速接口进行测试
     */
    private suspend fun getDownloadSpeedWithCloudflare(
        ip: String,
        port: Int,
        timeoutMs: Int
    ): Double = withContext(Dispatchers.IO) {
        try {
            // 创建SSLSocketFactory
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, null, null)
            val sslSocketFactory = sslContext.socketFactory
            
            // 连接阶段
            val connectionStartTime = System.currentTimeMillis()
            Log.d(TAG, "【CF测速】正在连接到 $ip:$port...")
            
            // 创建socket并连接
            val socket = sslSocketFactory.createSocket() as SSLSocket
            socket.connect(InetSocketAddress(ip, port), timeoutMs)
            socket.soTimeout = 3000 // 初始设置为3秒超时，确保快速判断无响应的IP
            
            // 设置SNI - 使用Cloudflare测速域名
            socket.sslParameters = socket.sslParameters.apply {
                serverNames = listOf(SNIHostName("speed.cloudflare.com"))
            }
            
            // 开始TLS握手
            socket.startHandshake()
            
            val connectionTime = System.currentTimeMillis() - connectionStartTime
            Log.d(TAG, "【CF测速】SSL连接建立成功，耗时: ${connectionTime}ms，准备发送HTTP请求")
            
            // 记录开始时间
            val startTime = System.currentTimeMillis()
            
            // 使用Cloudflare专用测速接口
            val httpRequest = "GET /__down?bytes=10000000 HTTP/1.1\r\n" +
                    "Host: speed.cloudflare.com\r\n" +
                    "User-Agent: Mozilla/5.0\r\n" +
                    "Accept: */*\r\n" +
                    "Connection: close\r\n" +
                    "\r\n"
            
            // 发送请求
            val out = socket.getOutputStream()
            out.write(httpRequest.toByteArray())
            out.flush()
            Log.d(TAG, "【CF测速】HTTP请求已发送，等待服务器响应...")
            
            // 读取响应
            val input = socket.getInputStream()
            val bufferReader = BufferedReader(InputStreamReader(input))
            
            // 读取HTTP头
            var isHeaderParsed = false
            val headerBuilder = StringBuilder()
            var statusCode = 0
            
            // 读取并解析头部
            while (!isHeaderParsed && isActive) {
                val line = bufferReader.readLine() ?: break
                
                if (line.isEmpty()) {
                    isHeaderParsed = true // 头部结束
                } else {
                    headerBuilder.append(line).append("\r\n")
                    
                    // 解析状态码
                    if (line.startsWith("HTTP/")) {
                        val parts = line.split(" ", limit = 3)
                        if (parts.size >= 2) {
                            statusCode = parts[1].toIntOrNull() ?: 0
                        }
                    }
                }
            }
            
            // 记录头部信息
            val headerContent = headerBuilder.toString()
            Log.d(TAG, "【CF测速】IP: $ip, HTTP头部解析完成:\n$headerContent")
            
            // 检查状态码
            if (statusCode != 200) {
                Log.w(TAG, "【CF测速】IP: $ip, 服务器返回非200状态码: $statusCode")
                socket.close()
                return@withContext 0.0
            }
            
            // 读取响应体并测量速度
            var totalBytes = 0L
            val buffer = ByteArray(65536)
            val initialWaitTime = 3000 // 最初等待3秒，如果没有数据则失败
            val maxTestTime = 30000 // 一旦开始接收数据，最长测试30秒
            var dataReceived = false // 是否已经接收到数据
            
            val startReadTime = System.currentTimeMillis()
            while (isActive && 
                  ((dataReceived && System.currentTimeMillis() - startReadTime < maxTestTime) || 
                   (!dataReceived && System.currentTimeMillis() - startReadTime < initialWaitTime))) {
                try {
                    val bytesRead = input.read(buffer)
                    if (bytesRead <= 0) break
                    
                    if (!dataReceived) {
                        dataReceived = true // 标记已经开始接收数据
                        Log.d(TAG, "【CF测速】IP: $ip, 开始接收数据，耗时: ${System.currentTimeMillis() - startReadTime}ms")
                        socket.soTimeout = 30000 // 接收到数据后延长超时时间
                    }
                    
                    totalBytes += bytesRead
                    
                    // 每1MB记录一次
                    if (totalBytes % (1024 * 1024) < buffer.size) {
                        Log.d(TAG, "【CF测速】IP: $ip, 已下载: ${totalBytes/1024/1024} MB")
                    }
                } catch (e: SocketTimeoutException) {
                    if (!dataReceived) {
                        Log.e(TAG, "【CF测速】IP: $ip, 连接成功但3秒内没有响应数据: ${e.message}")
                        break
                    } else {
                        Log.e(TAG, "【CF测速】IP: $ip, 下载中断: ${e.message}")
                        break
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "【CF测速】IP: $ip, 读取数据失败: ${e.message}")
                    break
                }
            }
            
            // 计算速度
            val durationMs = System.currentTimeMillis() - startReadTime
            val durationSeconds = durationMs / 1000.0
            val speedInKBps = if (durationSeconds > 0) totalBytes / durationSeconds / 1024.0 else 0.0
            
            Log.i(TAG, "【CF测速】IP: $ip, 结果: 下载 ${totalBytes/1024}KB, 耗时 ${durationSeconds}秒, 速度 $speedInKBps KB/s")
            
            // 关闭连接
            try {
                socket.close()
            } catch (e: IOException) {
                // 忽略关闭异常
            }
            
            return@withContext speedInKBps
            
        } catch (e: Exception) {
            Log.e(TAG, "【CF测速】IP: $ip 测试失败: ${e.message}", e)
            return@withContext 0.0
        }
    }
}

/**
 * 注意：在测速过程中可能会看到以下日志：
 * "[Posix_connect Debug]Process com.yiguihai.tsocks :443"
 * "tagSocket(xxx) with statsTag=0xffffffff, statsUid=-1"
 * 
 * 这些日志是Android系统在建立网络连接时产生的正常日志：
 * 1. Posix_connect Debug - 表示应用正在建立TCP连接到443端口(HTTPS)，这是测速过程中的正常连接操作
 * 2. tagSocket - 是Android系统为网络流量统计而标记socket的日志，不影响应用功能
 */ 