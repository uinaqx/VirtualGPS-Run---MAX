package com.virtualrun.app.model

import com.google.android.gms.maps.model.LatLng

/**
 * 轨迹点数据类
 */
data class RoutePoint(
    val lat: Double,
    val lng: Double,
    val distanceFromStart: Float = 0f,  // 距离起点的累积距离（米）
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toLatLng(): LatLng = LatLng(lat, lng)
}

/**
 * 路线数据类
 */
data class Route(
    val points: List<RoutePoint>,
    val totalDistance: Float,  // 总距离（米）
    val totalDuration: Long    // 预计总用时（秒）
) {
    companion object {
        fun fromLatLngPoints(latLngPoints: List<LatLng>): Route {
            if (latLngPoints.isEmpty()) {
                return Route(emptyList(), 0f, 0L)
            }

            val routePoints = mutableListOf<RoutePoint>()
            var cumulativeDistance = 0f

            latLngPoints.forEachIndexed { index, latLng ->
                if (index > 0) {
                    val prevPoint = latLngPoints[index - 1]
                    cumulativeDistance += calculateDistance(prevPoint, latLng)
                }
                routePoints.add(
                    RoutePoint(
                        lat = latLng.latitude,
                        lng = latLng.longitude,
                        distanceFromStart = cumulativeDistance
                    )
                )
            }

            return Route(routePoints, cumulativeDistance, 0L)
        }

        private fun calculateDistance(start: LatLng, end: LatLng): Float {
            val results = FloatArray(1)
            android.location.Location.distanceBetween(
                start.latitude, start.longitude,
                end.latitude, end.longitude,
                results
            )
            return results[0]
        }
    }
}

/**
 * 运动状态数据类
 */
data class RunningState(
    val isRunning: Boolean = false,
    val currentSpeed: Float = 0f,  // 当前速度（米/秒）
    val basePace: Float = 6.0f,    // 基础配速（分钟/公里）
    val currentLocation: RoutePoint? = null,
    val progress: Float = 0f,      // 路线进度 0.0 - 1.0
    val elapsedTime: Long = 0L     // 已用时间（秒）
)
