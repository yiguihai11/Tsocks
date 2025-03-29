package com.yiguihai.tsocks.utils

import java.util.Locale
import kotlin.math.pow

/**
 * 字节和数据格式化工具类
 * 提供字节大小格式化和数据速率格式化功能
 */
object ByteUtils {
    // 基本单位
    private val SIZE_UNITS = arrayOf("B", "KB", "MB", "GB", "TB", "PB", "EB", "ZB", "YB", "BB")
    // 速度单位 - 常规最大到GB/s
    private val SPEED_UNITS = arrayOf("B/s", "KB/s", "MB/s", "GB/s")
    // 完整速度单位 - 支持从B/s到BB/s
    private val FULL_SPEED_UNITS = arrayOf("B/s", "KB/s", "MB/s", "GB/s", "TB/s", "PB/s", "EB/s", "ZB/s", "YB/s", "BB/s")

    /**
     * 通用的格式化方法，内部使用
     * @param bytes 要格式化的字节数
     * @param units 使用的单位数组
     * @return 格式化后的字符串
     */
    private fun formatWithUnits(bytes: Long, units: Array<String>): String {
        var value = bytes.toDouble()
        var idx = 0

        while (value >= 1024.0 && idx < units.size - 1) {
            value /= 1024.0
            idx++
        }

        return String.format(Locale.US, "%.2f %s", value, units[idx])
    }

    /**
     * 格式化字节为人类可读字符串
     * 支持从B到BB的单位（字节到千亿亿亿亿字节）
     * @param bytes 要格式化的字节数
     * @return 格式化后的字符串，如"1.25 MB"
     */
    fun formatBytes(bytes: Long): String {
        return formatWithUnits(bytes, SIZE_UNITS)
    }

    /**
     * 格式化速率（专用于网络速度）
     * 限制到常见单位，最大到GB/s
     * @param bytesPerSecond 每秒字节数
     * @return 格式化后的速率字符串，如"1.25 MB/s"
     */
    fun formatSpeed(bytesPerSecond: Long): String {
        return formatWithUnits(bytesPerSecond, SPEED_UNITS)
    }
    
    /**
     * 格式化字节大小并附加"/s"单位
     * 支持全范围单位，从B/s到BB/s
     * @param bytesPerSecond 每秒字节数
     * @return 格式化后的速率字符串，包含单位，如"1.25 MB/s"
     */
    fun formatBytesSpeed(bytesPerSecond: Long): String {
        return formatWithUnits(bytesPerSecond, FULL_SPEED_UNITS)
    }
} 