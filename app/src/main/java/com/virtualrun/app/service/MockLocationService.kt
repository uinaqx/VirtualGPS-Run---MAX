package com.virtualrun.app.service

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.location.Criteria
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
import com.google.android.gms.maps.model.LatLng
import com.virtualrun.app.MainActivity
import com.virtualrun.app.R
import com.virtualrun.app.algorithm.TrajectoryInterpolator
import com.virtualrun.app.model.Route
import kotlinx.coroutines.*
import java.util.Locale

/**
 * 虚拟定位 Mock Location Service
 *
 * 核心功能：
 * 1. 同时模拟 GPS, NETWORK 和 FUSED provider
 * 2. 模拟真实动作 (TrajectoryInterpolator 提供)
 * 3. 支持后台运行和实时参数更新
 */
class MockLocationService : Service() {

    companion object {
        private const val TAG = "MockLocationService"
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "mock_location_service_channel"
        private const val UPDATE_INTERVAL_MS = 1000L
        private const val WAKELOCK_TIMEOUT_MS = 10 * 60 * 60 * 1000L
        private const val SERVICE_WATCHDOG_MS = 15_000L

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

        fun isMockEnabled(context: Context): Boolean {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            return try {
                lm.addTestProvider("test_check", false, false, false, false, false, false, false, 0, 5)
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
    private var trajectoryInterpolator: TrajectoryInterpolator? = null
    private var updateJob: Job? = null
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null
    private var currentPace = 6.0f
    private var currentRoutePoints: ArrayList<LatLng>? = null
    private var currentIsLoop = false
    private var lastResult: com.virtualrun.app.algorithm.PositionResult? = null

    private val providers = listOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
        "fused"
    )
    private val activeProviders = mutableSetOf<String>()

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
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
                    startForeground(NOTIFICATION_ID, buildNotification())
                    setupMockProviders()
                    startLoop()
                } else {
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
                stopSelf()
            }
        }
        return START_REDELIVER_INTENT
    }

    private fun setupMockProviders() {
        activeProviders.clear()
        providers.forEach { provider ->
            try {
                try { locationManager.removeTestProvider(provider) } catch (e: Exception) {}
                locationManager.addTestProvider(
                    provider, false, false, false, false, true, true, true,
                    1, 1
                )
                locationManager.setTestProviderEnabled(provider, true)
                locationManager.setTestProviderStatus(provider, 2, null, System.currentTimeMillis())
                activeProviders.add(provider)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to setup provider $provider", e)
            }
        }
    }

    private fun startLoop() {
        updateJob?.cancel()
        updateJob = serviceScope.launch {
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

    private fun pushLocation(result: com.virtualrun.app.algorithm.PositionResult) {
        if (activeProviders.isEmpty()) return

        val now = System.currentTimeMillis()
        val elapsedNanos = SystemClock.elapsedRealtimeNanos()
        val worker = workerHandler ?: return

        activeProviders.forEach { provider ->
            worker.post {
                try {
                    val loc = Location(provider).apply {
                        latitude = result.latitude
                        longitude = result.longitude
                        altitude = 20.0
                        speed = result.speed
                        bearing = result.bearing
                        accuracy = if (provider == LocationManager.NETWORK_PROVIDER) 3.5f else 1.2f
                        time = now
                        elapsedRealtimeNanos = elapsedNanos

                        val extras = Bundle()
                        extras.putInt("satellites", 12)
                        this.extras = extras

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            verticalAccuracyMeters = 2.5f
                            bearingAccuracyDegrees = 1.5f
                            speedAccuracyMetersPerSecond = 0.15f
                        }
                    }
                    locationManager.setTestProviderLocation(provider, loc)
                } catch (e: Exception) {
                    Log.e(TAG, "Error pushing location to $provider", e)
                }
            }
        }
    }

    private fun broadcastUpdate(result: com.virtualrun.app.algorithm.PositionResult) {
        val intent = Intent(BROADCAST_ACTION_STATE_UPDATE).apply {
            setPackage(packageName)
            putExtra(BROADCAST_EXTRA_LAT, result.latitude)
            putExtra(BROADCAST_EXTRA_LNG, result.longitude)
            putExtra(BROADCAST_EXTRA_PROGRESS, result.progress)
            putExtra(BROADCAST_EXTRA_LAP, result.lapCount)
            putExtra(BROADCAST_EXTRA_SPEED, result.speed)
            putExtra(BROADCAST_EXTRA_COMPLETED, result.isCompleted)
        }
        sendBroadcast(intent)
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
        removeMockProviders()
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
