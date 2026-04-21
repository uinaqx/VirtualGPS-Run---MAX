package com.virtualrun.app.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.maps.model.LatLng
import com.virtualrun.app.algorithm.TrajectoryUtils
import com.virtualrun.app.model.Route
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

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == MockLocationService.BROADCAST_ACTION_STATE_UPDATE) {
                val lat = intent.getDoubleExtra(MockLocationService.BROADCAST_EXTRA_LAT, 0.0)
                val lng = intent.getDoubleExtra(MockLocationService.BROADCAST_EXTRA_LNG, 0.0)
                val prog = intent.getFloatExtra(MockLocationService.BROADCAST_EXTRA_PROGRESS, 0f)
                val lap = intent.getIntExtra(MockLocationService.BROADCAST_EXTRA_LAP, 0)
                val spd = intent.getFloatExtra(MockLocationService.BROADCAST_EXTRA_SPEED, 0f)
                val completed = intent.getBooleanExtra(MockLocationService.BROADCAST_EXTRA_COMPLETED, false)

                _currentLocation.value = LatLng(lat, lng)
                _progress.value = prog
                _lapCount.value = lap
                _speed.value = spd

                if (completed) {
                    _isRunning.value = false
                }
            }
        }
    }

    fun registerReceiver(context: Context) {
        val filter = IntentFilter(MockLocationService.BROADCAST_ACTION_STATE_UPDATE)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(stateReceiver, filter)
        }
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
        if (currentPoints.size >= 2) {
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
        if (currentPoints.size < 2) return
        val start = currentPoints[0]
        val last = currentPoints.last()
        // 只有最后一点和起点还没重合时才闭合
        if (TrajectoryUtils.calculateDistance(last, start) > 1f) {
            _routePoints.value = currentPoints + start
            _isLoopMode.value = true
            Log.d("MainViewModel", "路线已闭环，进入循环模式")
        }
    }

    fun clearRoute() {
        _routePoints.value = emptyList()
        _progress.value = 0f
        _lapCount.value = 0
        _isLoopMode.value = false
    }

    fun setBasePace(pace: Float, context: Context? = null) {
        _basePace.value = pace
        Log.d("MainViewModel", "配速调整为: $pace 分/km")
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

    fun startRunning(context: Context) {
        if (_routePoints.value.size < 2) return

        try {
            val serviceIntent = Intent(context, MockLocationService::class.java).apply {
                action = MockLocationService.ACTION_START_MOCK
                putExtra(MockLocationService.EXTRA_PACE, _basePace.value)
                putExtra(MockLocationService.EXTRA_IS_LOOP, _isLoopMode.value)
                putParcelableArrayListExtra(MockLocationService.EXTRA_ROUTE_POINTS, ArrayList(_routePoints.value))
            }
            context.startForegroundService(serviceIntent)
            _isRunning.value = true
            _lapCount.value = 0
        } catch (e: Exception) {
            Log.e("MainViewModel", "Error starting run", e)
            _isRunning.value = false
        }
    }

    fun stopRunning(context: Context) {
        _isRunning.value = false
        try {
            val serviceIntent = Intent(context, MockLocationService::class.java).apply {
                action = MockLocationService.ACTION_STOP_MOCK
            }
            context.startService(serviceIntent)
        } catch (e: Exception) {
            Log.e("MainViewModel", "Error stopping service", e)
        }

        _progress.value = 0f
        _currentLocation.value = null
    }

    fun getCurrentRouteDistance(): Float {
        return TrajectoryUtils.calculateTotalDistance(_routePoints.value)
    }

    fun getCurrentRouteDuration(): Long {
        val distance = getCurrentRouteDistance()
        return TrajectoryUtils.calculateDuration(distance, _basePace.value)
    }
}
