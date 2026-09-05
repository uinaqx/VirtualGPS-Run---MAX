package com.virtualrun.app.service

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.model.LatLng
import com.virtualrun.app.MainActivity
import com.virtualrun.app.R
import com.virtualrun.app.algorithm.TrajectoryInterpolator
import com.virtualrun.app.model.Route
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.tasks.await
import java.util.Locale
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 虚拟定位 Mock Location Service
 *
 * 核心功能：
 * 1. 同时模拟 GPS、NETWORK、系统 FUSED provider 和 Google Play FLP
 * 2. 模拟真实动作 (TrajectoryInterpolator 提供)
 * 3. 支持后台运行和实时参数更新
 */
@SuppressLint("InlinedApi")
class MockLocationService : Service() {

    companion object {
        private const val TAG = "MockLocationService"
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "mock_location_service_channel"
        private const val UPDATE_INTERVAL_MS = 1000L
        private const val WAKELOCK_TIMEOUT_MS = 10 * 60 * 60 * 1000L
        private const val SERVICE_WATCHDOG_MS = 15_000L
        private const val PLATFORM_FUSED_PROVIDER = "fused"
        private const val FLP_TIMEOUT_MS = 2_000L
        private const val FLP_RETRY_MS = 15_000L

        const val ACTION_START_MOCK = "com.virtualrun.action.START_MOCK"
        const val ACTION_STOP_MOCK = "com.virtualrun.action.STOP_MOCK"
        const val ACTION_UPDATE_PARAMS = "com.virtualrun.action.UPDATE_PARAMS"

        const val EXTRA_PACE = "extra_pace"
        const val EXTRA_ROUTE_POINTS = "extra_route_points"
        const val EXTRA_IS_LOOP = "extra_is_loop"

        const val BROADCAST_ACTION_STATE_UPDATE = "com.virtualrun.broadcast.STATE_UPDATE"
        const val BROADCAST_EXTRA_LAT = "extra_lat"
        const val BROADCAST_EXTRA_LNG = "extra_lng"
        const val BROADCAST_EXTRA_PROGRESS = "extra_progress"
        const val BROADCAST_EXTRA_LAP = "extra_lap"
        const val BROADCAST_EXTRA_SPEED = "extra_speed"
        const val BROADCAST_EXTRA_COMPLETED = "extra_completed"
        const val BROADCAST_EXTRA_RUNNING = "extra_running"
        const val BROADCAST_EXTRA_HAS_LOCATION = "extra_has_location"

        fun isMockEnabled(context: Context): Boolean {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            return try {
                lm.addTestProvider(
                    "test_check",
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    ProviderProperties.POWER_USAGE_LOW,
                    ProviderProperties.ACCURACY_FINE
                )
                lm.removeTestProvider("test_check")
                true
            } catch (e: SecurityException) {
                false
            } catch (e: Exception) {
                true
            }
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private lateinit var locationManager: LocationManager
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var trajectoryInterpolator: TrajectoryInterpolator? = null
    private var updateJob: Job? = null
    private var fusedDeliveryJob: Job? = null
    private var fusedLocations: Channel<Location>? = null
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null
    private var currentPace = 6.0f
    private var currentRoutePoints: ArrayList<LatLng>? = null
    private var currentIsLoop = false
    private var lastResult: com.virtualrun.app.algorithm.PositionResult? = null
    private var terminalStateBroadcast = false
    private var metadataSampleCount = 0
    private var horizontalAccuracyMeters = 2.4f
    private var altitudeMeters = 45.0
    private var verticalAccuracyMetersState = 3.5f
    private var speedAccuracyMetersPerSecondState = 0.24f
    private var bearingAccuracyDegreesState = 2.8f
    private var satelliteCount = 12
    @Volatile private var fusedMockModeRequested = false
    @Volatile private var fusedMockModeEnabled = false
    private var fusedDeliveryFailureCount = 0

    private data class SensorMetadata(
        val horizontalAccuracyMeters: Float,
        val altitudeMeters: Double,
        val verticalAccuracyMeters: Float,
        val speedAccuracyMetersPerSecond: Float,
        val bearingAccuracyDegrees: Float,
        val satellites: Int
    )

    /**
     * 这是 LocationManager 的 provider。Google Play FLP 是另一条独立通道，
     * 不能靠添加一个同名的 "fused" 测试 provider 来替代。
     */
    private val platformProviders = listOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
        PLATFORM_FUSED_PROVIDER
    )
    private val activeProviders = mutableSetOf<String>()

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        workerThread = HandlerThread("mock-location-worker", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
        workerHandler = Handler(workerThread!!.looper)
        createNotificationChannel()
        acquireWakeLock()
        Log.d(TAG, "Mock Service Created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_MOCK -> {
                Log.d(TAG, "Action: START_MOCK")
                currentPace = intent.getFloatExtra(EXTRA_PACE, 6.0f)
                currentIsLoop = intent.getBooleanExtra(EXTRA_IS_LOOP, false)
                currentRoutePoints = intent.getParcelableArrayListExtra(EXTRA_ROUTE_POINTS)

                val points = currentRoutePoints
                if (points != null && points.size >= 2) {
                    val route = Route.fromLatLngPoints(points)
                    trajectoryInterpolator = TrajectoryInterpolator(route, currentPace, currentIsLoop)
                    terminalStateBroadcast = false
                    startForeground(NOTIFICATION_ID, buildNotification())
                    startLoop()
                } else {
                    broadcastTerminalState()
                    stopSelf()
                }
            }
            ACTION_UPDATE_PARAMS -> {
                val pace = intent.getFloatExtra(EXTRA_PACE, -1.0f)
                Log.d(TAG, "收到 ACTION_UPDATE_PARAMS, pace=$pace")
                if (pace > 0) {
                    currentPace = pace
                    if (trajectoryInterpolator != null) {
                        trajectoryInterpolator?.updatePace(pace)
                        Log.d(TAG, "配速已更新到插值器: $pace 分/km")
                    } else {
                        Log.w(TAG, "trajectoryInterpolator 为空，无法更新配速")
                    }
                    updateNotification(lastResult)
                } else {
                    Log.w(TAG, "收到无效的配速值: $pace")
                }
            }
            ACTION_STOP_MOCK -> {
                Log.d(TAG, "Action: STOP_MOCK")
                stopMockImmediately()
            }
        }
        // 未持久化运动状态时不自动重放起跑 Intent，避免进程恢复后瞬移回路线起点。
        return START_NOT_STICKY
    }

