package com.yiguihai.tsocks.utils

import android.content.Context
import android.util.Log
import com.maxmind.db.CHMCache
import com.maxmind.geoip2.DatabaseReader
import com.maxmind.geoip2.exception.AddressNotFoundException
import com.murgupluoglu.flagkit.FlagKit
import com.yiguihai.tsocks.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetAddress
import java.util.Locale

/**
 * GeoIP工具类，用于查询IP地址所属国家
 */
object GeoIpUtils {
    private const val TAG = "GeoIpUtils"
    private const val DB_FILENAME = "GeoLite2-Country.mmdb"
    private var databaseReader: DatabaseReader? = null
    
    // 国家代码到国旗emoji映射
    private val countryCodeToFlagEmoji = mapOf(
        "AD" to "🇦🇩", "AE" to "🇦🇪", "AF" to "🇦🇫", "AG" to "🇦🇬", "AI" to "🇦🇮", "AL" to "🇦🇱", "AM" to "🇦🇲",
        "AO" to "🇦🇴", "AR" to "🇦🇷", "AS" to "🇦🇸", "AT" to "🇦🇹", "AU" to "🇦🇺", "AW" to "🇦🇼", "AX" to "🇦🇽",
        "AZ" to "🇦🇿", "BA" to "🇧🇦", "BB" to "🇧🇧", "BD" to "🇧🇩", "BE" to "🇧🇪", "BF" to "🇧🇫", "BG" to "🇧🇬",
        "BH" to "🇧🇭", "BI" to "🇧🇮", "BJ" to "🇧🇯", "BL" to "🇧🇱", "BM" to "🇧🇲", "BN" to "🇧🇳", "BO" to "🇧🇴",
        "BQ" to "🇧🇶", "BR" to "🇧🇷", "BS" to "🇧🇸", "BT" to "🇧🇹", "BV" to "🇧🇻", "BW" to "🇧🇼", "BY" to "🇧🇾",
        "BZ" to "🇧🇿", "CA" to "🇨🇦", "CC" to "🇨🇨", "CD" to "🇨🇩", "CF" to "🇨🇫", "CG" to "🇨🇬", "CH" to "🇨🇭",
        "CI" to "🇨🇮", "CK" to "🇨🇰", "CL" to "🇨🇱", "CM" to "🇨🇲", "CN" to "🇨🇳", "CO" to "🇨🇴", "CR" to "🇨🇷",
        "CU" to "🇨🇺", "CV" to "🇨🇻", "CW" to "🇨🇼", "CX" to "🇨🇽", "CY" to "🇨🇾", "CZ" to "🇨🇿", "DE" to "🇩🇪",
        "DJ" to "🇩🇯", "DK" to "🇩🇰", "DM" to "🇩🇲", "DO" to "🇩🇴", "DZ" to "🇩🇿", "EC" to "🇪🇨", "EE" to "🇪🇪",
        "EG" to "🇪🇬", "EH" to "🇪🇭", "ER" to "🇪🇷", "ES" to "🇪🇸", "ET" to "🇪🇹", "FI" to "🇫🇮", "FJ" to "🇫🇯",
        "FK" to "🇫🇰", "FM" to "🇫🇲", "FO" to "🇫🇴", "FR" to "🇫🇷", "GA" to "🇬🇦", "GB" to "🇬🇧", "GD" to "🇬🇩",
        "GE" to "🇬🇪", "GF" to "🇬🇫", "GG" to "🇬🇬", "GH" to "🇬🇭", "GI" to "🇬🇮", "GL" to "🇬🇱", "GM" to "🇬🇲",
        "GN" to "🇬🇳", "GP" to "🇬🇵", "GQ" to "🇬🇶", "GR" to "🇬🇷", "GS" to "🇬🇸", "GT" to "🇬🇹", "GU" to "🇬🇺",
        "GW" to "🇬🇼", "GY" to "🇬🇾", "HK" to "🇭🇰", "HM" to "🇭🇲", "HN" to "🇭🇳", "HR" to "🇭🇷", "HT" to "🇭🇹",
        "HU" to "🇭🇺", "ID" to "🇮🇩", "IE" to "🇮🇪", "IL" to "🇮🇱", "IM" to "🇮🇲", "IN" to "🇮🇳", "IO" to "🇮🇴",
        "IQ" to "🇮🇶", "IR" to "🇮🇷", "IS" to "🇮🇸", "IT" to "🇮🇹", "JE" to "🇯🇪", "JM" to "🇯🇲", "JO" to "🇯🇴",
        "JP" to "🇯🇵", "KE" to "🇰🇪", "KG" to "🇰🇬", "KH" to "🇰🇭", "KI" to "🇰🇮", "KM" to "🇰🇲", "KN" to "🇰🇳",
        "KP" to "🇰🇵", "KR" to "🇰🇷", "KW" to "🇰🇼", "KY" to "🇰🇾", "KZ" to "🇰🇿", "LA" to "🇱🇦", "LB" to "🇱🇧",
        "LC" to "🇱🇨", "LI" to "🇱🇮", "LK" to "🇱🇰", "LR" to "🇱🇷", "LS" to "🇱🇸", "LT" to "🇱🇹", "LU" to "🇱🇺",
        "LV" to "🇱🇻", "LY" to "🇱🇾", "MA" to "🇲🇦", "MC" to "🇲🇨", "MD" to "🇲🇩", "ME" to "🇲🇪", "MF" to "🇲🇫",
        "MG" to "🇲🇬", "MH" to "🇲🇭", "MK" to "🇲🇰", "ML" to "🇲🇱", "MM" to "🇲🇲", "MN" to "🇲🇳", "MO" to "🇲🇴",
        "MP" to "🇲🇵", "MQ" to "🇲🇶", "MR" to "🇲🇷", "MS" to "🇲🇸", "MT" to "🇲🇹", "MU" to "🇲🇺", "MV" to "🇲🇻",
        "MW" to "🇲🇼", "MX" to "🇲🇽", "MY" to "🇲🇾", "MZ" to "🇲🇿", "NA" to "🇳🇦", "NC" to "🇳🇨", "NE" to "🇳🇪",
        "NF" to "🇳🇫", "NG" to "🇳🇬", "NI" to "🇳🇮", "NL" to "🇳🇱", "NO" to "🇳🇴", "NP" to "🇳🇵", "NR" to "🇳🇷",
        "NU" to "🇳🇺", "NZ" to "🇳🇿", "OM" to "🇴🇲", "PA" to "🇵🇦", "PE" to "🇵🇪", "PF" to "🇵🇫", "PG" to "🇵🇬",
        "PH" to "🇵🇭", "PK" to "🇵🇰", "PL" to "🇵🇱", "PM" to "🇵🇲", "PN" to "🇵🇳", "PR" to "🇵🇷", "PS" to "🇵🇸",
        "PT" to "🇵🇹", "PW" to "🇵🇼", "PY" to "🇵🇾", "QA" to "🇶🇦", "RE" to "🇷🇪", "RO" to "🇷🇴", "RS" to "🇷🇸",
        "RU" to "🇷🇺", "RW" to "🇷🇼", "SA" to "🇸🇦", "SB" to "🇸🇧", "SC" to "🇸🇨", "SD" to "🇸🇩", "SE" to "🇸🇪",
        "SG" to "🇸🇬", "SH" to "🇸🇭", "SI" to "🇸🇮", "SJ" to "🇸🇯", "SK" to "🇸🇰", "SL" to "🇸🇱", "SM" to "🇸🇲",
        "SN" to "🇸🇳", "SO" to "🇸🇴", "SR" to "🇸🇷", "SS" to "🇸🇸", "ST" to "🇸🇹", "SV" to "🇸🇻", "SX" to "🇸🇽",
        "SY" to "🇸🇾", "SZ" to "🇸🇿", "TC" to "🇹🇨", "TD" to "🇹🇩", "TF" to "🇹🇫", "TG" to "🇹🇬", "TH" to "🇹🇭",
        "TJ" to "🇹🇯", "TK" to "🇹🇰", "TL" to "🇹🇱", "TM" to "🇹🇲", "TN" to "🇹🇳", "TO" to "🇹🇴", "TR" to "🇹🇷",
        "TT" to "🇹🇹", "TV" to "🇹🇻", "TW" to "🇹🇼", "TZ" to "🇹🇿", "UA" to "🇺🇦", "UG" to "🇺🇬", "US" to "🇺🇸",
        "UY" to "🇺🇾", "UZ" to "🇺🇿", "VA" to "🇻🇦", "VC" to "🇻🇨", "VE" to "🇻🇪", "VG" to "🇻🇬", "VI" to "🇻🇮",
        "VN" to "🇻🇳", "VU" to "🇻🇺", "WF" to "🇼🇫", "WS" to "🇼🇸", "YE" to "🇾🇪", "YT" to "🇾🇹", "ZA" to "🇿🇦",
        "ZM" to "🇿🇲", "ZW" to "🇿🇼"
    )
    
