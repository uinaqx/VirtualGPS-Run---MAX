package com.virtualrun.app.algorithm

import com.virtualrun.app.model.Route
import com.virtualrun.app.model.RoutePoint
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrajectoryInterpolatorTest {

    @Test
    fun startAndPaceChangeRemainAccelerationLimited() {
        val interpolator = TrajectoryInterpolator(straightRoute(), 6f, random = Random(7))
        var now = 1_000L
        val first = interpolator.calculateNextPosition(now)
        assertEquals(0f, first.speed, 0.001f)
        assertTrue("stationary first sample lost the route heading", first.bearing in 80f..100f)

        val speeds = mutableListOf(first.speed)
        repeat(8) {
            now += 1_000L
            speeds += interpolator.calculateNextPosition(now).speed
        }

        speeds.zipWithNext().forEach { (previous, next) ->
            assertTrue("speed jump was ${next - previous} m/s", next - previous <= 0.70f)
        }
        assertTrue("runner never reached a steady jog", speeds.last() > 2.2f)

        val beforePaceChange = speeds.last()
        interpolator.updatePace(4f)
        now += 1_000L
        val afterPaceChange = interpolator.calculateNextPosition(now).speed
        assertTrue(afterPaceChange - beforePaceChange <= 0.70f)
    }

    @Test
    fun requestedStopDeceleratesBeforeCompletion() {
        val interpolator = TrajectoryInterpolator(straightRoute(), 6f, random = Random(11))
        var now = 10_000L
        interpolator.calculateNextPosition(now)
        repeat(9) {
            now += 1_000L
            interpolator.calculateNextPosition(now)
        }

        interpolator.requestStop()
        val stoppingSpeeds = mutableListOf<Float>()
        var completed = false
        repeat(12) {
            now += 1_000L
            val result = interpolator.calculateNextPosition(now)
            stoppingSpeeds += result.speed
            completed = completed || result.isCompleted
            if (result.isCompleted) return@repeat
        }

        assertTrue("stop sequence did not complete", completed)
        assertEquals(0f, stoppingSpeeds.last(), 0.001f)
        assertTrue("manual stop incorrectly completed the route", interpolator.calculateNextPosition(now + 1_000L).progress < 1f)
        assertTrue("stop was instantaneous", stoppingSpeeds.first() > 0.5f)
        stoppingSpeeds.zipWithNext().forEach { (previous, next) ->
            assertTrue("deceleration exceeded the hard limit", previous - next <= 0.95f)
        }
    }

    @Test
    fun twoPointLegacyLoopReturnsContinuouslyInsteadOfTeleporting() {
        val start = RoutePoint(0.0, 0.0)
        val end = RoutePoint(0.0, 0.00045)
        val route = Route(listOf(start, end, start), 100f, 0L)
        val interpolator = TrajectoryInterpolator(route, 6f, isLoopMode = true, random = Random(19))

        var now = 100_000L
        var previous = interpolator.calculateNextPosition(now)
        var maximumStepMeters = 0f
        var sawSecondLap = false
        repeat(90) {
            now += 1_000L
            val next = interpolator.calculateNextPosition(now)
            maximumStepMeters = maxOf(
                maximumStepMeters,
                distanceMeters(previous.latitude, previous.longitude, next.latitude, next.longitude)
            )
            sawSecondLap = sawSecondLap || next.lapCount > 0
            previous = next
        }

        assertTrue("test route never crossed its loop seam", sawSecondLap)
        assertTrue("loop seam teleported $maximumStepMeters meters", maximumStepMeters < 12f)
        assertFalse(previous.isCompleted)
    }

    @Test
    fun sharpCornerIsApproachedMoreSlowlyAndRecoveredSmoothly() {
        val route = Route(
            points = listOf(
                RoutePoint(0.0, 0.0),
                RoutePoint(0.0, 0.001),
                RoutePoint(0.001, 0.001)
            ),
            totalDistance = 222f,
            totalDuration = 80L
        )
        val interpolator = TrajectoryInterpolator(route, 6f, random = Random(23))
        var now = 200_000L
        val samples = mutableListOf<PositionResult>()
        samples += interpolator.calculateNextPosition(now)
        repeat(75) {
            now += 1_000L
            samples += interpolator.calculateNextPosition(now)
        }

        val approachAverage = samples.filter { it.progress in 0.20f..0.38f }.map { it.speed }.average()
        val cornerMinimum = samples.filter { it.progress in 0.42f..0.60f }.minOf { it.speed }
        val recoveredAverage = samples.filter { it.progress in 0.65f..0.82f }.map { it.speed }.average()

        assertTrue("corner did not reduce speed: approach=$approachAverage corner=$cornerMinimum", cornerMinimum < approachAverage * 0.95)
        assertTrue("runner did not recover after the corner", recoveredAverage > cornerMinimum * 1.05)
    }

    @Test
    fun openRouteBrakesBeforePublishingFinalStationarySamples() {
        val shortRoute = Route(
            points = listOf(RoutePoint(0.0, 0.0), RoutePoint(0.0, 0.0009)),
            totalDistance = 100f,
            totalDuration = 36L
        )
        val interpolator = TrajectoryInterpolator(shortRoute, 6f, random = Random(29))
        var now = 300_000L
        val results = mutableListOf(interpolator.calculateNextPosition(now))
        repeat(80) {
            if (!results.last().isCompleted) {
                now += 1_000L
                results += interpolator.calculateNextPosition(now)
            }
        }

        assertTrue("open route never completed", results.last().isCompleted)
        assertEquals(0f, results.last().speed, 0.001f)
        assertEquals(0f, results[results.lastIndex - 1].speed, 0.001f)
        results.filter { it.progress >= 1f }.forEach {
            assertTrue("route reported 100% while still moving at ${it.speed} m/s", it.speed <= 0.2f)
        }
        results.zipWithNext().forEach { (previous, next) ->
            assertTrue(
                "endpoint speed changed from ${previous.speed} to ${next.speed}",
                previous.speed - next.speed <= 0.95f
            )
            val stepMeters = distanceMeters(previous.latitude, previous.longitude, next.latitude, next.longitude)
            assertTrue("endpoint caused a $stepMeters meter position jump", stepMeters < 12f)
        }
    }

    @Test
    fun openRoutesAlwaysReachTheEndpointAcrossSupportedPaces() {
        val paces = listOf(3f, 6f, 15f)
        val routeLengthsMeters = listOf(9f, 100f, 111f, 222f, 667f)

        paces.forEachIndexed { paceIndex, pace ->
            routeLengthsMeters.forEachIndexed { lengthIndex, lengthMeters ->
                val route = straightRoute(lengthMeters)
                val interpolator = TrajectoryInterpolator(
                    route,
                    pace,
                    random = Random(100 + paceIndex * 10 + lengthIndex)
                )
                var now = 400_000L
                var result = interpolator.calculateNextPosition(now)
                var previousSpeed = result.speed
                var hasMoved = false
                val expectedSeconds = lengthMeters / (1000f / (pace * 60f))
                val maximumSamples = ((expectedSeconds + 120f) * 4f).toInt()

                repeat(maximumSamples) {
                    if (!result.isCompleted) {
                        now += 250L
                        result = interpolator.calculateNextPosition(now)
                        assertTrue(
                            "endpoint deceleration jumped from $previousSpeed to ${result.speed}: pace=$pace length=$lengthMeters",
                            previousSpeed - result.speed <= 0.35f
                        )
                        previousSpeed = result.speed
                        hasMoved = hasMoved || result.speed > 0.12f
                        if (hasMoved && result.progress < 1f - 1.5f / lengthMeters) {
                            assertTrue(
                                "runner stopped before endpoint: pace=$pace length=$lengthMeters progress=${result.progress}",
                                result.speed > 0f
                            )
                        }
                    }
                }

                assertTrue(
                    "open route stalled: pace=$pace length=$lengthMeters progress=${result.progress} speed=${result.speed}",
                    result.isCompleted
                )
                assertEquals("completed route did not publish 100%", 1f, result.progress, 0.0001f)
                assertEquals("completed route was still moving", 0f, result.speed, 0.001f)

                val endpoint = route.points.last()
                val endpointErrorMeters = distanceMeters(
                    result.latitude,
                    result.longitude,
                    endpoint.lat,
                    endpoint.lng
                )
                assertTrue(
                    "endpoint observation was ${endpointErrorMeters}m away: pace=$pace length=$lengthMeters",
                    endpointErrorMeters < 6f
                )
            }
        }
    }

    private fun straightRoute(): Route {
        return Route(
            points = listOf(RoutePoint(0.0, 0.0), RoutePoint(0.0, 0.006)),
            totalDistance = 667f,
            totalDuration = 240L
        )
    }

    private fun straightRoute(lengthMeters: Float): Route {
        val longitudeDelta = lengthMeters / 111_194.93
        return Route(
            points = listOf(RoutePoint(0.0, 0.0), RoutePoint(0.0, longitudeDelta)),
            totalDistance = lengthMeters,
            totalDuration = 0L
        )
    }

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val radius = 6_371_000.0
        val latitudeDelta = Math.toRadians(lat2 - lat1)
        val longitudeDelta = Math.toRadians(lon2 - lon1)
        val a = sin(latitudeDelta / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(longitudeDelta / 2).pow(2)
        return (2 * radius * atan2(sqrt(a), sqrt(1 - a))).toFloat()
    }
}
