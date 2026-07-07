package com.virtualrun.app.algorithm

import com.google.android.gms.maps.model.LatLng
import com.virtualrun.app.map.CoordinateConverter
import com.virtualrun.app.model.Route
import com.virtualrun.app.model.RoutePoint
import kotlin.math.*
import kotlin.random.Random

/**
 * 轨迹插值与真实跑步动作模拟算法
 *
 * 特性：
 * 1. 闭环路线使用 Chaikin 圆角化，把生硬折线处理成自然绕圈轨迹
 * 2. 按真实时间差推进距离，避免后台卡顿时速度失真
 * 3. 连续低频横向漂移，模拟真人每圈不会完全踩同一条线
 * 4. 速度波动保持平滑，避免刻意的锯齿偏移
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
    private var currentDistance: Float = 0f
    private var currentIndex: Int = 0
    private var isCompleted: Boolean = false
    private var lastLatitude: Double = 0.0
    private var lastLongitude: Double = 0.0
    private var displayedLatitude: Double = 0.0
    private var displayedLongitude: Double = 0.0
    private var smoothedBearing: Float = 0f
    private var lastUpdateTimeMs: Long? = null

    // 连续扰动相位。只在启动时随机一次，后续使用连续函数，避免一跳一跳的假轨迹。
    private var speedPhase = Random.nextDouble(0.0, Math.PI * 2)
    private var lateralPhase = Random.nextDouble(0.0, Math.PI * 2)

    // 循环统计
    private var lapCount: Int = 0

    init {
        val result = generateSmoothedPoints(route.points, isLoopMode)
        densePoints = result
        totalDistance = result.lastOrNull()?.distanceFromStart ?: 0f
    }

    fun updatePace(newPace: Float) {
        basePace = newPace
        baseSpeed = calculateBaseSpeed(newPace)
    }

    private fun calculateBaseSpeed(pace: Float): Float {
        return 1000f / (pace * 60)
    }

    fun calculateNextPosition(): PositionResult {
        if (densePoints.isEmpty() || totalDistance < MIN_ROUTE_DISTANCE_METERS) {
            val point = densePoints.firstOrNull()
            return PositionResult(
                latitude = point?.lat ?: 0.0,
                longitude = point?.lng ?: 0.0,
                speed = 0f,
                bearing = 0f,
                isCompleted = true,
                lapCount = lapCount
            )
        }

        if (isCompleted) {
            val lastPoint = densePoints.last()
            return PositionResult(
                latitude = lastPoint.lat,
                longitude = lastPoint.lng,
                speed = 0f,
                bearing = 0f,
                isCompleted = true,
                progress = 1.0f,
                lapCount = lapCount
            )
        }

        val now = System.currentTimeMillis()
        val deltaSeconds = calculateDeltaSeconds(now)
        val instantaneousSpeed = calculateSpeedWithVariation(now)
        currentDistance += instantaneousSpeed * deltaSeconds

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

        val basePos = findPositionByDistance(currentDistance)
        val movementBearing = if (lastLatitude != 0.0 && lastLongitude != 0.0) {
            calculateBearing(lastLatitude, lastLongitude, basePos.latitude, basePos.longitude)
        } else {
            estimatePathBearing(currentDistance)
        }

        val naturalPosition = applyNaturalTrackVariation(basePos, movementBearing, now)
        val smoothedPosition = smoothDisplayedPosition(naturalPosition.latitude, naturalPosition.longitude)
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
            progress = (currentDistance / totalDistance).coerceIn(0f, 1f),
            lapCount = lapCount
        )
    }

    private fun calculateDeltaSeconds(now: Long): Float {
        val last = lastUpdateTimeMs
        lastUpdateTimeMs = now
        if (last == null) return 0f
        return ((now - last) / 1000f).coerceIn(0.2f, 2.5f)
    }

    // ==================== 路线平滑 ====================

    /**
     * 地图点击点是 GCJ-02。模拟定位需要 WGS-84。
     * 闭环路线不再硬穿过每个尖角，而是先圆角化，再按距离重新采样。
     */
    private fun generateSmoothedPoints(originalPoints: List<RoutePoint>, loop: Boolean): List<DensePoint> {
        if (originalPoints.isEmpty()) return emptyList()

        val wgsPoints = originalPoints.map {
            val wgs84 = CoordinateConverter.gcj02ToWgs84(it.lat, it.lng)
            RoutePoint(wgs84.first, wgs84.second)
        }

        if (wgsPoints.size == 1) {
            val point = wgsPoints.first()
            return listOf(DensePoint(point.lat, point.lng, 0f))
        }

        return if (loop) {
            generateNaturalLoopPoints(removeDuplicateClosingPoint(wgsPoints))
        } else {
            generateOpenRoutePoints(wgsPoints)
        }
    }

    private fun removeDuplicateClosingPoint(points: List<RoutePoint>): List<RoutePoint> {
        if (points.size < 2) return points
        val first = points.first()
        val last = points.last()
        return if (haversineDistance(first.lat, first.lng, last.lat, last.lng) < 5f) {
            points.dropLast(1)
        } else {
            points
        }
    }

    private fun generateNaturalLoopPoints(points: List<RoutePoint>): List<DensePoint> {
        if (points.size < 3) {
            return generateOpenRoutePoints(points)
        }

        var smoothed = points
        repeat(LOOP_SMOOTHING_ITERATIONS) {
            smoothed = chaikinClosed(smoothed)
        }

        return resampleByDistance(smoothed + smoothed.first(), TARGET_SAMPLE_DISTANCE_METERS)
    }

    private fun generateOpenRoutePoints(points: List<RoutePoint>): List<DensePoint> {
        if (points.size < 2) return points.map { DensePoint(it.lat, it.lng, 0f) }

        val dense = mutableListOf<RoutePoint>()
        val padded = mutableListOf<RoutePoint>().apply {
            add(points.first())
            addAll(points)
            add(points.last())
        }

        for (seg in 0 until padded.size - 3) {
            val p0 = padded[seg]
            val p1 = padded[seg + 1]
            val p2 = padded[seg + 2]
            val p3 = padded[seg + 3]
            for (i in 0 until OPEN_ROUTE_INTERPOLATION_STEPS) {
                val t = i.toDouble() / OPEN_ROUTE_INTERPOLATION_STEPS
                val point = catmullRom(p0, p1, p2, p3, t)
                dense.add(RoutePoint(point.first, point.second))
            }
        }
        dense.add(points.last())
        return resampleByDistance(dense, TARGET_SAMPLE_DISTANCE_METERS)
    }

    private fun chaikinClosed(points: List<RoutePoint>): List<RoutePoint> {
        val result = mutableListOf<RoutePoint>()
        for (i in points.indices) {
            val p0 = points[i]
            val p1 = points[(i + 1) % points.size]
            val q = RoutePoint(
                lat = p0.lat * 0.75 + p1.lat * 0.25,
                lng = p0.lng * 0.75 + p1.lng * 0.25
            )
            val r = RoutePoint(
                lat = p0.lat * 0.25 + p1.lat * 0.75,
                lng = p0.lng * 0.25 + p1.lng * 0.75
            )
            result.add(q)
            result.add(r)
        }
        return result
    }

    private fun resampleByDistance(points: List<RoutePoint>, targetStepMeters: Float): List<DensePoint> {
        if (points.isEmpty()) return emptyList()

        val result = mutableListOf(DensePoint(points.first().lat, points.first().lng, 0f))
        var cumulativeDistance = 0f

        for (i in 0 until points.size - 1) {
            val start = points[i]
            val end = points[i + 1]
            val segmentDistance = haversineDistance(start.lat, start.lng, end.lat, end.lng)
            if (segmentDistance < 0.01f) continue

            val steps = max(1, ceil(segmentDistance / targetStepMeters).toInt())
            var prevLat = result.last().lat
            var prevLng = result.last().lng

            for (step in 1..steps) {
                val ratio = step.toDouble() / steps
                val lat = start.lat + (end.lat - start.lat) * ratio
                val lng = start.lng + (end.lng - start.lng) * ratio
                cumulativeDistance += haversineDistance(prevLat, prevLng, lat, lng)
                result.add(DensePoint(lat, lng, cumulativeDistance))
                prevLat = lat
                prevLng = lng
            }
        }

        return result
    }

    private fun catmullRom(
        p0: RoutePoint,
        p1: RoutePoint,
        p2: RoutePoint,
        p3: RoutePoint,
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

    // ==================== 速度与轨迹扰动 ====================

    private fun calculateSpeedWithVariation(now: Long): Float {
        val timeSec = now / 1000.0
        speedPhase += Random.nextDouble(-0.006, 0.006)
        val cadenceWave = 0.025 * sin(timeSec * 0.42 + speedPhase)
        val breathWave = 0.018 * sin(timeSec * 0.11 + speedPhase * 0.45)
        val terrainWave = 0.012 * sin(timeSec * 0.035 + 1.2)
        val totalVariation = cadenceWave + breathWave + terrainWave
        val variedSpeed = baseSpeed * (1 + totalVariation).toFloat()
        return variedSpeed.coerceIn(baseSpeed * 0.93f, baseSpeed * 1.07f)
    }

    /**
     * 真人绕圈不会走成尖锐折线，也不会每圈完全重合。
     * 这里使用连续的横向漂移：每圈有不同的轻微内外偏移，再叠加低频波动。
     */
    private fun applyNaturalTrackVariation(position: TargetPosition, bearing: Float, now: Long): TargetPosition {
        val distance = currentDistance.toDouble()
        val timeSec = now / 1000.0
        val lapPhase = lateralPhase + lapCount * 1.37

        val lateralMeters = if (isLoopMode) {
            val lapLaneOffset = ((lapCount % 5) - 2) * 0.55
            val longWave = sin(distance / 34.0 + lapPhase) * 1.10
            val mediumWave = sin(distance / 13.0 + lapPhase * 0.7) * 0.45
            val slowBodyDrift = sin(timeSec * 0.18 + lapPhase) * 0.35
            (lapLaneOffset + longWave + mediumWave + slowBodyDrift).coerceIn(-2.6, 2.6)
        } else {
            val softWobble = sin(distance / 22.0 + lapPhase) * 0.65 + sin(timeSec * 0.15 + lapPhase) * 0.25
            softWobble.coerceIn(-1.2, 1.2)
        }

        val sideBearing = if (lateralMeters >= 0) bearing + 90.0 else bearing - 90.0
        var shifted = if (abs(lateralMeters) > 0.01) {
            calculateLocationOffset(position.latitude, position.longitude, abs(lateralMeters), sideBearing)
        } else {
            position
        }

        if (isLoopMode) {
            val forwardMeters = sin(distance / 17.0 + lapPhase * 0.5) * 0.18
            if (abs(forwardMeters) > 0.01) {
                shifted = calculateLocationOffset(
                    shifted.latitude,
                    shifted.longitude,
                    abs(forwardMeters),
                    if (forwardMeters >= 0) bearing.toDouble() else bearing + 180.0
                )
            }
        }

        return shifted
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
        val bearingRad = Math.toRadians((bearingDegrees + 360.0) % 360.0)
        val latRad = Math.toRadians(lat)
        val lngRad = Math.toRadians(lng)

        val newLat = asin(sin(latRad) * cos(distRatio) + cos(latRad) * sin(distRatio) * cos(bearingRad))
        val newLng = lngRad + atan2(sin(bearingRad) * sin(distRatio) * cos(latRad), cos(distRatio) - sin(latRad) * sin(newLat))

        return TargetPosition(Math.toDegrees(newLat), Math.toDegrees(newLng))
    }

    private fun estimatePathBearing(distance: Float): Float {
        val current = findPositionByDistance(distance)
        val lookAheadDistance = if (isLoopMode) {
            (distance + LOOKAHEAD_DISTANCE_METERS) % totalDistance
        } else {
            (distance + LOOKAHEAD_DISTANCE_METERS).coerceAtMost(totalDistance)
        }
        val lookAhead = findPositionByDistance(lookAheadDistance)
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
        lastUpdateTimeMs = null
        speedPhase = Random.nextDouble(0.0, Math.PI * 2)
        lateralPhase = Random.nextDouble(0.0, Math.PI * 2)
        lapCount = 0
    }

    fun isCompleted(): Boolean = isCompleted
    fun getLapCount(): Int = lapCount

    companion object {
        private const val MIN_ROUTE_DISTANCE_METERS = 8f
        private const val TARGET_SAMPLE_DISTANCE_METERS = 3f
        private const val LOOP_SMOOTHING_ITERATIONS = 4
        private const val OPEN_ROUTE_INTERPOLATION_STEPS = 20
        private const val POSITION_SMOOTHING_FACTOR = 0.52f
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
