package com.yiguihai.tsocks.utils

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 原生程序执行器
 * 负责执行APK中打包的原生程序(.so)
 */
class NativeProgramExecutor(context: Context) {

    companion object {
        private const val TAG = "NativeProgramExecutor"
        private const val LIB_SSLOCAL = "libsslocal.so"
    }

    // 原生库目录
    private val nativeLibDir = context.applicationInfo.nativeLibraryDir

    /**
     * 执行原生程序
     * @param libName 程序文件名（必须以.so结尾）
     * @param args 程序参数
     * @return 执行结果
     */
    fun executeNativeProgram(libName: String, vararg args: String): ExecutionResult {
        val execFile = File(nativeLibDir, libName)

        // 检查文件存在性
        if (!execFile.exists()) {
            Log.e(TAG, "找不到 $libName")
            return ExecutionResult("", -1, false, "文件不存在")
        }

        // 确保执行权限
        if (!execFile.canExecute()) {
            execFile.setExecutable(true).also { if (!it) return ExecutionResult("", -1, false, "无执行权限") }
            Log.d(TAG, "已授予 $libName 执行权限")
        }

        Log.d(TAG, "执行: ${execFile.absolutePath} ${args.joinToString(" ")}")

        // 执行命令并处理结果
        return runCatching {
            val process = ProcessBuilder(listOf(execFile.absolutePath, *args))
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()
            val success = (exitCode == 0)

            Log.d(TAG, "结果 - 成功: $success, 退出码: $exitCode, 输出长度: ${output.length}")
            ExecutionResult(output.trim(), exitCode, success, if (success) "" else "执行失败，退出码: $exitCode")
        }.getOrElse { e ->
            Log.e(TAG, "执行出错", e)
            ExecutionResult("", -1, false, e.message ?: "未知错误")
        }
    }

    /**
     * 获取支持的加密方法列表
     * @return 加密方法列表，失败则返回默认值
     */
    fun getSupportedEncryptMethods(): List<String> {
        val result = executeNativeProgram(LIB_SSLOCAL, "-h")
        if (!result.success) {
            Log.e(TAG, "获取加密方法失败: ${result.errorMessage}")
            return defaultEncryptMethods
        }

        val methodPattern = Regex("encrypt-method.*?possible values: ([^]]+)]", RegexOption.DOT_MATCHES_ALL)
        return methodPattern.find(result.output)?.groupValues?.get(1)?.let { methods ->
            methods.replace("\n", "").split(", ").map { it.trim() }.filter { it.isNotEmpty() }
                .also { Log.i(TAG, "获取到 ${it.size} 种加密方法: ${it.joinToString()}") }
        } ?: run {
            Log.w(TAG, "未找到加密方法，使用默认值")
            defaultEncryptMethods
        }
    }

    /**
     * 获取支持的协议列表
     * @return 协议列表，失败则返回默认值
     */
    fun getSupportedProtocols(): List<String> {
        val result = executeNativeProgram(LIB_SSLOCAL, "-h")
        if (!result.success) {
            Log.e(TAG, "获取协议失败: ${result.errorMessage}")
            return defaultProtocols
        }

        val protocolPattern = Regex("--protocol.*?possible values: ([^]]+)]", RegexOption.DOT_MATCHES_ALL)
        return protocolPattern.find(result.output)?.groupValues?.get(1)?.let { protocols ->
            protocols.replace("\n", "").split(", ").map { it.trim() }.filter { it.isNotEmpty() }
                .also { Log.i(TAG, "获取到 ${it.size} 种协议: ${it.joinToString()}") }
        } ?: run {
            Log.w(TAG, "未找到协议，使用默认值")
            defaultProtocols
        }
    }

    /**
     * 获取版本信息
     * @return 版本字符串
     */
    fun getVersion(): String {
        val result = executeNativeProgram(LIB_SSLOCAL, "-V")
        return if (result.success) result.output.also { Log.i(TAG, "版本: $it") } else "未知版本".also { Log.e(TAG, "获取版本失败: ${result.errorMessage}") }
    }

    // 默认值
    private val defaultEncryptMethods = listOf("aes-256-gcm", "aes-128-gcm", "chacha20-ietf-poly1305")
    private val defaultProtocols = listOf("socks", "http")

    /**
     * 执行结果类
     */
    data class ExecutionResult(
        val output: String,
        val exitCode: Int,
        val success: Boolean,
        val errorMessage: String
    )
}