package com.virtualrun.app.service

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.maps.model.LatLng
import com.virtualrun.app.MainActivity
import com.virtualrun.app.R
import com.virtualrun.app.model.Route
import com.virtualrun.app.model.PaceMode
import com.virtualrun.app.model.SimulationMotionSettings
import androidx.core.content.IntentCompat
import com.virtualrun.app.model.SimulationMetrics
import com.virtualrun.app.model.SimulationMetricsCalculator
import com.virtualrun.app.model.SimulationProfile
import com.virtualrun.app.model.SimulationStorage
import java.util.Locale

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
        private const val WAKELOCK_TIMEOUT_MS = 10 * 60 * 60 * 1000L

        const val ACTION_START_MOCK = "com.virtualrun.action.START_MOCK"
        const val ACTION_STOP_MOCK = "com.virtualrun.action.STOP_MOCK"
        const val ACTION_UPDATE_PARAMS = "com.virtualrun.action.UPDATE_PARAMS"

        const val EXTRA_PACE = "extra_pace"
        const val EXTRA_ROUTE_POINTS = "extra_route_points"
        const val EXTRA_IS_LOOP = "extra_is_loop"
        const val EXTRA_STRIDE_CM = "extra_stride_cm"
        const val EXTRA_CALORIES_PER_KM = "extra_calories_per_km"
        const val EXTRA_PACE_MODE = "extra_pace_mode"
        const val EXTRA_FASTEST_PACE = "extra_fastest_pace"
        const val EXTRA_SLOWEST_PACE = "extra_slowest_pace"
        const val EXTRA_LOCAL_MANEUVERS = "extra_local_maneuvers"

        fun readMotionSettings(intent: Intent): SimulationMotionSettings = runCatching {
            val defaults = SimulationMotionSettings()
            SimulationMotionSettings(
                paceMode = intent.getStringExtra(EXTRA_PACE_MODE)?.let(PaceMode::valueOf) ?: defaults.paceMode,
                fastestSecondsPerKm = intent.getDoubleExtra(EXTRA_FASTEST_PACE, defaults.fastestSecondsPerKm),
                slowestSecondsPerKm = intent.getDoubleExtra(EXTRA_SLOWEST_PACE, defaults.slowestSecondsPerKm),
                localManeuvers = intent.getBooleanExtra(EXTRA_LOCAL_MANEUVERS, defaults.localManeuvers)
            )
        }.getOrDefault(SimulationMotionSettings())

        fun putMotionSettings(intent: Intent, settings: SimulationMotionSettings) {
            intent.putExtra(EXTRA_PACE_MODE, settings.paceMode.name)
            intent.putExtra(EXTRA_FASTEST_PACE, settings.fastestSecondsPerKm)
            intent.putExtra(EXTRA_SLOWEST_PACE, settings.slowestSecondsPerKm)
            intent.putExtra(EXTRA_LOCAL_MANEUVERS, settings.localManeuvers)
        }

        const val BROADCAST_ACTION_STATE_UPDATE = "com.virtualrun.broadcast.STATE_UPDATE"
        const val BROADCAST_ACTION_DELIVERY_HEALTH = "com.virtualrun.broadcast.DELIVERY_HEALTH"
        const val EXTRA_DELIVERY_SUMMARY = "extra_delivery_summary"
        const val EXTRA_DELIVERY_DETAILS = "extra_delivery_details"
        const val EXTRA_DELIVERY_WARNING = "extra_delivery_warning"
        const val BROADCAST_EXTRA_LAT = "extra_lat"
        const val BROADCAST_EXTRA_LNG = "extra_lng"
        const val BROADCAST_EXTRA_PROGRESS = "extra_progress"
        const val BROADCAST_EXTRA_LAP = "extra_lap"
        const val BROADCAST_EXTRA_SPEED = "extra_speed"
        const val BROADCAST_EXTRA_COMPLETED = "extra_completed"
        const val BROADCAST_EXTRA_RUNNING = "extra_running"
        const val BROADCAST_EXTRA_HAS_LOCATION = "extra_has_location"
        const val BROADCAST_EXTRA_DISTANCE_METERS = "extra_distance_meters"
        const val BROADCAST_EXTRA_ELAPSED_MILLIS = "extra_elapsed_millis"

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

    private lateinit var pump: MockLocationPump
    private val mainHandler = Handler(android.os.Looper.getMainLooper())
    private var session = 0L
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var currentPace = 6.0f
    private var motionSettings = SimulationMotionSettings()
    private var simulationProfile = SimulationProfile()
    private var simulationMetrics = SimulationMetrics()
    private var currentRoutePoints: ArrayList<LatLng>? = null
    private var currentIsLoop = false
    private var lastResult: com.virtualrun.app.algorithm.PositionResult? = null
    private var terminalStateBroadcast = false
    private var monitoringDelivery = false
    // Run independently of the pump looper: a stalled Binder call must not hide a lost heartbeat.
    private val deliveryMonitor = object : Runnable {
        override fun run() {
            if (!monitoringDelivery) return
            broadcastDeliveryHealth()
            updateNotification(lastResult)
            mainHandler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        pump = MockLocationPump(applicationContext)
        createNotificationChannel()
        acquireWakeLock()
        Log.d(TAG, "Mock Service Created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_MOCK -> {
                Log.d(TAG, "Action: START_MOCK")
                currentPace = intent.getFloatExtra(EXTRA_PACE, 6.0f).takeIf { it.isFinite() && it in 3f..15f } ?: 6.0f
                motionSettings = readMotionSettings(intent)
                simulationProfile = runCatching {
                    SimulationProfile(
                        intent.getDoubleExtra(EXTRA_STRIDE_CM, 100.0),
                        intent.getDoubleExtra(EXTRA_CALORIES_PER_KM, 60.0)
                    )
                }.getOrDefault(SimulationProfile())
                simulationMetrics = SimulationMetrics()
                lastResult = null
                currentIsLoop = intent.getBooleanExtra(EXTRA_IS_LOOP, false)
                currentRoutePoints = IntentCompat.getParcelableArrayListExtra(intent, EXTRA_ROUTE_POINTS, LatLng::class.java)

                val points = currentRoutePoints
                if (points != null && points.size >= 2) {
                    Log.i(TAG, "routeStart loop=$currentIsLoop points=${points.size} pace=$currentPace")
                    val route = Route.fromLatLngPoints(points)
                    val activeSession = ++session
                    terminalStateBroadcast = false
                    startForeground(NOTIFICATION_ID, buildNotification())
                    pump.start(route, currentPace, currentIsLoop, motionSettings) { result ->
                        mainHandler.post {
                            if (session == activeSession && !terminalStateBroadcast) {
                                lastResult = result
                                broadcastUpdate(result)
                                if (result.isCompleted) stopMockImmediately()
                            }
                        }
                    }
                    monitoringDelivery = true
                    mainHandler.removeCallbacks(deliveryMonitor)
                    mainHandler.post(deliveryMonitor)
                } else {
                    broadcastTerminalState()
                    stopSelf()
                }
            }
            ACTION_UPDATE_PARAMS -> {
                val requestedPace = intent.getFloatExtra(EXTRA_PACE, currentPace)
                if (motionSettings.paceMode != PaceMode.BOUNDED && requestedPace.isFinite() && requestedPace in 3f..15f) {
                    currentPace = requestedPace
                    pump.updatePace(currentPace)
                }
                updateNotification(lastResult)
            }
            ACTION_STOP_MOCK -> {
                Log.d(TAG, "Action: STOP_MOCK")
                stopMockImmediately()
            }
        }
        // 未持久化运动状态时不自动重放起跑 Intent，避免进程恢复后瞬移回路线起点。
        return START_NOT_STICKY
    }

    private fun stopMockImmediately() {
        ++session
        pump.stop()
        stopDeliveryMonitor()
        lastResult = null
        broadcastTerminalState()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun broadcastUpdate(result: com.virtualrun.app.algorithm.PositionResult) {
        if (result.distanceMeters.isFinite() && result.distanceMeters >= 0f && result.elapsedMillis >= 0L) {
            simulationMetrics = SimulationMetricsCalculator.calculate(
                result.distanceMeters.toDouble(), result.elapsedMillis, simulationProfile
            )
        }
        if (result.isCompleted) SimulationStorage.saveSnapshot(this, simulationMetrics, simulationProfile)
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
            putMotionSettings(this, motionSettings)
            putExtra(EXTRA_IS_LOOP, currentIsLoop)
            putSimulationMetrics(this)
            putParcelableArrayListExtra(EXTRA_ROUTE_POINTS, currentRoutePoints)
        }
        if (result.isCompleted) terminalStateBroadcast = true
        sendBroadcast(intent)
    }

    private fun broadcastDeliveryHealth(stopped: Boolean = false) {
        val health = pump.healthSnapshot(SystemClock.elapsedRealtime())
        sendBroadcast(Intent(BROADCAST_ACTION_DELIVERY_HEALTH).apply {
            setPackage(packageName)
            putExtra(EXTRA_DELIVERY_SUMMARY, if (stopped) "模拟定位已停止" else health.summary)
            putExtra(EXTRA_DELIVERY_DETAILS, if (stopped) "本次模拟定位已停止。\n${health.details}" else health.details)
            putExtra(EXTRA_DELIVERY_WARNING, !stopped && health.warning)
        })
    }

    private fun stopDeliveryMonitor() {
        monitoringDelivery = false
        mainHandler.removeCallbacks(deliveryMonitor)
        broadcastDeliveryHealth(stopped = true)
    }

    private fun broadcastTerminalState() {
        if (terminalStateBroadcast) return
        terminalStateBroadcast = true
        SimulationStorage.saveSnapshot(this, simulationMetrics, simulationProfile)
        sendBroadcast(Intent(BROADCAST_ACTION_STATE_UPDATE).apply {
            setPackage(packageName)
            putExtra(BROADCAST_EXTRA_HAS_LOCATION, false)
            putExtra(BROADCAST_EXTRA_COMPLETED, true)
            putExtra(BROADCAST_EXTRA_RUNNING, false)
            putExtra(BROADCAST_EXTRA_SPEED, 0f)
            putSimulationMetrics(this)
        })
    }

    private fun putSimulationMetrics(intent: Intent) {
        intent.putExtra(BROADCAST_EXTRA_DISTANCE_METERS, simulationMetrics.distanceMeters)
        intent.putExtra(BROADCAST_EXTRA_ELAPSED_MILLIS, simulationMetrics.elapsedMillis)
        intent.putExtra(EXTRA_STRIDE_CM, simulationProfile.strideLengthCm)
        intent.putExtra(EXTRA_CALORIES_PER_KM, simulationProfile.caloriesPerKm)
    }

    override fun onDestroy() {
        ++session
        stopDeliveryMonitor()
        if (!terminalStateBroadcast) broadcastTerminalState()
        pump.close()
        mainHandler.removeCallbacksAndMessages(null)
        releaseWakeLock()
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

        val health = pump.healthSnapshot(SystemClock.elapsedRealtime())
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(if (health.warning) "定位投递异常" else "虚拟跑步进行中")
            .setContentText("$statusText · ${health.summary}")
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
