package com.virtualrun.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.android.gms.maps.model.LatLng
import com.virtualrun.app.map.ChinaMapTileSource
import com.virtualrun.app.map.CoordinateConverter
import com.virtualrun.app.service.MockLocationService
import kotlinx.coroutines.launch
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.FolderOverlay
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OSMapScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    val routePoints by viewModel.routePoints.collectAsState()
    val isRunning by viewModel.isRunning.collectAsState()
    val basePace by viewModel.basePace.collectAsState()
    val currentLocation by viewModel.currentLocation.collectAsState()
    val progress by viewModel.progress.collectAsState()
    val lapCount by viewModel.lapCount.collectAsState()
    val isLoopMode by viewModel.isLoopMode.collectAsState()
    val currentSpeed by viewModel.speed.collectAsState()

    var mapView by remember { mutableStateOf<MapView?>(null) }
    // Slider 本地状态，拖动时更新 UI，释放时才发送到服务
    var sliderPace by remember(basePace) { mutableFloatStateOf(basePace) }
    var currentMarker by remember { mutableStateOf<Marker?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView?.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView?.onPause()
                Lifecycle.Event.ON_DESTROY -> mapView?.onDetach()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(routePoints) {
        val mv = mapView ?: return@LaunchedEffect
        mv.overlays.removeAll(mv.overlays.filter { it is Polyline || it is FolderOverlay })

        if (routePoints.size >= 2) {
            val polyline = Polyline(mv).apply {
                routePoints.forEach { addPoint(GeoPoint(it.latitude, it.longitude)) }
                outlinePaint.apply {
                    color = android.graphics.Color.BLUE
                    strokeWidth = 10f
                }
            }
            mv.overlays.add(polyline)
        }

        val folder = FolderOverlay()
        routePoints.forEachIndexed { index, point ->
            val marker = Marker(mv).apply {
                position = GeoPoint(point.latitude, point.longitude)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                // 不显示 info window，避免弹窗干扰
                infoWindow = null
                // 起点用绿色大圆点，其他用默认标记
                icon = if (index == 0) {
                    val d = android.graphics.drawable.GradientDrawable()
                    d.shape = android.graphics.drawable.GradientDrawable.OVAL
                    d.setColor(android.graphics.Color.parseColor("#4CAF50"))
                    d.setStroke(5, android.graphics.Color.WHITE)
                    d.setSize(48, 48)
                    d
                } else {
                    val d = android.graphics.drawable.GradientDrawable()
                    d.shape = android.graphics.drawable.GradientDrawable.OVAL
                    d.setColor(android.graphics.Color.parseColor("#2196F3"))
                    d.setStroke(3, android.graphics.Color.WHITE)
                    d.setSize(32, 32)
                    d
                }
                // 点击起点标记直接闭环，点击其他标记无反应
                setOnMarkerClickListener { _, _ ->
                    if (index == 0 && !isRunning && routePoints.size >= 2) {
                        viewModel.closeLoop()
                        true
                    } else {
                        false
                    }
                }
            }
            folder.add(marker)
        }
        mv.overlays.add(folder)
        mv.invalidate()
    }

    LaunchedEffect(currentLocation) {
        val mv = mapView ?: return@LaunchedEffect
        currentMarker?.let { mv.overlays.remove(it) }
        currentLocation?.let { loc ->
            // WGS-84转为GCJ-02显示在高德地图上
            val gcjLoc = CoordinateConverter.wgs84ToGcj02(loc.latitude, loc.longitude)
            val marker = Marker(mv).apply {
                position = GeoPoint(gcjLoc.first, gcjLoc.second)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = ContextCompat.getDrawable(context, android.R.drawable.ic_menu_mylocation)
            }
            mv.overlays.add(marker)
            currentMarker = marker
            if (isRunning) {
                mv.controller.animateTo(GeoPoint(gcjLoc.first, gcjLoc.second))
            }
            mv.invalidate()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                MapView(ctx).apply {
                    setTileSource(ChinaMapTileSource()) // 使用国内直连高德瓦片
                    setMultiTouchControls(true)
                    zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
                    controller.setZoom(16.0)
                    controller.setCenter(GeoPoint(39.9042, 116.4074))

                    overlays.add(MapEventsOverlay(object : MapEventsReceiver {
                        override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                            if (!isRunning && p != null) {
                                viewModel.addRoutePoint(LatLng(p.latitude, p.longitude))
                                return true
                            }
                            return false
                        }
                        override fun longPressHelper(p: GeoPoint?): Boolean = false
                    }))
                    mapView = this
                }
            },
            update = { mv -> mv.invalidate() }
        )

        // 右上角缩放按钮
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 16.dp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SmallFloatingActionButton(
                onClick = { mapView?.controller?.zoomIn() },
                shape = CircleShape,
                containerColor = Color.White.copy(alpha = 0.8f)
            ) { Icon(Icons.Default.Add, "放大") }
            SmallFloatingActionButton(
                onClick = { mapView?.controller?.zoomOut() },
                shape = CircleShape,
                containerColor = Color.White.copy(alpha = 0.8f)
            ) { Icon(Icons.Default.Remove, "缩小") }
        }

        // 定位按钮 (左下角，稍向上移避开卡片)
        FloatingActionButton(
            onClick = {
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    errorMessage = "请先授予位置权限"
                    return@FloatingActionButton
                }
                val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                if (loc != null) {
                    mapView?.controller?.animateTo(GeoPoint(loc.latitude, loc.longitude), 18.0, 1000L)
                } else {
                    lm.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, object : LocationListener {
                        override fun onLocationChanged(l: Location) { mapView?.controller?.animateTo(GeoPoint(l.latitude, l.longitude), 18.0, 1000L) }
                        override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
                        override fun onProviderEnabled(p: String) {}
                        override fun onProviderDisabled(p: String) {}
                    }, Looper.getMainLooper())
                }
            },
            modifier = Modifier.padding(16.dp).align(Alignment.BottomStart).offset(y = (-200).dp),
            containerColor = MaterialTheme.colorScheme.primary
        ) { Icon(Icons.Default.MyLocation, "定位") }

        // 底部面板
        Column(
            modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (routePoints.size >= 2) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    shape = RoundedCornerShape(12.dp),
                    elevation = CardDefaults.cardElevation(4.dp)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("路线信息", fontWeight = FontWeight.Bold)
                            if (isLoopMode) Badge(containerColor = Color(0xFF4CAF50)) { Text("循环模式", color = Color.White, modifier = Modifier.padding(4.dp)) }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            val distKm = viewModel.getCurrentRouteDistance() / 1000
                            Text("距离: ${"%.2f".format(distKm)} km")
                            Text("预计: ${formatDuration(viewModel.getCurrentRouteDuration())}")
                        }
                        if (isRunning) {
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("进度: ${"%.1f".format(progress * 100)}%", fontSize = 12.sp)
                                if (isLoopMode) Text("第 ${lapCount + 1} 圈", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                shape = RoundedCornerShape(12.dp),
                elevation = CardDefaults.cardElevation(4.dp)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("目标配速: ${"%.1f".format(sliderPace)} 分/km", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        if (isRunning && currentSpeed > 0.1f) {
                            // 速度(m/s) → 配速(分/km): 1000 / (speed * 60)
                            val currentPace = 1000f / (currentSpeed * 60f)
                            Text("当前: ${"%.1f".format(currentPace)} 分/km", fontSize = 14.sp, color = Color(0xFF4CAF50))
                        }
                    }
                    Slider(
                        value = sliderPace,
                        onValueChange = { sliderPace = it },
                        onValueChangeFinished = {
                            viewModel.setBasePace(sliderPace, context)
                        },
                        valueRange = 3f..15f,
                        steps = 11
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("3'00\"", fontSize = 10.sp, color = Color.Gray)
                        Text("快", fontSize = 10.sp, color = Color.Gray)
                        Text("慢", fontSize = 10.sp, color = Color.Gray)
                        Text("15'00\"", fontSize = 10.sp, color = Color.Gray)
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(
                    onClick = { viewModel.clearRoute() },
                    enabled = !isRunning && routePoints.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Gray),
                    modifier = Modifier.weight(1f).height(56.dp)
                ) {
                    Icon(Icons.Default.Clear, null)
                    Spacer(Modifier.width(8.dp))
                    Text("清除", fontSize = 18.sp)
                }

                Button(
                    onClick = {
                        if (isRunning) {
                            viewModel.stopRunning(context)
                        } else {
                            if (routePoints.size >= 2) {
                                if (!MockLocationService.isMockEnabled(context)) {
                                    errorMessage = "请先在开发者选项中「选择模拟位置信息应用」为本应用"
                                } else {
                                    viewModel.startRunning(context)
                                }
                            } else {
                                errorMessage = "请至少选择2个点"
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = if (isRunning) Color.Red else Color(0xFF4CAF50)),
                    modifier = Modifier.weight(1f).height(56.dp)
                ) {
                    Icon(if (isRunning) Icons.Default.Stop else Icons.Default.PlayArrow, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (isRunning) "停止" else "开始", fontSize = 18.sp)
                }
            }
        }
    }

    errorMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { errorMessage = null },
            title = { Text("提示") },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = {
                    errorMessage = null
                    try {
                        context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                    } catch (e: Exception) {
                        Toast.makeText(context, "请手动打开开发者选项", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("去设置") }
            },
            dismissButton = { TextButton(onClick = { errorMessage = null }) { Text("忽略") } }
        )
    }
}

fun formatDuration(seconds: Long): String {
    val minutes = seconds / 60
    val secs = seconds % 60
    return String.format("%d:%02d", minutes, secs)
}
