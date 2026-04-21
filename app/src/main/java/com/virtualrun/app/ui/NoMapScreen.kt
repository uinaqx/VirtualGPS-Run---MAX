package com.virtualrun.app.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.maps.model.LatLng
import com.virtualrun.app.algorithm.TrajectoryUtils
import com.virtualrun.app.map.MapType

/**
 * 无地图模式界面
 * 适用于没有 Google Play 服务的手机
 */
@Composable
fun NoMapScreen(
    viewModel: MainViewModel,
    currentMapType: MapType,
    onMapTypeChange: (MapType) -> Unit
) {
    val context = LocalContext.current
    val routePoints by viewModel.routePoints.collectAsState()
    val isRunning by viewModel.isRunning.collectAsState()
    val basePace by viewModel.basePace.collectAsState()
    val progress by viewModel.progress.collectAsState()

    var showAddPointDialog by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // 标题
        Text(
            text = "虚拟跑步 - 坐标模式",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // 地图类型选择
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            shape = RoundedCornerShape(12.dp),
            elevation = CardDefaults.cardElevation(4.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "当前地图模式",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = when (currentMapType) {
                        MapType.OSM -> "OpenStreetMap（开源地图）"
                        MapType.GOOGLE -> "Google 地图（需要 Google Play）"
                        MapType.BAIDU -> "百度地图"
                        MapType.AMAP -> "高德地图"
                        MapType.NONE -> "无地图模式（坐标输入）"
                    },
                    color = Color.Gray
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "提示：您的手机没有 Google Play 服务，已自动切换到无地图模式",
                    fontSize = 12.sp,
                    color = Color(0xFFFF9800)
                )
            }
        }

        // 路线点列表
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(bottom = 16.dp),
            shape = RoundedCornerShape(12.dp),
            elevation = CardDefaults.cardElevation(4.dp)
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
                        text = "路线点 (${routePoints.size})",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Button(
                        onClick = { showAddPointDialog = true },
                        enabled = !isRunning
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "添加")
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("添加点")
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (routePoints.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "点击 [添加点] 按钮\n输入起点和终点的经纬度",
                            color = Color.Gray,
                            fontSize = 14.sp
                        )
                    }
                } else {
                    // 显示路线点
                    routePoints.forEachIndexed { index, point ->
                        RoutePointItem(
                            index = index,
                            point = point,
                            isFirst = index == 0,
                            isLast = index == routePoints.size - 1
                        )
                        if (index < routePoints.size - 1) {
                            Divider(modifier = Modifier.padding(vertical = 4.dp))
                        }
                    }
                }
            }
        }

        // 路线信息
        if (routePoints.size >= 2) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                shape = RoundedCornerShape(12.dp),
                elevation = CardDefaults.cardElevation(4.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "路线信息",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "距离：${String.format("%.2f", viewModel.getCurrentRouteDistance() / 1000)} km")
                        Text(text = "预计：${formatDuration(viewModel.getCurrentRouteDuration())}")
                    }
                    if (isRunning) {
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = progress,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = "进度：${String.format("%.1f", progress * 100)}%",
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }

        // 配速设置
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            shape = RoundedCornerShape(12.dp),
            elevation = CardDefaults.cardElevation(4.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "配速设置",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "${String.format("%.1f", basePace)} 分/km")
                    Slider(
                        value = basePace,
                        onValueChange = { viewModel.setBasePace(it) },
                        valueRange = 3f..15f,
                        steps = 11,
                        modifier = Modifier.weight(1f)
                    )
                    Text(text = "15 分/km")
                }
            }
        }

        // 控制按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(
                onClick = { viewModel.clearRoute() },
                enabled = !isRunning && routePoints.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Gray
                )
            ) {
                Icon(Icons.Default.Clear, contentDescription = "清除")
                Spacer(modifier = Modifier.width(4.dp))
                Text("清除")
            }

            Button(
                onClick = {
                    if (isRunning) {
                        viewModel.stopRunning(context)
                    } else {
                        if (routePoints.size >= 2) {
                            viewModel.startRunning(context)
                        } else {
                            errorMessage = "请至少添加两个点（起点和终点）"
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRunning) Color.Red else Color(0xFF4CAF50)
                ),
                modifier = Modifier.size(height = 56.dp, width = 120.dp)
            ) {
                Icon(
                    if (isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = if (isRunning) "停止" else "开始"
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (isRunning) "停止" else "开始", fontSize = 18.sp)
            }
        }
    }

    // 添加点对话框
    if (showAddPointDialog) {
        AddPointDialog(
            pointIndex = routePoints.size,
            onDismiss = { showAddPointDialog = false },
            onConfirm = { lat, lng ->
                viewModel.addRoutePoint(LatLng(lat, lng))
                showAddPointDialog = false
            }
        )
    }

    // 错误对话框
    errorMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { errorMessage = null },
            title = { Text("提示") },
            text = { Text(msg) },
            confirmButton = {
                Button(onClick = { errorMessage = null }) {
                    Text("确定")
                }
            }
        )
    }
}

@Composable
fun RoutePointItem(
    index: Int,
    point: LatLng,
    isFirst: Boolean,
    isLast: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = when {
                isFirst -> Icons.Default.LocationOn
                isLast -> Icons.Default.Flag
                else -> Icons.Default.Circle
            },
            contentDescription = null,
            tint = when {
                isFirst -> Color.Green
                isLast -> Color.Red
                else -> Color.Blue
            }
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(
                text = when {
                    isFirst -> "起点"
                    isLast -> "终点"
                    else -> "途经点 ${index}"
                },
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            Text(
                text = "${String.format("%.6f", point.latitude)}, ${String.format("%.6f", point.longitude)}",
                fontSize = 12.sp,
                color = Color.Gray
            )
        }
    }
}

@Composable
fun AddPointDialog(
    pointIndex: Int,
    onDismiss: () -> Unit,
    onConfirm: (Double, Double) -> Unit
) {
    var latText by remember { mutableStateOf("") }
    var lngText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    val title = when (pointIndex) {
        0 -> "添加起点"
        1 -> "添加终点"
        else -> "添加途经点 ${pointIndex}"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(
                    text = "请输入经纬度坐标（小数形式）",
                    fontSize = 14.sp,
                    color = Color.Gray,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                OutlinedTextField(
                    value = latText,
                    onValueChange = { latText = it },
                    label = { Text("纬度 (Latitude)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = lngText,
                    onValueChange = { lngText = it },
                    label = { Text("经度 (Longitude)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                error?.let {
                    Text(
                        text = it,
                        color = Color.Red,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val lat = latText.toDoubleOrNull()
                    val lng = lngText.toDoubleOrNull()

                    when {
                        lat == null || lng == null -> {
                            error = "请输入有效的数字"
                        }
                        lat < -90 || lat > 90 -> {
                            error = "纬度范围：-90 ~ 90"
                        }
                        lng < -180 || lng > 180 -> {
                            error = "经度范围：-180 ~ 180"
                        }
                        else -> {
                            onConfirm(lat, lng)
                        }
                    }
                }
            ) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
