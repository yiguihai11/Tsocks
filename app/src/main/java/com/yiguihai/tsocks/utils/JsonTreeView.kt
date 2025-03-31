package com.yiguihai.tsocks.utils

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.animateFloatAsState
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.yiguihai.tsocks.R

/**
 * 自定义JSON可视化组件，支持树形展开/折叠
 */
@Composable
fun JsonTreeView(
    jsonString: String,
    modifier: Modifier = Modifier,
    onError: ((String) -> Unit)? = null
) {
    val lazyListState = rememberLazyListState()
    val context = LocalContext.current
    
    // 在Composable函数外部解析JSON
    val parseResult = remember(jsonString) {
        try {
            JsonParser.parseString(jsonString).let { Result.success(it) }
        } catch (e: Exception) {
            onError?.invoke(e.message ?: context.getString(R.string.json_parse_error))
            Result.failure(e)
        }
    }
    
    when {
        parseResult.isSuccess -> {
            val jsonElement = parseResult.getOrNull()!!
            LazyColumn(
                state = lazyListState,
                modifier = modifier.fillMaxSize()
            ) {
                item {
                    JsonTreeNodeView(
                        element = jsonElement, 
                        key = "root", 
                        level = 0
                    )
                }
            }
        }
        parseResult.isFailure -> {
            // 显示JSON解析错误
            Text(
                text = stringResource(
                    R.string.json_parse_error, 
                    parseResult.exceptionOrNull()?.message ?: ""
                ),
                color = Color.Red,
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}

@Composable
private fun JsonTreeNodeView(
    element: JsonElement,
    key: String,
    level: Int
) {
    val initialExpanded = level < 2 // 默认展开前两级
    var isExpanded by remember { mutableStateOf(initialExpanded) }
    val rotationDegree by animateFloatAsState(targetValue = if (isExpanded) 90f else 0f)
    
    val indentation = 16.dp * level
    
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { isExpanded = !isExpanded }
                .padding(start = indentation, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (element.isJsonObject || element.isJsonArray) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = if (isExpanded) stringResource(R.string.collapse) else stringResource(R.string.expand),
                    modifier = Modifier.rotate(rotationDegree),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(4.dp))
            } else {
                Spacer(modifier = Modifier.width(24.dp))
            }
            
            JsonNodeLabel(element, key)
        }
        
        if (isExpanded) {
            when {
                element.isJsonObject -> {
                    val entries = element.asJsonObject.entrySet().toList()
                    entries.forEachIndexed { _, entry ->
                        JsonTreeNodeView(
                            element = entry.value,
                            key = entry.key,
                            level = level + 1
                        )
                    }
                }
                element.isJsonArray -> {
                    val jsonArray = element.asJsonArray
                    jsonArray.forEachIndexed { index, item ->
                        JsonTreeNodeView(
                            element = item,
                            key = "[$index]",
                            level = level + 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun JsonNodeLabel(element: JsonElement, key: String) {
    val text = buildAnnotatedString {
        if (key != "root") {
            withStyle(SpanStyle(
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )) {
                append("\"$key\"")
            }
            
            // 添加冒号分隔符，根据key的格式调整空格
            append(if (element.isJsonObject || element.isJsonArray) ": " else " : ")
        }
        
        when {
            element.isJsonObject -> {
                withStyle(SpanStyle(color = MaterialTheme.colorScheme.tertiary)) {
                    append("{ ... }")
                }
                val size = element.asJsonObject.size()
                withStyle(SpanStyle(color = Color.Gray, fontSize = 12.sp)) {
                    append(" $size ${stringResource(R.string.properties)}")
                }
            }
            element.isJsonArray -> {
                withStyle(SpanStyle(color = MaterialTheme.colorScheme.tertiary)) {
                    append("[ ... ]")
                }
                val size = element.asJsonArray.size()
                withStyle(SpanStyle(color = Color.Gray, fontSize = 12.sp)) {
                    append(" $size ${stringResource(R.string.items)}")
                }
            }
            element.isJsonNull -> {
                withStyle(SpanStyle(color = Color.Gray)) {
                    append("null")
                }
            }
            element.isJsonPrimitive -> {
                val primitive = element.asJsonPrimitive
                val (color, text) = when {
                    primitive.isBoolean -> Color(0xFF8F6B32) to primitive.asBoolean.toString()
                    primitive.isNumber -> Color(0xFF1C78D6) to primitive.asNumber.toString()
                    else -> {
                        // 对字符串处理转义字符，使其更易读
                        val str = primitive.asString
                        
                        // 使用StringBuilder手动处理每个字符，避免使用有问题的转义序列
                        val unescapedStr = StringBuilder()
                        var i = 0
                        while (i < str.length) {
                            if (str[i] == '\\' && i + 1 < str.length) {
                                when (str[i + 1]) {
                                    '\"' -> unescapedStr.append('\"')
                                    '\\' -> unescapedStr.append('\\')
                                    'n' -> unescapedStr.append('\n')
                                    'r' -> unescapedStr.append('\r')
                                    't' -> unescapedStr.append('\t')
                                    '/' -> unescapedStr.append('/')
                                    'b' -> unescapedStr.append('\b')
                                    'f' -> unescapedStr.append('\u000C') // 换页符
                                    'u' -> {
                                        // Unicode转义序列 \uXXXX
                                        if (i + 5 < str.length) {
                                            try {
                                                val hexValue = str.substring(i + 2, i + 6).toInt(16)
                                                unescapedStr.append(hexValue.toChar())
                                                i += 4 // 跳过这4个十六进制字符
                                            } catch (e: Exception) {
                                                // 如果解析失败，保留原样
                                                unescapedStr.append("\\u")
                                            }
                                        } else {
                                            // 不完整的Unicode序列，保留原样
                                            unescapedStr.append("\\u")
                                        }
                                    }
                                    else -> {
                                        // 未知的转义序列，保留反斜杠
                                        unescapedStr.append('\\')
                                        unescapedStr.append(str[i + 1])
                                    }
                                }
                                i += 2 // 跳过转义序列的两个字符
                            } else {
                                unescapedStr.append(str[i])
                                i++
                            }
                        }
                        
                        // 处理最终字符串中的任何剩余Unicode转义序列
                        // 例如，在JSON解析过程中可能没有被正确解析的\u003c等格式
                        val finalString = unescapedStr.toString()
                        val unicodePattern = Regex("\\\\u([0-9a-fA-F]{4})")
                        val fullyUnescapedStr = unicodePattern.replace(finalString) { matchResult ->
                            try {
                                val hexValue = matchResult.groupValues[1].toInt(16)
                                hexValue.toChar().toString()
                            } catch (e: Exception) {
                                matchResult.value // 保留原样
                            }
                        }
                        
                        Color(0xFF007F0E) to "\"${fullyUnescapedStr}\""
                    }
                }
                withStyle(SpanStyle(color = color)) {
                    append(text)
                }
            }
        }
    }
    
    Text(
        text = text,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.padding(vertical = 2.dp)
    )
} 