    // 特殊网络标识的映射
    private fun getSpecialNetworksLabels(context: Context) = mapOf(
        "LAN" to context.getString(R.string.lan),
        "本地" to context.getString(R.string.local),
        "特殊" to context.getString(R.string.special),
        "APIPA" to context.getString(R.string.auto_ip),
        "组播" to context.getString(R.string.multicast),
        "保留" to context.getString(R.string.reserved),
        "广播" to context.getString(R.string.broadcast)
    )
    
    // 特殊IP地址的标识
    private val specialNetworks = mapOf(
        "10.0.0.0/8" to "LAN",     // RFC1918 私有地址
        "172.16.0.0/12" to "LAN",  // RFC1918 私有地址
        "192.168.0.0/16" to "LAN", // RFC1918 私有地址
        "127.0.0.0/8" to "本地",    // 本地回环地址
        "0.0.0.0/8" to "特殊",      // 本网络
        "169.254.0.0/16" to "APIPA", // 自动私有IP地址
        "224.0.0.0/4" to "组播",     // 组播地址
        "240.0.0.0/4" to "保留",     // 保留地址
        "255.255.255.255/32" to "广播" // 广播地址
    )
    
    /**
     * 初始化GeoIP数据库
     * @param context 上下文
     * @return 是否初始化成功
     */
    suspend fun initialize(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            // 检查数据库文件是否存在
            val dbFile = File(context.filesDir, DB_FILENAME)
            
            // 如果数据库文件不存在，从assets复制
            if (!dbFile.exists()) {
                context.assets.open(DB_FILENAME).use { input ->
                    FileOutputStream(dbFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }
            
            // 初始化数据库读取器
            databaseReader = DatabaseReader.Builder(dbFile)
                .withCache(CHMCache())
                .build()
            
            true
        } catch (e: IOException) {
            Log.e(TAG, context.getString(R.string.geoip_init_failed), e)
            false
        }
    }
    
    /**
     * 获取IP地址的国家代码
     * @param ipAddress IP地址字符串
     * @return 国家代码，如果无法获取则返回null
     */
    private suspend fun getCountryCode(ipAddress: String, context: Context): String? = withContext(Dispatchers.IO) {
        try {
            // 检查是否为特殊网络地址
            for ((network, _) in specialNetworks) {
                val (prefix, maskLength) = network.split("/")
                if (isInNetwork(ipAddress, prefix, maskLength.toInt())) {
                    return@withContext null
                }
            }
            
            // 查询IP地址
            val address = InetAddress.getByName(ipAddress)
            val response = databaseReader?.country(address)
            response?.country?.isoCode
        } catch (e: AddressNotFoundException) {
            // IP地址不在数据库中
            null
        } catch (e: Exception) {
            Log.e(TAG, context.getString(R.string.get_country_code_failed, ipAddress), e)
            null
        }
    }
    
    /**
     * 获取IP地址对应的国旗资源ID
     * @param context 上下文
     * @param ipAddress IP地址
     * @return 国旗图标资源ID，如果无法获取则返回null
     */
    suspend fun getCountryFlagResId(context: Context, ipAddress: String): Int? = withContext(Dispatchers.IO) {
        try {
            // 检查是否为特殊网络地址
            for ((network, _) in specialNetworks) {
                val (prefix, maskLength) = network.split("/")
                if (isInNetwork(ipAddress, prefix, maskLength.toInt())) {
                    // 特殊网络地址返回null，由调用方处理显示特殊标识
                    return@withContext null
                }
            }
            
            // 获取国家代码
            val countryCode = getCountryCode(ipAddress, context) ?: return@withContext null
            
            // 转换为小写以匹配FlagKit的格式
            val countryCodeLower = countryCode.lowercase(Locale.ROOT)
            
            // 获取国旗资源ID
            FlagKit.getResId(context, countryCodeLower)
        } catch (e: Exception) {
            Log.e(TAG, context.getString(R.string.get_country_code_failed, ipAddress), e)
            null
        }
    }
    
    /**
     * 获取IP地址的网络类型标识
     * @param ipAddress IP地址
     * @return 网络类型标识，如果不是特殊网络则返回null
     */
    fun getNetworkType(ipAddress: String): String? {
        for ((network, label) in specialNetworks) {
            val (prefix, maskLength) = network.split("/")
            if (isInNetwork(ipAddress, prefix, maskLength.toInt())) {
                return label
            }
        }
        return null
    }
    
    /**
     * 获取IP地址的国家名称
     * @param ipAddress IP地址字符串
     * @param locale 语言环境，默认为当前系统语言
     * @return 国家名称，如果无法获取则返回null
     */
    suspend fun getCountryName(ipAddress: String, context: Context, locale: Locale = Locale.getDefault()): String? = withContext(Dispatchers.IO) {
        try {
            // 检查是否为特殊网络地址
            for ((network, label) in specialNetworks) {
                val (prefix, maskLength) = network.split("/")
                if (isInNetwork(ipAddress, prefix, maskLength.toInt())) {
                    return@withContext getSpecialNetworksLabels(context)[label] ?: label
                }
            }
            
            // 查询IP地址
            val address = InetAddress.getByName(ipAddress)
            val response = databaseReader?.country(address)
            response?.country?.names?.get(locale.language)
        } catch (e: AddressNotFoundException) {
            // IP地址不在数据库中
            null
        } catch (e: Exception) {
            Log.e(TAG, context.getString(R.string.get_country_name_failed, ipAddress), e)
            null
        }
    }
    
    /**
     * 判断IP地址是否在指定网络中
     * @param ip IP地址
     * @param networkPrefix 网络前缀
     * @param maskLength 掩码长度
     * @return 是否在网络中
     */
    private fun isInNetwork(ip: String, networkPrefix: String, maskLength: Int): Boolean {
        try {
            val addr = ipToLong(ip)
            val mask = (-1L shl (32 - maskLength))
            val net = ipToLong(networkPrefix)
            
            return (addr and mask) == (net and mask)
        } catch (e: Exception) {
            return false
        }
    }
    
    /**
     * 将IP地址转换为长整型
     * @param ip IP地址字符串
     * @return 长整型表示的IP地址
     */
    private fun ipToLong(ip: String): Long {
        val parts = ip.split(".")
        return ((parts[0].toLong() shl 24) +
                (parts[1].toLong() shl 16) +
                (parts[2].toLong() shl 8) +
                parts[3].toLong())
    }
    
    /**
     * 释放资源
     * 在不再需要GeoIP功能时调用
     */
    fun release() {
        databaseReader = null
    }
} 