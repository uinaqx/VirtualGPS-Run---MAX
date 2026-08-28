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
 * 1. 闭环路线只在顶点附近做定长圆角，保留用户选择的直线路段
 * 2. 单调时钟、小步积分、加速度与 jerk 约束保证速度连续
 * 3. 提前感知弯道和终点，模拟弯前降速、弯后恢复与收尾减速
 * 4. 连续低频横向漂移和相关 GNSS 误差，避免锯齿与逐点白噪声
 * 5. 路径平滑层与 GPS 观测层相互独立，误差只叠加一次
 */
class TrajectoryInterpolator(
    route: Route,
    private var basePace: Float,  // 配速：分钟/公里
    private val isLoopMode: Boolean = false, // 是否循环跑
    private val random: Random = Random.Default
) {
    // 基础速度：米/秒
    private var baseSpeed: Float = calculateBaseSpeed(basePace)

    // 平滑后的密集点列表（含距离信息）
    private data class DensePoint(val lat: Double, val lng: Double, val distanceFromStart: Float)
    private val densePoints: List<DensePoint>
    private val totalDistance: Float

    // 当前状态
    private var currentDistance: Float = 0f
    private var totalTravelDistance: Float = 0f
    private var isCompleted: Boolean = false
    private var lastLatitude: Double = 0.0
    private var lastLongitude: Double = 0.0
    private var smoothedPathLatitude: Double = 0.0
    private var smoothedPathLongitude: Double = 0.0
    private var observedLatitude: Double = 0.0
    private var observedLongitude: Double = 0.0
    private var hasPreviousBasePosition = false
    private var hasSmoothedPathPosition = false
    private var hasObservedPosition = false
    private var hasSmoothedBearing = false
    private var smoothedBearing: Float = 0f
    private var lastUpdateTimeMs: Long? = null
    private var currentSpeed: Float = 0f
    private var currentAcceleration: Float = 0f
    private var stopRequested = false
    private var endpointBraking = false
    private var stationarySampleCount = 0

    // 连续扰动相位。只在启动时随机一次，后续使用连续函数，避免一跳一跳的假轨迹。
    private var lateralPhase = random.nextDouble(0.0, Math.PI * 2)
    private var slowSpeedNoise = 0.0
    private var fastSpeedNoise = 0.0
    private var gpsNorthErrorMeters = 0.0
    private var gpsEastErrorMeters = 0.0

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

    fun requestStop() {
        stopRequested = true
    }

    private fun calculateBaseSpeed(pace: Float): Float {
        return 1000f / (pace * 60)
    }

    fun calculateNextPosition(nowMs: Long = System.nanoTime() / 1_000_000L): PositionResult {
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

        val deltaSeconds = calculateDeltaSeconds(nowMs)
        if (!isCompleted) advanceMotion(deltaSeconds)

        val reachedOpenRouteEnd = !isLoopMode && currentDistance >= totalDistance - END_POSITION_TOLERANCE_METERS
        if ((stopRequested || reachedOpenRouteEnd) && currentSpeed <= STATIONARY_SPEED_THRESHOLD_MPS) {
            currentSpeed = 0f
            currentAcceleration = 0f
            stationarySampleCount++
            if (stationarySampleCount >= REQUIRED_STATIONARY_SAMPLES) isCompleted = true
        } else {
            stationarySampleCount = 0
        }

        val basePos = positionAtDistance(currentDistance)
        val baseStepMeters = if (hasPreviousBasePosition) {
            haversineDistance(lastLatitude, lastLongitude, basePos.latitude, basePos.longitude)
        } else 0f
        val movementBearing = if (hasPreviousBasePosition && baseStepMeters >= MIN_BEARING_STEP_METERS) {
            calculateBearing(lastLatitude, lastLongitude, basePos.latitude, basePos.longitude)
        } else {
            estimatePathBearing(currentDistance)
        }

        val naturalPosition = applyNaturalTrackVariation(basePos, movementBearing, nowMs)
        val smoothedPosition = smoothPathPosition(naturalPosition.latitude, naturalPosition.longitude, deltaSeconds)
        val noisyObservedPosition = applyGpsMeasurementNoise(smoothedPosition, deltaSeconds)
        val observedPosition = constrainCruiseObservationSpeed(
            noisyObservedPosition,
            movementBearing,
            deltaSeconds
        )
        val targetBearing = if (hasObservedPosition && currentSpeed >= BEARING_FROM_OBSERVATION_MIN_SPEED_MPS) {
            val observedBearing = calculateBearing(
                observedLatitude,
                observedLongitude,
                observedPosition.latitude,
                observedPosition.longitude
            )
            blendBearings(movementBearing, observedBearing, OBSERVED_BEARING_WEIGHT)
        } else {
            movementBearing
        }
        val finalBearing = smoothBearing(targetBearing)

        smoothedPathLatitude = smoothedPosition.latitude
        smoothedPathLongitude = smoothedPosition.longitude
        observedLatitude = observedPosition.latitude
        observedLongitude = observedPosition.longitude
        lastLatitude = basePos.latitude
        lastLongitude = basePos.longitude
        hasSmoothedPathPosition = true
        hasObservedPosition = true
        hasPreviousBasePosition = true

        return PositionResult(
            latitude = observedPosition.latitude,
            longitude = observedPosition.longitude,
            speed = currentSpeed,
            bearing = finalBearing,
            isCompleted = isCompleted,
            progress = when {
                !isLoopMode && reachedOpenRouteEnd && currentSpeed <= STATIONARY_SPEED_THRESHOLD_MPS -> 1f
                !isLoopMode && currentSpeed > STATIONARY_SPEED_THRESHOLD_MPS ->
                    (currentDistance / totalDistance).coerceIn(0f, 0.999f)
                else -> (currentDistance / totalDistance).coerceIn(0f, 1f)
            },
            lapCount = lapCount
        )
    }

    private fun calculateDeltaSeconds(now: Long): Float {
        val last = lastUpdateTimeMs
        lastUpdateTimeMs = now
        if (last == null) return 0f
        return ((now - last) / 1000f).coerceIn(0f, MAX_ELAPSED_SECONDS)
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
            // 兼容旧数据：两点循环按 A→B→A 往返，绝不能在 B 处瞬移回 A。
            return resampleByDistance(points + points.asReversed().drop(1), TARGET_SAMPLE_DISTANCE_METERS)
        }

        val rounded = roundClosedCorners(points)
        return resampleByDistance(rounded + rounded.first(), TARGET_SAMPLE_DISTANCE_METERS)
    }

    private fun generateOpenRoutePoints(points: List<RoutePoint>): List<DensePoint> {
        if (points.size < 2) return points.map { DensePoint(it.lat, it.lng, 0f) }
        if (points.size == 2) return resampleByDistance(points, TARGET_SAMPLE_DISTANCE_METERS)
        return resampleByDistance(roundOpenCorners(points), TARGET_SAMPLE_DISTANCE_METERS)
    }

    private fun roundOpenCorners(points: List<RoutePoint>): List<RoutePoint> {
        val result = mutableListOf(points.first())
        for (i in 1 until points.lastIndex) {
            val previous = points[i - 1]
            val vertex = points[i]
            val next = points[i + 1]
            val previousDistance = haversineDistance(previous.lat, previous.lng, vertex.lat, vertex.lng)
            val nextDistance = haversineDistance(vertex.lat, vertex.lng, next.lat, next.lng)
            if (previousDistance < 0.5f || nextDistance < 0.5f) {
                result.add(vertex)
                continue
            }

            val trimMeters = min(
                CORNER_TRIM_METERS,
                min(previousDistance * MAX_CORNER_EDGE_FRACTION, nextDistance * MAX_CORNER_EDGE_FRACTION)
            )
            val entrance = interpolateRoutePoint(vertex, previous, trimMeters / previousDistance)
            val exit = interpolateRoutePoint(vertex, next, trimMeters / nextDistance)
            result.add(entrance)
            for (step in 1..CORNER_CURVE_STEPS) {
                val t = step.toDouble() / CORNER_CURVE_STEPS
                val inverse = 1.0 - t
                result.add(
                    RoutePoint(
                        lat = inverse * inverse * entrance.lat + 2.0 * inverse * t * vertex.lat + t * t * exit.lat,
                        lng = inverse * inverse * entrance.lng + 2.0 * inverse * t * vertex.lng + t * t * exit.lng
                    )
                )
            }
        }
        result.add(points.last())
        return result
    }

    /**
     * 只在每个顶点附近做固定距离圆角，保留用户画出的长直线。
     * 全局 Chaikin 会按边长比例切角：边越长，圆角越大，数百米街区会被切成大圆弧。
     */
    private fun roundClosedCorners(points: List<RoutePoint>): List<RoutePoint> {
        val result = mutableListOf<RoutePoint>()
        for (i in points.indices) {
            val previous = points[(i - 1 + points.size) % points.size]
            val vertex = points[i]
            val next = points[(i + 1) % points.size]
            val previousDistance = haversineDistance(previous.lat, previous.lng, vertex.lat, vertex.lng)
            val nextDistance = haversineDistance(vertex.lat, vertex.lng, next.lat, next.lng)

            if (previousDistance < 0.5f || nextDistance < 0.5f) {
                result.add(vertex)
                continue
            }

            val trimMeters = min(
                CORNER_TRIM_METERS,
                min(previousDistance * MAX_CORNER_EDGE_FRACTION, nextDistance * MAX_CORNER_EDGE_FRACTION)
            )
            val entrance = interpolateRoutePoint(vertex, previous, trimMeters / previousDistance)
            val exit = interpolateRoutePoint(vertex, next, trimMeters / nextDistance)
            result.add(entrance)

            for (step in 1..CORNER_CURVE_STEPS) {
                val t = step.toDouble() / CORNER_CURVE_STEPS
                val inverse = 1.0 - t
                result.add(
                    RoutePoint(
                        lat = inverse * inverse * entrance.lat + 2.0 * inverse * t * vertex.lat + t * t * exit.lat,
                        lng = inverse * inverse * entrance.lng + 2.0 * inverse * t * vertex.lng + t * t * exit.lng
                    )
                )
            }
        }
        return result
    }

    private fun interpolateRoutePoint(start: RoutePoint, end: RoutePoint, ratio: Float): RoutePoint {
        val boundedRatio = ratio.coerceIn(0f, 1f).toDouble()
        return RoutePoint(
            lat = start.lat + (end.lat - start.lat) * boundedRatio,
            lng = start.lng + (end.lng - start.lng) * boundedRatio
        )
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

    private fun haversineDistance(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Float {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return (r * c).toFloat()
    }

    // ==================== 速度与轨迹扰动 ====================

    /**
     * 使用不超过 250ms 的内部步长积分。即使服务某一帧延迟，速度也不会跨越加速度约束。
     */
    private fun advanceMotion(deltaSeconds: Float) {
        if (deltaSeconds <= 0f) return

        var remainingSeconds = deltaSeconds
        while (remainingSeconds > 0.0001f) {
            val stepSeconds = min(INTEGRATION_STEP_SECONDS, remainingSeconds)

            val desiredSpeed = calculateDesiredSpeed(stepSeconds)
            val previousSpeed = currentSpeed
            updateSpeedWithMotionLimits(desiredSpeed, stepSeconds)

            var distanceIncrement = (previousSpeed + currentSpeed) * 0.5f * stepSeconds
            if (!isLoopMode) {
                val remainingDistance = (totalDistance - currentDistance).coerceAtLeast(0f)
                if (
                    endpointBraking &&
                    distanceIncrement >= remainingDistance &&
                    currentSpeed <= STATIONARY_SPEED_THRESHOLD_MPS
                ) {
                    // Only settle on the exact endpoint once the motion controller is already stationary.
                    // Never bypass acceleration/jerk limits by forcing a moving sample directly to zero.
                    distanceIncrement = remainingDistance
                    currentSpeed = 0f
                    currentAcceleration = 0f
                } else {
                    distanceIncrement = distanceIncrement.coerceAtMost(remainingDistance)
                }
            }
            currentDistance += distanceIncrement
            totalTravelDistance += distanceIncrement

            if (isLoopMode && currentDistance >= totalDistance) {
                val completedLaps = floor(currentDistance / totalDistance).toInt().coerceAtLeast(1)
                currentDistance %= totalDistance
                lapCount += completedLaps
            }

            remainingSeconds -= stepSeconds
        }
    }

    private fun calculateDesiredSpeed(deltaSeconds: Float): Float {
        updateCorrelatedSpeedNoise(deltaSeconds)
        if (stopRequested) return 0f

        val variedCruiseSpeed = baseSpeed *
            (1f + (slowSpeedNoise + fastSpeedNoise).toFloat()).coerceIn(
                1f - MAX_CRUISE_NOISE_RATIO,
                1f + MAX_CRUISE_NOISE_RATIO
            )
        val turnFactor = 1f - MAX_CORNER_SPEED_REDUCTION * previewTurnSeverity(currentDistance)
        var desiredSpeed = (variedCruiseSpeed * turnFactor).coerceIn(
            baseSpeed * MIN_CRUISE_SPEED_RATIO,
            baseSpeed * MAX_CRUISE_SPEED_RATIO
        )

        if (!isLoopMode) {
            val remainingDistance = (totalDistance - currentDistance).coerceAtLeast(0f)
            if (!endpointBraking) {
                val decisionGuardDistance =
                    currentSpeed * INTEGRATION_STEP_SECONDS +
                        0.5f * MAX_ACCELERATION_MPS2 * INTEGRATION_STEP_SECONDS * INTEGRATION_STEP_SECONDS
                val predictedStoppingDistance = predictStoppingDistance() + decisionGuardDistance
                endpointBraking = remainingDistance <= predictedStoppingDistance + ENDPOINT_MARGIN_METERS
            }
            if (endpointBraking) {
                return 0f
            }
        }

        return desiredSpeed.coerceAtLeast(0f)
    }

    /**
     * Predict the distance needed to stop with the exact same response, acceleration and jerk limits used
     * by the live integrator. This avoids both premature stops and a last-frame hard snap at short routes.
     */
    private fun predictStoppingDistance(): Float {
        var simulatedSpeed = currentSpeed
        var simulatedAcceleration = currentAcceleration
        var stoppingDistance = 0f

        repeat(MAX_STOPPING_PREDICTION_STEPS) {
            if (simulatedSpeed <= 0f) return stoppingDistance

            val commandedAcceleration = (-simulatedSpeed / DECELERATION_RESPONSE_SECONDS).coerceIn(
                -MAX_DECELERATION_MPS2,
                MAX_ACCELERATION_MPS2
            )
            val maximumAccelerationChange = MAX_JERK_MPS3 * INTEGRATION_STEP_SECONDS
            simulatedAcceleration += (commandedAcceleration - simulatedAcceleration).coerceIn(
                -maximumAccelerationChange,
                maximumAccelerationChange
            )
            simulatedAcceleration = simulatedAcceleration.coerceIn(
                -MAX_DECELERATION_MPS2,
                MAX_ACCELERATION_MPS2
            )

            val previousSpeed = simulatedSpeed
            simulatedSpeed = (simulatedSpeed + simulatedAcceleration * INTEGRATION_STEP_SECONDS)
                .coerceAtLeast(0f)
            if (simulatedSpeed < STATIONARY_SPEED_THRESHOLD_MPS) {
                simulatedSpeed = 0f
                simulatedAcceleration = 0f
            }
            stoppingDistance +=
                (previousSpeed + simulatedSpeed) * 0.5f * INTEGRATION_STEP_SECONDS
        }

        return stoppingDistance
    }

    private fun updateCorrelatedSpeedNoise(deltaSeconds: Float) {
        slowSpeedNoise = evolveOrnsteinUhlenbeck(
            slowSpeedNoise,
            deltaSeconds,
            SLOW_SPEED_NOISE_TIME_CONSTANT_SECONDS,
            SLOW_SPEED_NOISE_STANDARD_DEVIATION
        )
        fastSpeedNoise = evolveOrnsteinUhlenbeck(
            fastSpeedNoise,
            deltaSeconds,
            FAST_SPEED_NOISE_TIME_CONSTANT_SECONDS,
            FAST_SPEED_NOISE_STANDARD_DEVIATION
        )
    }

    private fun evolveOrnsteinUhlenbeck(
        current: Double,
        deltaSeconds: Float,
        timeConstantSeconds: Double,
        standardDeviation: Double
    ): Double {
        if (deltaSeconds <= 0f) return current
        val alpha = exp(-deltaSeconds / timeConstantSeconds)
        val innovation = standardDeviation * sqrt(1.0 - alpha * alpha) * randomGaussian()
        return current * alpha + innovation
    }

    private fun updateSpeedWithMotionLimits(desiredSpeed: Float, deltaSeconds: Float) {
        val responseTime = if (desiredSpeed >= currentSpeed) ACCELERATION_RESPONSE_SECONDS else DECELERATION_RESPONSE_SECONDS
        val commandedAcceleration = ((desiredSpeed - currentSpeed) / responseTime).coerceIn(
            -MAX_DECELERATION_MPS2,
            MAX_ACCELERATION_MPS2
        )
        val maximumAccelerationChange = MAX_JERK_MPS3 * deltaSeconds
        currentAcceleration += (commandedAcceleration - currentAcceleration).coerceIn(
            -maximumAccelerationChange,
            maximumAccelerationChange
        )
        currentAcceleration = currentAcceleration.coerceIn(-MAX_DECELERATION_MPS2, MAX_ACCELERATION_MPS2)

        currentSpeed = (currentSpeed + currentAcceleration * deltaSeconds).coerceAtLeast(0f)
        if (desiredSpeed <= 0f && currentSpeed < STATIONARY_SPEED_THRESHOLD_MPS) {
            currentSpeed = 0f
            currentAcceleration = 0f
        }
    }

    /**
     * 比较当前位置切线与未来 4/8/12/15 米处切线，提前识别即将到来的转弯。
     */
    private fun previewTurnSeverity(distance: Float): Float {
        val currentBearing = pathBearingAt(distance)
        var maximumAngle = 0f
        TURN_LOOKAHEAD_SAMPLES_METERS.forEach { lookAhead ->
            if (!isLoopMode && distance + lookAhead >= totalDistance) return@forEach
            val futureBearing = pathBearingAt(distance + lookAhead)
            maximumAngle = max(maximumAngle, smallestBearingDifference(currentBearing, futureBearing))
        }

        val normalized = ((maximumAngle - TURN_RESPONSE_START_DEGREES) /
            (TURN_RESPONSE_FULL_DEGREES - TURN_RESPONSE_START_DEGREES)).coerceIn(0f, 1f)
        return normalized * normalized * (3f - 2f * normalized)
    }

    private fun pathBearingAt(distance: Float): Float {
        val before = positionAtDistance(distance - PATH_TANGENT_HALF_WINDOW_METERS)
        val after = positionAtDistance(distance + PATH_TANGENT_HALF_WINDOW_METERS)
        return calculateBearing(before.latitude, before.longitude, after.latitude, after.longitude)
    }

    private fun smallestBearingDifference(first: Float, second: Float): Float {
        return abs((second - first + 540f) % 360f - 180f)
    }

    private fun blendBearings(primary: Float, secondary: Float, secondaryWeight: Float): Float {
        val delta = (secondary - primary + 540f) % 360f - 180f
        return (primary + delta * secondaryWeight + 360f) % 360f
    }

    /**
     * 真人绕圈不会走成尖锐折线，也不会每圈完全重合。
     * 这里使用连续的横向漂移：每圈有不同的轻微内外偏移，再叠加低频波动。
     */
    private fun applyNaturalTrackVariation(position: TargetPosition, bearing: Float, now: Long): TargetPosition {
        val distance = totalTravelDistance.toDouble()
        val timeSec = now / 1000.0
        val completedLaps = if (totalDistance > 0f) totalTravelDistance / totalDistance else 0f
        val lapPhase = lateralPhase + completedLaps * 0.9

        val lateralMeters = if (isLoopMode) {
            val lapLaneOffset = sin(completedLaps * Math.PI * 0.74 + lateralPhase) * 0.8
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

    /**
     * GNSS 误差不是逐点白噪声，而是会在数秒内保持方向后缓慢回归。
     * 使用二维 Ornstein-Uhlenbeck 过程生成连续的米级观测漂移。
     */
    private fun applyGpsMeasurementNoise(position: TargetPosition, deltaSeconds: Float): TargetPosition {
        if (deltaSeconds > 0f) {
            val alpha = exp(-deltaSeconds / GPS_ERROR_TIME_CONSTANT_SECONDS)
            val innovationScale = GPS_ERROR_STANDARD_DEVIATION_METERS * sqrt(1.0 - alpha * alpha)
            gpsNorthErrorMeters = gpsNorthErrorMeters * alpha + innovationScale * randomGaussian()
            gpsEastErrorMeters = gpsEastErrorMeters * alpha + innovationScale * randomGaussian()
        }

        var observed = position
        if (abs(gpsNorthErrorMeters) > 0.01) {
            observed = calculateLocationOffset(
                observed.latitude,
                observed.longitude,
                abs(gpsNorthErrorMeters),
                if (gpsNorthErrorMeters >= 0) 0.0 else 180.0
            )
        }
        if (abs(gpsEastErrorMeters) > 0.01) {
            observed = calculateLocationOffset(
                observed.latitude,
                observed.longitude,
                abs(gpsEastErrorMeters),
                if (gpsEastErrorMeters >= 0) 90.0 else 270.0
            )
        }
        return observed
    }

    /**
     * Keep coordinate-derived cruise speed inside the same ±15% band as the reported speed. GPS drift is
     * still free to change direction, but it cannot make adjacent observations imply an implausible surge
     * or slowdown. Start-up, requested stopping and natural endpoint braking retain their real transitions.
     */
    private fun constrainCruiseObservationSpeed(
        candidate: TargetPosition,
        fallbackBearing: Float,
        deltaSeconds: Float
    ): TargetPosition {
        if (
            !hasObservedPosition ||
            deltaSeconds <= 0f ||
            stopRequested ||
            endpointBraking
        ) {
            return candidate
        }

        val minimumCruiseSpeed = baseSpeed * MIN_CRUISE_SPEED_RATIO
        val maximumCruiseSpeed = baseSpeed * MAX_CRUISE_SPEED_RATIO
        if (currentSpeed !in minimumCruiseSpeed..maximumCruiseSpeed) return candidate

        val observedStepMeters = haversineDistance(
            observedLatitude,
            observedLongitude,
            candidate.latitude,
            candidate.longitude
        )
        // Keep a tiny guard inside the public band to absorb the difference between the spherical
        // distance formula and the WGS-84 radius used when applying the offset.
        val minimumStepMeters = minimumCruiseSpeed * deltaSeconds * (1f + OBSERVATION_BAND_GUARD_RATIO)
        val maximumStepMeters = maximumCruiseSpeed * deltaSeconds * (1f - OBSERVATION_BAND_GUARD_RATIO)
        val boundedStepMeters = observedStepMeters.coerceIn(minimumStepMeters, maximumStepMeters)
        if (abs(boundedStepMeters - observedStepMeters) < 0.001f) return candidate

        val stepBearing = if (observedStepMeters >= MIN_BEARING_STEP_METERS) {
            calculateBearing(
                observedLatitude,
                observedLongitude,
                candidate.latitude,
                candidate.longitude
            )
        } else {
            fallbackBearing
        }
        return calculateLocationOffset(
            observedLatitude,
            observedLongitude,
            boundedStepMeters.toDouble(),
            stepBearing.toDouble()
        )
    }

    private fun randomGaussian(): Double {
        val first = random.nextDouble().coerceAtLeast(1e-12)
        val second = random.nextDouble()
        return sqrt(-2.0 * ln(first)) * cos(2.0 * Math.PI * second)
    }

    private fun calculateBearing(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Float {
        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)
        val deltaLng = Math.toRadians(lng2 - lng1)
        val y = sin(deltaLng) * cos(lat2Rad)
        val x = cos(lat1Rad) * sin(lat2Rad) - sin(lat1Rad) * cos(lat2Rad) * cos(deltaLng)
        var bearing = Math.toDegrees(atan2(y, x)).toFloat()
        bearing = (bearing + 360) % 360
        return bearing
    }

    private fun positionAtDistance(targetDistance: Float): TargetPosition {
        if (densePoints.isEmpty()) return TargetPosition(0.0, 0.0)
        val normalizedDistance = if (isLoopMode && totalDistance > 0f) {
            ((targetDistance % totalDistance) + totalDistance) % totalDistance
        } else {
            targetDistance.coerceIn(0f, totalDistance)
        }

        var low = 0
        var high = densePoints.lastIndex
        while (low <= high) {
            val middle = (low + high) ushr 1
            if (densePoints[middle].distanceFromStart < normalizedDistance) low = middle + 1 else high = middle - 1
        }

        if (low <= 0) return TargetPosition(densePoints.first().lat, densePoints.first().lng)
        if (low >= densePoints.size) return TargetPosition(densePoints.last().lat, densePoints.last().lng)

        val previous = densePoints[low - 1]
        val next = densePoints[low]
        val segmentLength = next.distanceFromStart - previous.distanceFromStart
        val ratio = if (segmentLength > 0f) (normalizedDistance - previous.distanceFromStart) / segmentLength else 0f
        return TargetPosition(
            previous.lat + (next.lat - previous.lat) * ratio,
            previous.lng + (next.lng - previous.lng) * ratio
        )
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
        return pathBearingAt(distance)
    }

    private fun smoothPathPosition(targetLat: Double, targetLng: Double, deltaSeconds: Float): TargetPosition {
        if (!hasSmoothedPathPosition || deltaSeconds <= 0f) {
            return TargetPosition(targetLat, targetLng)
        }

        val factor = (1.0 - exp(-deltaSeconds / POSITION_SMOOTHING_TIME_CONSTANT_SECONDS)).coerceIn(0.0, 1.0)
        val lat = smoothedPathLatitude + (targetLat - smoothedPathLatitude) * factor
        val lng = smoothedPathLongitude + (targetLng - smoothedPathLongitude) * factor
        return TargetPosition(lat, lng)
    }

    private fun smoothBearing(targetBearing: Float): Float {
        if (!hasSmoothedBearing) {
            smoothedBearing = targetBearing
            hasSmoothedBearing = true
            return targetBearing
        }

        var delta = (targetBearing - smoothedBearing + 540f) % 360f - 180f
        delta = delta.coerceIn(-MAX_BEARING_STEP_DEGREES, MAX_BEARING_STEP_DEGREES)
        smoothedBearing = (smoothedBearing + delta * BEARING_SMOOTHING_FACTOR + 360f) % 360f
        return smoothedBearing
    }

    fun reset() {
        currentDistance = 0f
        totalTravelDistance = 0f
        isCompleted = false
        lastLatitude = 0.0
        lastLongitude = 0.0
        smoothedPathLatitude = 0.0
        smoothedPathLongitude = 0.0
        observedLatitude = 0.0
        observedLongitude = 0.0
        hasPreviousBasePosition = false
        hasSmoothedPathPosition = false
        hasObservedPosition = false
        hasSmoothedBearing = false
        smoothedBearing = 0f
        lastUpdateTimeMs = null
        currentSpeed = 0f
        currentAcceleration = 0f
        stopRequested = false
        endpointBraking = false
        stationarySampleCount = 0
        lateralPhase = random.nextDouble(0.0, Math.PI * 2)
        slowSpeedNoise = 0.0
        fastSpeedNoise = 0.0
        gpsNorthErrorMeters = 0.0
        gpsEastErrorMeters = 0.0
        lapCount = 0
    }

    fun isCompleted(): Boolean = isCompleted
    fun isStopping(): Boolean = stopRequested
    fun getLapCount(): Int = lapCount

    companion object {
        private const val MIN_ROUTE_DISTANCE_METERS = 8f
        private const val TARGET_SAMPLE_DISTANCE_METERS = 3f
        private const val CORNER_TRIM_METERS = 8f
        private const val MAX_CORNER_EDGE_FRACTION = 0.2f
        private const val CORNER_CURVE_STEPS = 8
        private const val POSITION_SMOOTHING_TIME_CONSTANT_SECONDS = 0.58
        private const val BEARING_SMOOTHING_FACTOR = 0.45f
        private const val MAX_BEARING_STEP_DEGREES = 45f
        private const val PATH_TANGENT_HALF_WINDOW_METERS = 2.5f
        private val TURN_LOOKAHEAD_SAMPLES_METERS = floatArrayOf(4f, 8f, 12f, 15f)
        private const val TURN_RESPONSE_START_DEGREES = 12f
        private const val TURN_RESPONSE_FULL_DEGREES = 85f
        private const val MIN_CRUISE_SPEED_RATIO = 0.85f
        private const val MAX_CRUISE_SPEED_RATIO = 1.15f
        private const val MAX_CRUISE_NOISE_RATIO = 0.05f
        private const val OBSERVATION_BAND_GUARD_RATIO = 0.002f
        private const val MAX_CORNER_SPEED_REDUCTION = 0.08f
        private const val SLOW_SPEED_NOISE_TIME_CONSTANT_SECONDS = 32.0
        private const val SLOW_SPEED_NOISE_STANDARD_DEVIATION = 0.025
        private const val FAST_SPEED_NOISE_TIME_CONSTANT_SECONDS = 6.0
        private const val FAST_SPEED_NOISE_STANDARD_DEVIATION = 0.011
        private const val INTEGRATION_STEP_SECONDS = 0.10f
        private const val MAX_ELAPSED_SECONDS = 5f
        private const val MAX_ACCELERATION_MPS2 = 0.65f
        private const val MAX_DECELERATION_MPS2 = 0.90f
        private const val MAX_JERK_MPS3 = 0.72f
        private const val ACCELERATION_RESPONSE_SECONDS = 2.8f
        private const val DECELERATION_RESPONSE_SECONDS = 1.8f
        private const val ENDPOINT_MARGIN_METERS = 0.45f
        private const val MAX_STOPPING_PREDICTION_STEPS = 600
        private const val END_POSITION_TOLERANCE_METERS = 1.5f
        private const val STATIONARY_SPEED_THRESHOLD_MPS = 0.12f
        private const val REQUIRED_STATIONARY_SAMPLES = 2
        private const val MIN_BEARING_STEP_METERS = 0.15f
        private const val BEARING_FROM_OBSERVATION_MIN_SPEED_MPS = 1.2f
        private const val OBSERVED_BEARING_WEIGHT = 0.18f
        private const val GPS_ERROR_TIME_CONSTANT_SECONDS = 10.0
        private const val GPS_ERROR_STANDARD_DEVIATION_METERS = 0.45
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
