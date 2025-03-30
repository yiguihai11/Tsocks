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
                    else -> Color(0xFF007F0E) to "\"${primitive.asString}\""
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