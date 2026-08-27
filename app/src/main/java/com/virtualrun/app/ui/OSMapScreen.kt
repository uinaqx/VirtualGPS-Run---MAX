package com.virtualrun.app.ui

import android.Manifest
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.animation.LinearInterpolator
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.android.gms.maps.model.LatLng
import com.virtualrun.app.map.ChinaMapTileSource
import com.virtualrun.app.map.CoordinateConverter
import com.virtualrun.app.service.MockLocationService
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.FolderOverlay
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import kotlin.math.roundToInt

private val PlannedRouteColor = Color(0xFF1E88E5)
private val ActualTrailColor = Color(0xFFFF7043)
private val RunGreen = Color(0xFF2EAD65)
private val StopRed = Color(0xFFE04F5F)

private enum class RunUiErrorAction {
    DISMISS,
    APP_SETTINGS,
    DEVELOPER_SETTINGS
}

private data class RunUiError(
    val message: String,
    val action: RunUiErrorAction
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OSMapScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val routePoints by viewModel.routePoints.collectAsState()
    val isRunning by viewModel.isRunning.collectAsState()
    val isStopping by viewModel.isStopping.collectAsState()
    val basePace by viewModel.basePace.collectAsState()
    val currentLocation by viewModel.currentLocation.collectAsState()
    val progress by viewModel.progress.collectAsState()
    val lapCount by viewModel.lapCount.collectAsState()
    val isLoopMode by viewModel.isLoopMode.collectAsState()
    val currentSpeed by viewModel.speed.collectAsState()
    val latestIsRunning by rememberUpdatedState(isRunning)
    val latestIsLoopMode by rememberUpdatedState(isLoopMode)

    var mapView by remember { mutableStateOf<MapView?>(null) }
    var sliderPace by remember(basePace) { mutableFloatStateOf(basePace) }
    var routePolyline by remember { mutableStateOf<Polyline?>(null) }
    var routeMarkers by remember { mutableStateOf<FolderOverlay?>(null) }
    var actualTrail by remember { mutableStateOf<Polyline?>(null) }
    val rawTrailPoints = remember { mutableListOf<GeoPoint>() }
    var currentMarker by remember { mutableStateOf<Marker?>(null) }
    var markerAnimator by remember { mutableStateOf<ValueAnimator?>(null) }
    var pendingLocationListener by remember { mutableStateOf<LocationListener?>(null) }
    var lastCameraFollowAt by remember { mutableLongStateOf(0L) }
    var wasRunning by remember { mutableStateOf(false) }
    var uiError by remember { mutableStateOf<RunUiError?>(null) }

    val animatedProgress = remember { Animatable(0f) }
    var animatedProgressLap by remember { mutableIntStateOf(0) }
    val animatedSpeed by animateFloatAsState(
        targetValue = currentSpeed,
        animationSpec = tween(durationMillis = 650, easing = LinearEasing),
        label = "current speed"
    )
    val actionColor by animateColorAsState(
        targetValue = when {
            isStopping -> ActualTrailColor
            isRunning -> StopRed
            else -> RunGreen
        },
        animationSpec = tween(250),
        label = "run action color"
    )
    val locationButtonOffset by animateDpAsState(
        targetValue = if (routePoints.size >= 2) (-300).dp else (-210).dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "location button offset"
    )

    fun animateToDeviceLocation(location: Location) {
        val gcjLocation = CoordinateConverter.wgs84ToGcj02(location.latitude, location.longitude)
        mapView?.controller?.animateTo(GeoPoint(gcjLocation.first, gcjLocation.second), 18.0, 1000L)
    }

    LaunchedEffect(progress, lapCount, isRunning) {
        if (!isRunning) {
            animatedProgress.snapTo(0f)
        } else if (lapCount != animatedProgressLap || progress + 0.05f < animatedProgress.value) {
            animatedProgress.snapTo(progress)
        } else {
            animatedProgress.animateTo(progress, tween(850, easing = LinearEasing))
        }
        animatedProgressLap = lapCount
    }

    LaunchedEffect(isRunning, mapView) {
        if (isRunning && !wasRunning) {
            markerAnimator?.cancel()
            rawTrailPoints.clear()
            actualTrail?.let { mapView?.overlays?.remove(it) }
            actualTrail = null
            lastCameraFollowAt = 0L
        }
        wasRunning = isRunning
    }

    DisposableEffect(lifecycleOwner, mapView) {
        val activeMap = mapView
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> activeMap?.onResume()
                Lifecycle.Event.ON_PAUSE -> activeMap?.onPause()
                Lifecycle.Event.ON_DESTROY -> activeMap?.onDetach()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            markerAnimator?.cancel()
        }
    }

    DisposableEffect(pendingLocationListener) {
        val listener = pendingLocationListener
        onDispose {
            if (listener != null) {
                val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                runCatching { locationManager.removeUpdates(listener) }
            }
        }
    }

    LaunchedEffect(mapView, routePoints) {
        val mv = mapView ?: return@LaunchedEffect
        routePolyline?.let(mv.overlays::remove)
        routeMarkers?.let(mv.overlays::remove)
        routePolyline = null
        routeMarkers = null

        if (routePoints.size >= 2) {
            routePolyline = Polyline(mv).apply {
                routePoints.forEach { addPoint(GeoPoint(it.latitude, it.longitude)) }
                outlinePaint.apply {
                    color = PlannedRouteColor.toArgb()
                    strokeWidth = 10f
                }
            }.also(mv.overlays::add)
        }

        if (routePoints.isNotEmpty()) {
            routeMarkers = FolderOverlay().also { folder ->
                routePoints.forEachIndexed { index, point ->
                    val marker = Marker(mv).apply {
                        position = GeoPoint(point.latitude, point.longitude)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        infoWindow = null
                        icon = android.graphics.drawable.GradientDrawable().apply {
                            shape = android.graphics.drawable.GradientDrawable.OVAL
                            setColor(if (index == 0) android.graphics.Color.parseColor("#2EAD65") else android.graphics.Color.parseColor("#1E88E5"))
                            setStroke(if (index == 0) 5 else 3, android.graphics.Color.WHITE)
                            val targetDp = if (index == 0) 48 else 40
                            val targetPx = (targetDp * context.resources.displayMetrics.density).roundToInt()
                            setSize(targetPx, targetPx)
                        }
                        setOnMarkerClickListener { _, _ ->
                            if (index == 0 && !latestIsRunning && routePoints.size >= 3) {
                                viewModel.closeLoop()
                            }
                            true
                        }
                    }
                    folder.add(marker)
                }
                mv.overlays.add(folder)
            }
        }

        if (routePoints.isEmpty() && !latestIsRunning) {
            actualTrail?.let(mv.overlays::remove)
            actualTrail = null
            rawTrailPoints.clear()
        }
        // 路线编辑后重新建立稳定层级：计划线 < 路线点 < 原始实跑线 < 当前定位点。
        actualTrail?.let {
            mv.overlays.remove(it)
            mv.overlays.add(it)
        }
        currentMarker?.let {
            mv.overlays.remove(it)
            mv.overlays.add(it)
        }
        mv.invalidate()
    }

    LaunchedEffect(mapView, currentLocation) {
        val mv = mapView ?: return@LaunchedEffect
        val loc = currentLocation
        if (loc == null) {
            markerAnimator?.cancel()
            currentMarker?.let(mv.overlays::remove)
            currentMarker = null
            mv.invalidate()
            return@LaunchedEffect
        }

        val gcjLoc = CoordinateConverter.wgs84ToGcj02(loc.latitude, loc.longitude)
        val target = GeoPoint(gcjLoc.first, gcjLoc.second)
        val previousTrailPoint = rawTrailPoints.lastOrNull()
        if (previousTrailPoint == null || previousTrailPoint.latitude != target.latitude || previousTrailPoint.longitude != target.longitude) {
            rawTrailPoints.add(target)
            if (rawTrailPoints.size > 3600) rawTrailPoints.removeAt(0)
        }

        val trail = actualTrail ?: Polyline(mv).apply {
            outlinePaint.apply {
                color = ActualTrailColor.toArgb()
                strokeWidth = 6f
            }
        }.also {
            actualTrail = it
            mv.overlays.add(it)
        }
        trail.setPoints(rawTrailPoints.toList())

        val marker = currentMarker ?: Marker(mv).apply {
            position = target
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            icon = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(android.graphics.Color.parseColor("#FF7043"))
                setStroke(5, android.graphics.Color.WHITE)
                setSize(44, 44)
            }
        }.also {
            currentMarker = it
            mv.overlays.add(it)
        }

        markerAnimator?.cancel()
        val start = GeoPoint(marker.position.latitude, marker.position.longitude)
        markerAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 880L
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                val fraction = animator.animatedValue as Float
                marker.position = GeoPoint(
                    start.latitude + (target.latitude - start.latitude) * fraction,
                    start.longitude + (target.longitude - start.longitude) * fraction
                )
                mv.invalidate()
            }
            start()
        }

        val now = SystemClock.elapsedRealtime()
        if (isRunning && now - lastCameraFollowAt >= 2500L) {
            mv.controller.animateTo(target, mv.zoomLevelDouble, 900L)
            lastCameraFollowAt = now
        }
        mv.invalidate()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .semantics {
                    contentDescription = "路线地图。移动地图后双击，在地图中心添加路线点"
                    onClick(label = "在地图中心添加路线点") {
                        val center = mapView?.mapCenter
                        if (!latestIsRunning && !latestIsLoopMode && center != null) {
                            viewModel.addRoutePoint(LatLng(center.latitude, center.longitude))
                            true
                        } else {
                            false
                        }
                    }
                },
            factory = { ctx ->
                MapView(ctx).apply {
                    setTileSource(ChinaMapTileSource())
                    setMultiTouchControls(true)
                    zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
                    controller.setZoom(16.0)
                    controller.setCenter(GeoPoint(39.9042, 116.4074))
                    overlays.add(MapEventsOverlay(object : MapEventsReceiver {
                        override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                            if (!latestIsRunning && !latestIsLoopMode && p != null) {
                                viewModel.addRoutePoint(LatLng(p.latitude, p.longitude))
                            }
                            return p != null
                        }

                        override fun longPressHelper(p: GeoPoint?): Boolean = false
                    }))
                    mapView = this
                }
            }
        )

        AnimatedVisibility(
            visible = isRunning,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp),
            enter = fadeIn(tween(180)) + slideInVertically(tween(240)) { -it / 2 },
            exit = fadeOut(tween(150)) + slideOutVertically(tween(200)) { -it / 2 }
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                shadowElevation = 6.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.DirectionsRun, contentDescription = null, tint = RunGreen)
                    Text(
                        when {
                            isStopping -> "正在平稳停止"
                            isLoopMode -> "跑步中 · 第 ${lapCount + 1} 圈"
                            else -> "跑步中"
                        },
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = routePoints.size >= 3 && !isLoopMode && !isRunning,
            modifier = Modifier.align(Alignment.TopStart).padding(top = 16.dp, start = 16.dp),
            enter = fadeIn(tween(180)) + slideInVertically(tween(240)) { -it / 2 },
            exit = fadeOut(tween(140)) + slideOutVertically(tween(200)) { -it / 2 }
        ) {
            ExtendedFloatingActionButton(
                onClick = { viewModel.closeLoop() },
                modifier = Modifier.semantics { contentDescription = "闭合路线" },
                icon = { Icon(Icons.Default.Loop, contentDescription = null) },
                text = { Text("闭合路线") },
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary
            )
        }

        Column(
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 16.dp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SmallFloatingActionButton(
                onClick = { mapView?.controller?.zoomIn() },
                shape = CircleShape,
                containerColor = Color.White.copy(alpha = 0.86f)
            ) { Icon(Icons.Default.Add, "放大") }
            SmallFloatingActionButton(
                onClick = { mapView?.controller?.zoomOut() },
                shape = CircleShape,
                containerColor = Color.White.copy(alpha = 0.86f)
            ) { Icon(Icons.Default.Remove, "缩小") }
            if (!isRunning && !isLoopMode) {
                SmallFloatingActionButton(
                    onClick = {
                        mapView?.mapCenter?.let { center ->
                            viewModel.addRoutePoint(LatLng(center.latitude, center.longitude))
                        }
                    },
                    shape = CircleShape,
                    containerColor = Color.White.copy(alpha = 0.92f)
                ) { Icon(Icons.Default.AddLocation, "在地图中心添加路线点") }
            }
        }

        FloatingActionButton(
            onClick = {
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    uiError = RunUiError("请先授予位置权限", RunUiErrorAction.APP_SETTINGS)
                    return@FloatingActionButton
                }
                val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                val location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                if (location != null) {
                    pendingLocationListener?.let { runCatching { locationManager.removeUpdates(it) } }
                    pendingLocationListener = null
                    animateToDeviceLocation(location)
                } else {
                    pendingLocationListener?.let { runCatching { locationManager.removeUpdates(it) } }
                    val listener = object : LocationListener {
                            override fun onLocationChanged(location: Location) {
                                animateToDeviceLocation(location)
                                runCatching { locationManager.removeUpdates(this) }
                                pendingLocationListener = null
                            }
                            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                            override fun onProviderEnabled(provider: String) = Unit
                            override fun onProviderDisabled(provider: String) = Unit
                        }
                    pendingLocationListener = listener
                    locationManager.requestSingleUpdate(
                        LocationManager.NETWORK_PROVIDER,
                        listener,
                        Looper.getMainLooper()
                    )
                }
            },
            modifier = Modifier.padding(16.dp).align(Alignment.BottomStart).offset(y = locationButtonOffset),
            containerColor = MaterialTheme.colorScheme.primary
        ) { Icon(Icons.Default.MyLocation, "定位") }

        Column(
            modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AnimatedVisibility(
                visible = routePoints.size >= 2,
                enter = fadeIn(tween(180)) + slideInVertically(tween(260)) { it / 3 },
                exit = fadeOut(tween(140)) + slideOutVertically(tween(220)) { it / 3 }
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).animateContentSize(
                        spring(stiffness = Spring.StiffnessMediumLow)
                    ),
                    shape = RoundedCornerShape(16.dp),
                    elevation = CardDefaults.cardElevation(5.dp)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("路线信息", fontWeight = FontWeight.Bold)
                            AnimatedVisibility(visible = isLoopMode, enter = fadeIn(), exit = fadeOut()) {
                                Badge(containerColor = RunGreen) {
                                    Text(
                                        "循环模式",
                                        color = Color.White,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                    )
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            val distanceKm = viewModel.getCurrentRouteDistance() / 1000
                            Text("距离: ${"%.2f".format(distanceKm)} km")
                            Text("预计: ${formatDuration(viewModel.getCurrentRouteDuration())}")
                        }
                        AnimatedVisibility(visible = isRunning, enter = fadeIn(tween(220)), exit = fadeOut(tween(150))) {
                            Column {
                                Spacer(Modifier.height(8.dp))
                                LinearProgressIndicator(
                                    progress = animatedProgress.value,
                                    modifier = Modifier.fillMaxWidth(),
                                    color = RunGreen
                                )
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("进度: ${"%.1f".format(animatedProgress.value * 100)}%", fontSize = 12.sp)
                                    if (isLoopMode) Text("第 ${lapCount + 1} 圈", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).animateContentSize(
                    spring(stiffness = Spring.StiffnessMediumLow)
                ),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(5.dp)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("目标配速: ${"%.1f".format(sliderPace)} 分/km", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        AnimatedVisibility(
                            visible = isRunning && animatedSpeed > 0.1f,
                            enter = fadeIn(tween(200)),
                            exit = fadeOut(tween(140))
                        ) {
                            val currentPace = 1000f / (animatedSpeed.coerceAtLeast(0.1f) * 60f)
                            Text("当前: ${"%.1f".format(currentPace)} 分/km", fontSize = 14.sp, color = RunGreen)
                        }
                    }
                    Slider(
                        value = sliderPace,
                        onValueChange = { sliderPace = it },
                        onValueChangeFinished = { viewModel.setBasePace(sliderPace, context) },
                        enabled = !isStopping,
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
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6F7782)),
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
                        } else if (routePoints.size >= 2) {
                            if (!MockLocationService.isMockEnabled(context)) {
                                uiError = RunUiError(
                                    "请先在开发者选项中「选择模拟位置信息应用」为本应用",
                                    RunUiErrorAction.DEVELOPER_SETTINGS
                                )
                            } else viewModel.startRunning(context)
                        } else {
                            uiError = RunUiError("请至少选择2个点", RunUiErrorAction.DISMISS)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = actionColor),
                    enabled = !isStopping,
                    modifier = Modifier.weight(1f).height(56.dp)
                ) {
                    val actionLabel = when {
                        isStopping -> "减速中"
                        isRunning -> "停止"
                        else -> "开始"
                    }
                    Crossfade(targetState = actionLabel, animationSpec = tween(180), label = "run action") { label ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (label == "开始") Icons.Default.PlayArrow else Icons.Default.Stop, null)
                            Spacer(Modifier.width(8.dp))
                            Text(label, fontSize = 18.sp)
                        }
                    }
                }
            }
        }
    }

    uiError?.let { error ->
        AlertDialog(
            onDismissRequest = { uiError = null },
            title = { Text("提示") },
            text = { Text(error.message) },
            confirmButton = {
                TextButton(onClick = {
                    uiError = null
                    when (error.action) {
                        RunUiErrorAction.DISMISS -> Unit
                        RunUiErrorAction.APP_SETTINGS -> try {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:${context.packageName}")
                                )
                            )
                        } catch (_: Exception) {
                            Toast.makeText(context, "请手动打开应用权限设置", Toast.LENGTH_SHORT).show()
                        }
                        RunUiErrorAction.DEVELOPER_SETTINGS -> try {
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                        } catch (_: Exception) {
                            Toast.makeText(context, "请手动打开开发者选项", Toast.LENGTH_SHORT).show()
                        }
                    }
                }) {
                    Text(if (error.action == RunUiErrorAction.DISMISS) "知道了" else "去设置")
                }
            },
            dismissButton = if (error.action == RunUiErrorAction.DISMISS) null else {
                { TextButton(onClick = { uiError = null }) { Text("取消") } }
            }
        )
    }
}

fun formatDuration(seconds: Long): String {
    val minutes = seconds / 60
    val secs = seconds % 60
    return String.format("%d:%02d", minutes, secs)
}
