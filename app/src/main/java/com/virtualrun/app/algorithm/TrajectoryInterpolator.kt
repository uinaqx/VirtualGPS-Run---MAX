package com.virtualrun.app.algorithm

import com.google.android.gms.maps.model.LatLng
import com.virtualrun.app.map.CoordinateConverter
import com.virtualrun.app.model.Route
import kotlin.math.*
import kotlin.random.Random

/**
 * 轨迹插值与真实跑步动作模拟算法
 *
 * 特性：
 * 1. Catmull-Rom 样条平滑：路线更自然
 * 2. 速度波动：±15% 模拟真实配速变化
 * 3. 路线偏离：渐变式偏移，模拟真实跑步摆动
 * 4. 循环跑支持
 * 5. WGS-84/GCJ-02 坐标转换
 */
class TrajectoryInterpolator(
    route: Route,
    private var basePace: Float,  // 配速：分钟/公里
    private val isLoopMode: Boolean = false // 是否循环跑
) {
    // 基础速度：米/秒
    private var baseSpeed: Float = calculateBaseSpeed(basePace)

    // 平滑后的密集点列表（含距离信息）
    private data class DensePoint(val lat: Double, val lng: Double, val distanceFromStart: Float)
    private val densePoints: List<DensePoint>
    private val totalDistance: Float

    // 当前状态
    private var currentDistance: Float = 0f  // 已跑距离（米）
    private var currentIndex: Int = 0        // 当前所在路段索引
    private var isCompleted: Boolean = false
    private var lastLatitude: Double = 0.0
    private var lastLongitude: Double = 0.0
    private var displayedLatitude: Double = 0.0
    private var displayedLongitude: Double = 0.0
    private var smoothedBearing: Float = 0f
    private var speedPhase = Random.nextDouble(0.0, Math.PI * 2)

    // 循环统计
    private var lapCount: Int = 0

    // 偏离参数 - 改进为渐变式
    private var deviationState = DeviationState.NONE
    private var nextDeviationTime = System.currentTimeMillis() + Random.nextLong(5000, 11000)
    private var deviationStartTime: Long = 0
    private var deviationEndTime: Long = 0
    private var deviationMaxDuration: Long = 0
    private var currentDeviationAngle = 0.0
    private var currentMaxDeviationDistance = 0.0

    private enum class DeviationState {
        NONE,       // 无偏移
        ENTERING,   // 渐入偏移
        HOLDING,    // 保持最大偏移
        EXITING     // 渐出偏移
    }

    init {
        // 预处理：对原始路线做 Catmull-Rom 平滑，生成密集点
        // 同时将GCJ-02坐标转为WGS-84（因为模拟定位需要WGS-84）
        val result = generateSmoothedPoints(route.points, isLoopMode)
        densePoints = result
        totalDistance = result.lastOrNull()?.distanceFromStart ?: 0f
    }

    fun updatePace(newPace: Float) {
        this.basePace = newPace
        this.baseSpeed = calculateBaseSpeed(newPace)
    }

    private fun calculateBaseSpeed(pace: Float): Float {
        return 1000f / (pace * 60)
    }

    fun calculateNextPosition(): PositionResult {
        if (isCompleted || densePoints.isEmpty()) {
            val lastPoint = densePoints.lastOrNull()
            return PositionResult(
                latitude = lastPoint?.lat ?: 0.0,
                longitude = lastPoint?.lng ?: 0.0,
                speed = 0f,
                bearing = 0f,
                isCompleted = true,
                lapCount = lapCount
            )
        }

        val now = System.currentTimeMillis()
        val instantaneousSpeed = calculateSpeedWithVariation(now)

        // 假设每秒调用一次
        val distanceToMove = instantaneousSpeed * 1.0f
        currentDistance += distanceToMove

        // 检查是否完成或需要循环
        if (currentDistance >= totalDistance) {
            if (isLoopMode) {
                currentDistance %= totalDistance
                currentIndex = 0
                lapCount++
            } else {
                isCompleted = true
                val lastPoint = densePoints.last()
                return PositionResult(
                    latitude = lastPoint.lat,
                    longitude = lastPoint.lng,
                    speed = 0f,
                    bearing = calculateBearing(lastLatitude, lastLongitude, lastPoint.lat, lastPoint.lng),
                    isCompleted = true,
                    progress = 1.0f,
                    lapCount = lapCount
                )
            }
        }

        // 找到基础位置
        val basePos = findPositionByDistance(currentDistance)
        var finalLat = basePos.latitude
        var finalLng = basePos.longitude

        // 计算航向角
        val movementBearing = if (lastLatitude != 0.0 && lastLongitude != 0.0) {
            calculateBearing(lastLatitude, lastLongitude, finalLat, finalLng)
        } else {
            estimatePathBearing(currentDistance)
        }

        // 处理渐变式路线偏离
        handleSmoothDeviation(now, movementBearing.toDouble())
        val deviationOffset = calculateCurrentDeviation(now)
        if (deviationOffset > 0.01) {
            val offset = calculateLocationOffset(finalLat, finalLng, deviationOffset, currentDeviationAngle)
            finalLat = offset.latitude
            finalLng = offset.longitude
        }

        val smoothedPosition = smoothDisplayedPosition(finalLat, finalLng)
        val targetBearing = if (displayedLatitude != 0.0 && displayedLongitude != 0.0) {
            calculateBearing(displayedLatitude, displayedLongitude, smoothedPosition.latitude, smoothedPosition.longitude)
        } else {
            movementBearing
        }
        val finalBearing = smoothBearing(targetBearing)

        displayedLatitude = smoothedPosition.latitude
        displayedLongitude = smoothedPosition.longitude
        lastLatitude = basePos.latitude
        lastLongitude = basePos.longitude

        return PositionResult(
            latitude = smoothedPosition.latitude,
            longitude = smoothedPosition.longitude,
            speed = instantaneousSpeed,
            bearing = finalBearing,
            isCompleted = false,
            progress = if (totalDistance > 0) currentDistance / totalDistance else 0f,
            lapCount = lapCount
        )
    }

    // ==================== Catmull-Rom 样条平滑 ====================

    /**
     * 对原始路线进行 Catmull-Rom 样条插值，生成平滑密集点
     * 同时将GCJ-02坐标转换为WGS-84（用于模拟定位）
     */
    private fun generateSmoothedPoints(originalPoints: List<com.virtualrun.app.model.RoutePoint>, loop: Boolean): List<DensePoint> {
        if (originalPoints.size < 2) {
            return originalPoints.map {
                // 地图点击的是GCJ-02，转为WGS-84存储
                val wgs84 = CoordinateConverter.gcj02ToWgs84(it.lat, it.lng)
                DensePoint(wgs84.first, wgs84.second, 0f)
            }
        }

        val dense = mutableListOf<DensePoint>()
        var cumulativeDist = 0f

        // 先将所有点转为WGS-84
        val wgsPoints = originalPoints.map {
            val wgs84 = CoordinateConverter.gcj02ToWgs84(it.lat, it.lng)
            com.virtualrun.app.model.RoutePoint(wgs84.first, wgs84.second)
        }

        if (loop) {
            val n = wgsPoints.size
            val extended = mutableListOf<com.virtualrun.app.model.RoutePoint>()
            extended.add(wgsPoints[n - 1])
            extended.addAll(wgsPoints)
            extended.add(wgsPoints[0])
            extended.add(wgsPoints[1])

            for (seg in 0 until n) {
                val p0 = extended[seg]
                val p1 = extended[seg + 1]
                val p2 = extended[seg + 2]
                val p3 = extended[seg + 3]
                val steps = 15

                for (i in 0 until steps) {
                    val t = i.toDouble() / steps
                    val pt = catmullRom(p0, p1, p2, p3, t)
                    if (dense.isNotEmpty()) {
                        cumulativeDist += haversineDistance(dense.last().lat, dense.last().lng, pt.first, pt.second)
                    }
                    dense.add(DensePoint(pt.first, pt.second, cumulativeDist))
                }
            }
            val firstPt = dense.first()
            cumulativeDist += haversineDistance(dense.last().lat, dense.last().lng, firstPt.lat, firstPt.lng)
            dense.add(DensePoint(firstPt.lat, firstPt.lng, cumulativeDist))
        } else {
            val padded = mutableListOf<com.virtualrun.app.model.RoutePoint>()
            padded.add(wgsPoints.first())
            padded.addAll(wgsPoints)
            padded.add(wgsPoints.last())

            for (seg in 0 until padded.size - 3) {
                val p0 = padded[seg]
                val p1 = padded[seg + 1]
                val p2 = padded[seg + 2]
                val p3 = padded[seg + 3]
                val steps = 15

                for (i in 0 until steps) {
                    val t = i.toDouble() / steps
                    val pt = catmullRom(p0, p1, p2, p3, t)
                    if (dense.isNotEmpty()) {
                        cumulativeDist += haversineDistance(dense.last().lat, dense.last().lng, pt.first, pt.second)
                    }
                    dense.add(DensePoint(pt.first, pt.second, cumulativeDist))
                }
            }
            val last = wgsPoints.last()
            cumulativeDist += haversineDistance(dense.last().lat, dense.last().lng, last.lat, last.lng)
            dense.add(DensePoint(last.lat, last.lng, cumulativeDist))
        }

        return dense
    }

    private fun catmullRom(
        p0: com.virtualrun.app.model.RoutePoint,
        p1: com.virtualrun.app.model.RoutePoint,
        p2: com.virtualrun.app.model.RoutePoint,
        p3: com.virtualrun.app.model.RoutePoint,
        t: Double
    ): Pair<Double, Double> {
        val t2 = t * t
        val t3 = t2 * t

        val lat = 0.5 * (
            (2.0 * p1.lat) +
            (-p0.lat + p2.lat) * t +
            (2.0 * p0.lat - 5.0 * p1.lat + 4.0 * p2.lat - p3.lat) * t2 +
            (-p0.lat + 3.0 * p1.lat - 3.0 * p2.lat + p3.lat) * t3
        )

        val lng = 0.5 * (
            (2.0 * p1.lng) +
            (-p0.lng + p2.lng) * t +
            (2.0 * p0.lng - 5.0 * p1.lng + 4.0 * p2.lng - p3.lng) * t2 +
            (-p0.lng + 3.0 * p1.lng - 3.0 * p2.lng + p3.lng) * t3
        )

        return Pair(lat, lng)
    }

    private fun haversineDistance(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Float {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return (r * c).toFloat()
    }

    // ==================== 速度波动 ====================

    private fun calculateSpeedWithVariation(now: Long): Float {
        val timeSec = now / 1000.0
        speedPhase += Random.nextDouble(-0.02, 0.02)
        val cadenceWave = 0.028 * sin(timeSec * 0.42 + speedPhase)
        val breathWave = 0.02 * sin(timeSec * 0.11 + speedPhase * 0.45)
        val strideWave = 0.01 * sin(timeSec * 1.6 + speedPhase * 1.5)
        val terrainWave = 0.012 * sin(timeSec * 0.03 + 1.2)
        val randomVariation = Random.nextDouble(-0.008, 0.008)
        val totalVariation = cadenceWave + breathWave + strideWave + terrainWave + randomVariation
        val variedSpeed = baseSpeed * (1 + totalVariation).toFloat()
        return variedSpeed.coerceIn(baseSpeed * 0.92f, baseSpeed * 1.08f)
    }

    // ==================== 渐变式路线偏离 ====================

    /**
     * 处理渐变式路线偏离状态机
     */
    private fun handleSmoothDeviation(now: Long, currentBearing: Double) {
        when (deviationState) {
            DeviationState.NONE -> {
                if (now >= nextDeviationTime) {
                    // 开始新的偏离周期
                    startNewDeviation(now, currentBearing)
                }
            }
            DeviationState.ENTERING -> {
                if (now >= deviationStartTime + ENTER_DURATION) {
                    deviationState = DeviationState.HOLDING
                }
            }
            DeviationState.HOLDING -> {
                if (now >= deviationEndTime - EXIT_DURATION) {
                    deviationState = DeviationState.EXITING
                }
            }
            DeviationState.EXITING -> {
                if (now >= deviationEndTime) {
                    deviationState = DeviationState.NONE
                    nextDeviationTime = now + Random.nextLong(6000, 12000)
                }
            }
        }
    }

    /**
     * 开始新的偏离周期
     */
    private fun startNewDeviation(now: Long, currentBearing: Double) {
        deviationState = DeviationState.ENTERING
        deviationStartTime = now
        deviationMaxDuration = Random.nextLong(2500, 6000)
        deviationEndTime = now + deviationMaxDuration

        // 偏离方向：垂直于当前航向（左右随机）±30度随机
        val sideOffset = if (Random.nextBoolean()) 90 else -90
        val randomAngle = Random.nextDouble(-30.0, 30.0)
        currentDeviationAngle = (currentBearing + sideOffset + randomAngle) % 360

        currentMaxDeviationDistance = Random.nextDouble(0.5, 3.0)
    }

    /**
     * 计算当前时刻的偏移距离（带渐入渐出效果）
     */
    private fun calculateCurrentDeviation(now: Long): Double {
        return when (deviationState) {
            DeviationState.NONE -> 0.0
            DeviationState.ENTERING -> {
                val elapsed = now - deviationStartTime
                val progress = (elapsed.toDouble() / ENTER_DURATION).coerceIn(0.0, 1.0)
                // 使用easeInOut函数让过渡更平滑
                val easedProgress = easeInOutCubic(progress)
                currentMaxDeviationDistance * easedProgress
            }
            DeviationState.HOLDING -> {
                // 保持阶段添加轻微摆动
                val timeInHold = now - (deviationStartTime + ENTER_DURATION)
                val breathe = sin(timeInHold * 0.003) * 0.3 // ±30%的呼吸效果
                currentMaxDeviationDistance * (1 + breathe)
            }
            DeviationState.EXITING -> {
                val timeToEnd = deviationEndTime - now
                val progress = (timeToEnd.toDouble() / EXIT_DURATION).coerceIn(0.0, 1.0)
                val easedProgress = easeInOutCubic(progress)
                currentMaxDeviationDistance * easedProgress
            }
        }
    }

    /**
     * Cubic ease-in-out 缓动函数
     */
    private fun easeInOutCubic(t: Double): Double {
        return if (t < 0.5) {
            4.0 * t * t * t
        } else {
            val v = -2.0 * t + 2.0
            1.0 - (v * v * v) / 2.0
        }
    }

    private fun calculateBearing(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Float {
        if (lat1 == 0.0 && lng1 == 0.0) return 0f
        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)
        val deltaLng = Math.toRadians(lng2 - lng1)
        val y = sin(deltaLng) * cos(lat2Rad)
        val x = cos(lat1Rad) * sin(lat2Rad) - sin(lat1Rad) * cos(lat2Rad) * cos(deltaLng)
        var bearing = Math.toDegrees(atan2(y, x)).toFloat()
        bearing = (bearing + 360) % 360
        return bearing
    }

    private fun findPositionByDistance(targetDistance: Float): TargetPosition {
        if (targetDistance < 0f || densePoints.isEmpty()) {
            val first = densePoints.firstOrNull() ?: return TargetPosition(0.0, 0.0)
            return TargetPosition(first.lat, first.lng)
        }

        if (targetDistance < densePoints[currentIndex].distanceFromStart) {
            currentIndex = 0
        }

        for (i in currentIndex until densePoints.size - 1) {
            val currentPt = densePoints[i]
            val nextPt = densePoints[i + 1]
            val segStart = currentPt.distanceFromStart
            val segEnd = nextPt.distanceFromStart

            if (targetDistance in segStart..segEnd) {
                currentIndex = i
                val ratio = if (segEnd > segStart) (targetDistance - segStart) / (segEnd - segStart) else 0f
                val lat = currentPt.lat + (nextPt.lat - currentPt.lat) * ratio
                val lng = currentPt.lng + (nextPt.lng - currentPt.lng) * ratio
                return TargetPosition(lat, lng)
            }
        }

        val lastPt = densePoints.last()
        return TargetPosition(lastPt.lat, lastPt.lng)
    }

    private fun calculateLocationOffset(lat: Double, lng: Double, distanceMeters: Double, bearingDegrees: Double): TargetPosition {
        val radiusEarth = 6378137.0
        val distRatio = distanceMeters / radiusEarth
        val bearingRad = Math.toRadians(bearingDegrees)
        val latRad = Math.toRadians(lat)
        val lngRad = Math.toRadians(lng)

        val newLat = asin(sin(latRad) * cos(distRatio) + cos(latRad) * sin(distRatio) * cos(bearingRad))
        val newLng = lngRad + atan2(sin(bearingRad) * sin(distRatio) * cos(latRad), cos(distRatio) - sin(latRad) * sin(newLat))

        return TargetPosition(Math.toDegrees(newLat), Math.toDegrees(newLng))
    }

    private fun estimatePathBearing(distance: Float): Float {
        val current = findPositionByDistance(distance)
        val lookAhead = findPositionByDistance((distance + LOOKAHEAD_DISTANCE_METERS).coerceAtMost(totalDistance))
        return calculateBearing(current.latitude, current.longitude, lookAhead.latitude, lookAhead.longitude)
    }

    private fun smoothDisplayedPosition(targetLat: Double, targetLng: Double): TargetPosition {
        if (displayedLatitude == 0.0 && displayedLongitude == 0.0) {
            return TargetPosition(targetLat, targetLng)
        }

        val lat = displayedLatitude + (targetLat - displayedLatitude) * POSITION_SMOOTHING_FACTOR
        val lng = displayedLongitude + (targetLng - displayedLongitude) * POSITION_SMOOTHING_FACTOR
        return TargetPosition(lat, lng)
    }

    private fun smoothBearing(targetBearing: Float): Float {
        if (smoothedBearing == 0f) {
            smoothedBearing = targetBearing
            return targetBearing
        }

        var delta = (targetBearing - smoothedBearing + 540f) % 360f - 180f
        delta = delta.coerceIn(-MAX_BEARING_STEP_DEGREES, MAX_BEARING_STEP_DEGREES)
        smoothedBearing = (smoothedBearing + delta * BEARING_SMOOTHING_FACTOR + 360f) % 360f
        return smoothedBearing
    }

    fun reset() {
        currentDistance = 0f
        currentIndex = 0
        isCompleted = false
        lastLatitude = 0.0
        lastLongitude = 0.0
        displayedLatitude = 0.0
        displayedLongitude = 0.0
        smoothedBearing = 0f
        speedPhase = Random.nextDouble(0.0, Math.PI * 2)
        lapCount = 0
        deviationState = DeviationState.NONE
        nextDeviationTime = System.currentTimeMillis() + Random.nextLong(5000, 11000)
    }

    fun isCompleted(): Boolean = isCompleted
    fun getLapCount(): Int = lapCount

    companion object {
        private const val ENTER_DURATION = 1200L
        private const val EXIT_DURATION = 1200L
        private const val POSITION_SMOOTHING_FACTOR = 0.35f
        private const val BEARING_SMOOTHING_FACTOR = 0.25f
        private const val MAX_BEARING_STEP_DEGREES = 15f
        private const val LOOKAHEAD_DISTANCE_METERS = 4f
    }
}

data class PositionResult(
    val latitude: Double,
    val longitude: Double,
    val speed: Float,
    val bearing: Float = 0f,
    val isCompleted: Boolean,
    val progress: Float = 0f,
    val lapCount: Int = 0
)

data class TargetPosition(
    val latitude: Double,
    val longitude: Double
)

object TrajectoryUtils {
    fun calculateDistance(start: LatLng, end: LatLng): Float {
        val results = FloatArray(1)
        android.location.Location.distanceBetween(start.latitude, start.longitude, end.latitude, end.longitude, results)
        return results[0]
    }

    fun calculateTotalDistance(points: List<LatLng>): Float {
        var totalDistance = 0f
        for (i in 0 until points.size - 1) {
            totalDistance += calculateDistance(points[i], points[i + 1])
        }
        return totalDistance
    }

    fun calculateDuration(distanceMeters: Float, paceMinPerKm: Float): Long {
        val distanceKm = distanceMeters / 1000f
        return (distanceKm * paceMinPerKm * 60).toLong()
    }
}