    private fun setupMockProviders() {
        activeProviders.clear()
        platformProviders.forEach { provider ->
            try {
                try { locationManager.removeTestProvider(provider) } catch (e: Exception) {}
                locationManager.addTestProvider(
                    provider, provider == LocationManager.NETWORK_PROVIDER,
                    provider == LocationManager.GPS_PROVIDER,
                    provider == LocationManager.NETWORK_PROVIDER, false, true, true, true,
                    if (provider == LocationManager.GPS_PROVIDER) ProviderProperties.POWER_USAGE_HIGH
                    else ProviderProperties.POWER_USAGE_LOW,
                    ProviderProperties.ACCURACY_FINE
                )
                locationManager.setTestProviderEnabled(provider, true)
                activeProviders.add(provider)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to setup provider $provider", e)
            }
        }
    }

    /**
     * FusedLocationProviderClient 不读取 LocationManager 中手工添加的同名 provider。
     * 必须显式进入 FLP mock 模式，才能覆盖跑步软件在开始记录后发起的高精度融合定位请求。
     */
    @SuppressLint("MissingPermission")
    private suspend fun enableFusedMockMode() {
        fusedMockModeRequested = true
        fusedMockModeEnabled = false
        fusedDeliveryFailureCount = 0
        try {
            fusedMockModeEnabled = withTimeoutOrNull(FLP_TIMEOUT_MS) {
                fusedLocationClient.setMockMode(true).await()
                true
            } ?: false
            Log.i(TAG, "Google Play FLP mock mode enabled=$fusedMockModeEnabled")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enable Google Play FLP mock mode; platform providers remain active", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun disableFusedMockMode() {
        if (!fusedMockModeRequested && !fusedMockModeEnabled) return

        fusedMockModeRequested = false
        fusedMockModeEnabled = false
        fusedLocationClient.setMockMode(false)
            .addOnSuccessListener { Log.i(TAG, "Google Play FLP mock mode disabled") }
            .addOnFailureListener { error -> Log.e(TAG, "Failed to disable Google Play FLP mock mode", error) }
    }

    private fun startLoop() {
        updateJob?.cancel()
        startFusedDelivery()
        updateJob = serviceScope.launch {
            setupMockProviders()

            if (activeProviders.isEmpty() && !fusedMockModeEnabled) {
                Log.e(TAG, "No mock-location delivery channel could be enabled")
                broadcastTerminalState()
                stopSelf()
                return@launch
            }

            while (isActive) {
                val result = trajectoryInterpolator?.calculateNextPosition()
                if (result != null) {
                    lastResult = result
                    pushLocation(result)
                    broadcastUpdate(result)
                    updateNotification(result)
                    if (result.isCompleted) {
                        break
                    }
                }
                delay(UPDATE_INTERVAL_MS)
            }
            stopSelf()
        }
    }

    private fun stopMockImmediately() {
        updateJob?.cancel()
        updateJob = null
        stopFusedDelivery()
        workerHandler?.removeCallbacksAndMessages(null)
        removeMockProviders()
        disableFusedMockMode()
        trajectoryInterpolator = null
        lastResult = null
        broadcastTerminalState()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    @SuppressLint("MissingPermission")
    private fun pushLocation(result: com.virtualrun.app.algorithm.PositionResult) {
        if (activeProviders.isEmpty() && !fusedMockModeEnabled) return

        val now = System.currentTimeMillis()
        val elapsedNanos = SystemClock.elapsedRealtimeNanos()
        val worker = workerHandler ?: return
        val metadata = evolveSensorMetadata()

        activeProviders.toList().forEach { provider ->
            val providerAccuracy = when (provider) {
                LocationManager.NETWORK_PROVIDER -> (metadata.horizontalAccuracyMeters + 2.2f).coerceAtMost(8.0f)
                PLATFORM_FUSED_PROVIDER -> (metadata.horizontalAccuracyMeters + 0.35f).coerceAtMost(6.0f)
                else -> metadata.horizontalAccuracyMeters
            }
            worker.post {
                try {
                    val loc = buildMockLocation(provider, result, metadata, providerAccuracy, now, elapsedNanos)
                    locationManager.setTestProviderLocation(provider, loc)
                } catch (e: Exception) {
                    Log.e(TAG, "Error pushing location to $provider", e)
                }
            }
        }

        if (fusedLocations != null) {
            val fusedAccuracy = (metadata.horizontalAccuracyMeters + 0.35f).coerceAtMost(6.0f)
            val fusedLocation = buildMockLocation(
                PLATFORM_FUSED_PROVIDER,
                result,
                metadata,
                fusedAccuracy,
                now,
                elapsedNanos
            )
            fusedLocations?.trySend(fusedLocation)
        }
    }

    /** FLP failures must never suspend the GPS/Network clock. Keep only the newest fix. */
    @SuppressLint("MissingPermission") // SecurityException is handled per channel below.
    private fun startFusedDelivery() {
        stopFusedDelivery()
        val locations = Channel<Location>(Channel.CONFLATED)
        fusedLocations = locations
        fusedDeliveryJob = serviceScope.launch {
            var nextRetryAt = 0L
            for (location in locations) {
                if (!fusedMockModeEnabled) {
                    if (SystemClock.elapsedRealtime() < nextRetryAt) continue
                    nextRetryAt = SystemClock.elapsedRealtime() + FLP_RETRY_MS
                    val availability = GoogleApiAvailability.getInstance()
                        .isGooglePlayServicesAvailable(this@MockLocationService)
                    if (availability != ConnectionResult.SUCCESS) {
                        Log.w(TAG, "FLP unavailable ($availability); GPS/Network updates continue")
                        continue
                    }
                    enableFusedMockMode()
                    // Enabling can take seconds: consume a fresh fix on the next iteration.
                    continue
                }
                try {
                    val delivered = withTimeoutOrNull(FLP_TIMEOUT_MS) {
                        fusedLocationClient.setMockLocation(location).await()
                        true
                    } ?: false
                    if (!delivered) error("FLP delivery timed out")
                    fusedDeliveryFailureCount = 0
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    fusedDeliveryFailureCount++
                    fusedMockModeEnabled = false
                    nextRetryAt = SystemClock.elapsedRealtime() + FLP_RETRY_MS
                    Log.e(TAG, "FLP delivery failed; retry scheduled, platform updates continue", e)
                }
            }
        }
    }

    private fun stopFusedDelivery() {
        fusedDeliveryJob?.cancel()
        fusedDeliveryJob = null
        fusedLocations?.close()
        fusedLocations = null
    }

    private fun buildMockLocation(
        provider: String,
        result: com.virtualrun.app.algorithm.PositionResult,
        metadata: SensorMetadata,
        providerAccuracy: Float,
        wallClockMillis: Long,
        elapsedRealtimeNanosValue: Long
    ): Location = Location(provider).apply {
        latitude = result.latitude
        longitude = result.longitude
        altitude = metadata.altitudeMeters
        speed = result.speed
        bearing = result.bearing
        accuracy = providerAccuracy
        time = wallClockMillis
        elapsedRealtimeNanos = elapsedRealtimeNanosValue

        extras = Bundle().apply {
            putInt("satellites", metadata.satellites)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            verticalAccuracyMeters = metadata.verticalAccuracyMeters
            bearingAccuracyDegrees = metadata.bearingAccuracyDegrees
            speedAccuracyMetersPerSecond = metadata.speedAccuracyMetersPerSecond
        }
    }

    /**
     * 真实 GNSS 精度和卫星可见数会缓慢变化，而不是每秒完全相同。
     * 各状态使用均值回归，避免逐点独立随机数造成不自然的闪烁。
     */
    private fun evolveSensorMetadata(): SensorMetadata {
        metadataSampleCount++
        horizontalAccuracyMeters = evolveValue(horizontalAccuracyMeters, 2.6f, 0.10f, 0.14f, 1.6f, 5.5f)
        altitudeMeters = evolveValue(altitudeMeters.toFloat(), 45.0f, 0.025f, 0.16f, 40.0f, 50.0f).toDouble()
        verticalAccuracyMetersState = evolveValue(verticalAccuracyMetersState, 4.0f, 0.08f, 0.20f, 2.5f, 7.0f)
        speedAccuracyMetersPerSecondState = evolveValue(
            speedAccuracyMetersPerSecondState,
            0.28f,
            0.12f,
            0.018f,
            0.12f,
            0.55f
        )
        bearingAccuracyDegreesState = evolveValue(bearingAccuracyDegreesState, 3.4f, 0.08f, 0.22f, 1.5f, 8.0f)

        if (metadataSampleCount % 5 == 0) {
            satelliteCount = (satelliteCount + Random.nextInt(-1, 2)).coerceIn(8, 16)
        }

        return SensorMetadata(
            horizontalAccuracyMeters = horizontalAccuracyMeters,
            altitudeMeters = altitudeMeters,
            verticalAccuracyMeters = verticalAccuracyMetersState,
            speedAccuracyMetersPerSecond = speedAccuracyMetersPerSecondState,
            bearingAccuracyDegrees = bearingAccuracyDegreesState,
            satellites = satelliteCount
        )
    }

    private fun evolveValue(
        current: Float,
        target: Float,
        meanReversion: Float,
        noiseScale: Float,
        minimum: Float,
        maximum: Float
    ): Float {
        val next = current + (target - current) * meanReversion + randomGaussian().toFloat() * noiseScale
        return next.coerceIn(minimum, maximum)
    }

    private fun randomGaussian(): Double {
        val first = Random.nextDouble().coerceAtLeast(1e-12)
        val second = Random.nextDouble()
        return sqrt(-2.0 * ln(first)) * cos(2.0 * Math.PI * second)
    }

    private fun broadcastUpdate(result: com.virtualrun.app.algorithm.PositionResult) {
        val running = !result.isCompleted
        val intent = Intent(BROADCAST_ACTION_STATE_UPDATE).apply {
            setPackage(packageName)
            putExtra(BROADCAST_EXTRA_HAS_LOCATION, true)
            putExtra(BROADCAST_EXTRA_LAT, result.latitude)
            putExtra(BROADCAST_EXTRA_LNG, result.longitude)
            putExtra(BROADCAST_EXTRA_PROGRESS, result.progress)
            putExtra(BROADCAST_EXTRA_LAP, result.lapCount)
            putExtra(BROADCAST_EXTRA_SPEED, result.speed)
            putExtra(BROADCAST_EXTRA_COMPLETED, result.isCompleted)
            putExtra(BROADCAST_EXTRA_RUNNING, running)
            putExtra(EXTRA_PACE, currentPace)
            putExtra(EXTRA_IS_LOOP, currentIsLoop)
            putParcelableArrayListExtra(EXTRA_ROUTE_POINTS, currentRoutePoints)
        }
        if (result.isCompleted) terminalStateBroadcast = true
        sendBroadcast(intent)
    }

    private fun broadcastTerminalState() {
        if (terminalStateBroadcast) return
        terminalStateBroadcast = true
        sendBroadcast(Intent(BROADCAST_ACTION_STATE_UPDATE).apply {
            setPackage(packageName)
            putExtra(BROADCAST_EXTRA_HAS_LOCATION, false)
            putExtra(BROADCAST_EXTRA_COMPLETED, true)
            putExtra(BROADCAST_EXTRA_RUNNING, false)
            putExtra(BROADCAST_EXTRA_SPEED, 0f)
        })
    }

    private fun removeMockProviders() {
        updateJob?.cancel()
        activeProviders.forEach { provider ->
            try {
                locationManager.setTestProviderEnabled(provider, false)
                locationManager.removeTestProvider(provider)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing provider $provider", e)
            }
        }
        activeProviders.clear()
    }

    override fun onDestroy() {
        Log.d(TAG, "Service Destroyed")
        if (!terminalStateBroadcast) broadcastTerminalState()
        stopFusedDelivery()
        removeMockProviders()
        disableFusedMockMode()
        releaseWakeLock()
        workerHandler?.removeCallbacksAndMessages(null)
        workerThread?.quitSafely()
        workerHandler = null
        workerThread = null
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        wakeLock = powerManager.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "$packageName:mock-location").apply {
            setReferenceCounted(false)
            acquire(WAKELOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "虚拟跑步服务",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val result = lastResult
        val statusText = if (result == null) {
            "正在准备虚拟定位..."
        } else {
            val progressPercent = (result.progress * 100).coerceIn(0f, 100f)
            val lapText = if (currentIsLoop) " · 第${result.lapCount + 1}圈" else ""
            String.format(Locale.getDefault(), "%.1f 分/km · %.0f%%%s", currentPace, progressPercent, lapText)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("虚拟跑步进行中")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(result: com.virtualrun.app.algorithm.PositionResult?) {
        lastResult = result
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification())
    }
}
