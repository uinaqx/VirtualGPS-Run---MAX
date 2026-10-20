package com.virtualrun.app.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.maps.model.LatLng
import com.virtualrun.app.algorithm.TrajectoryUtils
import com.virtualrun.app.model.Route
import com.virtualrun.app.model.reverseRoute
import com.virtualrun.app.model.PaceMode
import com.virtualrun.app.model.SimulationMotionSettings
import com.virtualrun.app.model.SimulationMetrics
import com.virtualrun.app.model.SimulationMetricsCalculator
import com.virtualrun.app.model.SimulationProfile
import com.virtualrun.app.model.SimulationStorage
import com.virtualrun.app.service.MockLocationService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel : ViewModel() {
    private val _routePoints = MutableStateFlow<List<LatLng>>(emptyList())
    val routePoints: StateFlow<List<LatLng>> = _routePoints.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _basePace = MutableStateFlow(6.0f)
    val basePace: StateFlow<Float> = _basePace.asStateFlow()

    private val _motionSettings = MutableStateFlow(SimulationMotionSettings())
    val motionSettings: StateFlow<SimulationMotionSettings> = _motionSettings.asStateFlow()

    private val _currentLocation = MutableStateFlow<LatLng?>(null)
    val currentLocation: StateFlow<LatLng?> = _currentLocation.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    private val _lapCount = MutableStateFlow(0)
    val lapCount: StateFlow<Int> = _lapCount.asStateFlow()

    private val _isLoopMode = MutableStateFlow(false)
    val isLoopMode: StateFlow<Boolean> = _isLoopMode.asStateFlow()

    private val _speed = MutableStateFlow(0f)
    val speed: StateFlow<Float> = _speed.asStateFlow()

    private val _simulationProfile = MutableStateFlow(SimulationProfile())
    val simulationProfile: StateFlow<SimulationProfile> = _simulationProfile.asStateFlow()
    private val _simulationMetrics = MutableStateFlow(SimulationMetrics())
    val simulationMetrics: StateFlow<SimulationMetrics> = _simulationMetrics.asStateFlow()
    private val _deliverySummary = MutableStateFlow("尚未开始定位")
    val deliverySummary: StateFlow<String> = _deliverySummary.asStateFlow()
    private val _deliveryDetails = MutableStateFlow("")
    val deliveryDetails: StateFlow<String> = _deliveryDetails.asStateFlow()
    private val _deliveryWarning = MutableStateFlow(false)
    val deliveryWarning: StateFlow<Boolean> = _deliveryWarning.asStateFlow()
    private var applicationContext: Context? = null
    private var stopRequested = false

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == MockLocationService.BROADCAST_ACTION_DELIVERY_HEALTH) {
                _deliverySummary.value = intent.getStringExtra(MockLocationService.EXTRA_DELIVERY_SUMMARY).orEmpty()
                _deliveryDetails.value = intent.getStringExtra(MockLocationService.EXTRA_DELIVERY_DETAILS).orEmpty()
                _deliveryWarning.value = intent.getBooleanExtra(MockLocationService.EXTRA_DELIVERY_WARNING, false)
                return
            }
            if (intent?.action == MockLocationService.BROADCAST_ACTION_STATE_UPDATE) {
                val hasLocation = intent.getBooleanExtra(MockLocationService.BROADCAST_EXTRA_HAS_LOCATION, true)
                val prog = intent.getFloatExtra(MockLocationService.BROADCAST_EXTRA_PROGRESS, 0f)
                val lap = intent.getIntExtra(MockLocationService.BROADCAST_EXTRA_LAP, 0)
                val spd = intent.getFloatExtra(MockLocationService.BROADCAST_EXTRA_SPEED, 0f)
                val completed = intent.getBooleanExtra(MockLocationService.BROADCAST_EXTRA_COMPLETED, false)
                val running = intent.getBooleanExtra(MockLocationService.BROADCAST_EXTRA_RUNNING, !completed)

                if (intent.hasExtra(MockLocationService.BROADCAST_EXTRA_DISTANCE_METERS)) {
                    runCatching {
                        val activeProfile = SimulationProfile(
                            intent.getDoubleExtra(MockLocationService.EXTRA_STRIDE_CM, 100.0),
                            intent.getDoubleExtra(MockLocationService.EXTRA_CALORIES_PER_KM, 60.0)
                        )
                        _simulationMetrics.value = SimulationMetricsCalculator.calculate(
                            intent.getDoubleExtra(MockLocationService.BROADCAST_EXTRA_DISTANCE_METERS, 0.0),
                            intent.getLongExtra(MockLocationService.BROADCAST_EXTRA_ELAPSED_MILLIS, 0L),
                            activeProfile
                        )
                        if (running) _simulationProfile.value = activeProfile
                    }.onFailure { Log.w("MainViewModel", "Invalid local simulation statistics", it) }
                }

                if (hasLocation) {
                    val lat = intent.getDoubleExtra(MockLocationService.BROADCAST_EXTRA_LAT, 0.0)
                    val lng = intent.getDoubleExtra(MockLocationService.BROADCAST_EXTRA_LNG, 0.0)
                    _currentLocation.value = LatLng(lat, lng)
                    _progress.value = prog
                    _lapCount.value = lap
                    _speed.value = spd
                }

                // Android 13's typed Parcelable-list reader can crash for LatLng's CREATOR.
                // IntentCompat uses the legacy reader there and the fixed API on Android 14+.
                val restoredPoints = IntentCompat.getParcelableArrayListExtra(
                    intent, MockLocationService.EXTRA_ROUTE_POINTS, LatLng::class.java
                )
                restoredPoints?.let { _routePoints.value = it }
                if (intent.hasExtra(MockLocationService.EXTRA_PACE)) {
                    _basePace.value = intent.getFloatExtra(MockLocationService.EXTRA_PACE, _basePace.value)
                }
                if (intent.hasExtra(MockLocationService.EXTRA_PACE_MODE)) {
                    _motionSettings.value = MockLocationService.readMotionSettings(intent)
                }
                if (intent.hasExtra(MockLocationService.EXTRA_IS_LOOP)) {
                    _isLoopMode.value = intent.getBooleanExtra(MockLocationService.EXTRA_IS_LOOP, false)
                }

                _isRunning.value = running
                if (!running || completed) stopRequested = false

                if (completed) {
                    _speed.value = 0f
                    _isRunning.value = false
                }
            }
        }
    }

    fun registerReceiver(context: Context) {
        applicationContext = context.applicationContext
        _simulationProfile.value = SimulationStorage.loadProfile(context)
        _simulationMetrics.value = SimulationStorage.loadSnapshot(context)
        val filter = IntentFilter(MockLocationService.BROADCAST_ACTION_STATE_UPDATE).apply {
            addAction(MockLocationService.BROADCAST_ACTION_DELIVERY_HEALTH)
        }
        ContextCompat.registerReceiver(
            context,
            stateReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    fun unregisterReceiver(context: Context) {
        try {
            context.unregisterReceiver(stateReceiver)
        } catch (e: Exception) {
            Log.e("MainViewModel", "Error unregistering receiver", e)
        }
    }

    fun addRoutePoint(latLng: LatLng) {
        val currentPoints = _routePoints.value

        // 检查点击的是否是起点，如果是，则闭合曲线进入循环模式
        if (currentPoints.size >= 3) {
            val start = currentPoints[0]
            val distanceToStart = TrajectoryUtils.calculateDistance(latLng, start)

            // 点击距离起点小于 25 米认为是想闭合
            if (distanceToStart < 25f) {
                val last = currentPoints.last()
                // 只有最后一点和起点还没重合时才闭合
                if (TrajectoryUtils.calculateDistance(last, start) > 5f) {
                    _routePoints.value = currentPoints + start
                    _isLoopMode.value = true
                    return
                }
            }
        }

        _routePoints.value = currentPoints + latLng
        _isLoopMode.value = false
    }

    /**
     * 手动闭环：将最后一个点连接到起点，形成循环路线
     */
    fun closeLoop() {
        val currentPoints = _routePoints.value
        if (currentPoints.size < 3) return
        val start = currentPoints[0]
        val last = currentPoints.last()
        // 只有最后一点和起点还没重合时才闭合
        if (TrajectoryUtils.calculateDistance(last, start) > 1f) {
            _routePoints.value = currentPoints + start
            _isLoopMode.value = true
            Log.d("MainViewModel", "路线已闭环，进入循环模式")
        }
    }

    fun reverseRouteDirection() {
        if (_isRunning.value || stopRequested) return
        _routePoints.value = reverseRoute(_routePoints.value, _isLoopMode.value)
    }

    fun clearRoute() {
        if (_isRunning.value || stopRequested) return
        _routePoints.value = emptyList()
        _progress.value = 0f
        _lapCount.value = 0
        _isLoopMode.value = false
        _currentLocation.value = null
        _speed.value = 0f
        _simulationMetrics.value = SimulationMetrics()
        _deliverySummary.value = "尚未开始定位"
        _deliveryDetails.value = ""
        _deliveryWarning.value = false
        applicationContext?.let { SimulationStorage.clearSnapshot(it) }
    }

    fun setSimulationProfile(profile: SimulationProfile) {
        if (_isRunning.value) return
        _simulationProfile.value = profile
        applicationContext?.let { SimulationStorage.saveProfile(it, profile) }
    }

    fun setBasePace(pace: Float, context: Context? = null) {
        if (!pace.isFinite() || pace !in 3f..15f || _motionSettings.value.paceMode == PaceMode.BOUNDED) return
        _basePace.value = pace
        // 如果正在运行，实时更新服务中的配速
        if (_isRunning.value && context != null) {
            try {
                val intent = Intent(context, MockLocationService::class.java).apply {
                    action = MockLocationService.ACTION_UPDATE_PARAMS
                    putExtra(MockLocationService.EXTRA_PACE, pace)
                }
                context.startService(intent)
                Log.d("MainViewModel", "已发送配速更新到服务: $pace")
            } catch (e: Exception) {
                Log.e("MainViewModel", "发送配速更新失败", e)
            }
        }
    }

    fun setMotionSettings(settings: SimulationMotionSettings) {
        if (_isRunning.value || stopRequested) return
        _motionSettings.value = settings
    }

    fun startRunning(context: Context) {
        if (_routePoints.value.size < 2) return

        try {
            val serviceIntent = Intent(context, MockLocationService::class.java).apply {
                action = MockLocationService.ACTION_START_MOCK
                putExtra(MockLocationService.EXTRA_PACE, _basePace.value)
                putExtra(MockLocationService.EXTRA_IS_LOOP, _isLoopMode.value)
                MockLocationService.putMotionSettings(this, _motionSettings.value)
                putExtra(MockLocationService.EXTRA_STRIDE_CM, _simulationProfile.value.strideLengthCm)
                putExtra(MockLocationService.EXTRA_CALORIES_PER_KM, _simulationProfile.value.caloriesPerKm)
                putParcelableArrayListExtra(MockLocationService.EXTRA_ROUTE_POINTS, ArrayList(_routePoints.value))
            }
            context.startForegroundService(serviceIntent)
            _progress.value = 0f
            _currentLocation.value = null
            _speed.value = 0f
            _isRunning.value = true
            stopRequested = false
            _lapCount.value = 0
            _simulationMetrics.value = SimulationMetrics()
            _deliverySummary.value = "正在准备定位"
            _deliveryDetails.value = ""
            _deliveryWarning.value = false
        } catch (e: Exception) {
            Log.e("MainViewModel", "Error starting run", e)
            _isRunning.value = false
        }
    }

    fun stopRunning(context: Context) {
        if (!_isRunning.value || stopRequested) return
        // Keep Clear/Start disabled until the service has saved and acknowledged its final totals.
        stopRequested = true
        _speed.value = 0f
        try {
            val serviceIntent = Intent(context, MockLocationService::class.java).apply {
                action = MockLocationService.ACTION_STOP_MOCK
            }
            context.startService(serviceIntent)
        } catch (e: Exception) {
            Log.e("MainViewModel", "Error stopping service", e)
            val wasActive = context.stopService(Intent(context, MockLocationService::class.java))
            if (!wasActive) {
                _isRunning.value = false
                stopRequested = false
            }
        }
    }

    fun getCurrentRouteDistance(): Float {
        return TrajectoryUtils.calculateTotalDistance(_routePoints.value)
    }

    fun getCurrentRouteDuration(): Long {
        val distance = getCurrentRouteDistance()
        val settings = _motionSettings.value
        val referencePace = if (settings.paceMode == PaceMode.BOUNDED) {
            ((settings.fastestSecondsPerKm + settings.slowestSecondsPerKm) / 120.0).toFloat()
        } else _basePace.value
        return TrajectoryUtils.calculateDuration(distance, referencePace)
    }
}
