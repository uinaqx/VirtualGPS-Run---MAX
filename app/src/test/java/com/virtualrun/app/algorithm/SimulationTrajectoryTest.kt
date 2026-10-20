package com.virtualrun.app.algorithm

import com.virtualrun.app.model.Route
import com.virtualrun.app.model.RoutePoint
import com.virtualrun.app.model.SimulationMetricsCalculator
import com.virtualrun.app.model.SimulationProfile
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SimulationTrajectoryTest {
    @Test
    fun varyingConstantPaceKeepsMinuteAveragesAcrossLoopsAndDelayedTicks() {
        val route = Route(listOf(RoutePoint(0.0, 0.0), RoutePoint(0.0, 0.001)), 111f, 0L)
        val targetSpeed = 1_000f / 300f
        for (interval in listOf(100L, 200L, 1_000L)) {
            val engine = TrajectoryInterpolator(route, 5f, true, Random(1),
                uniformPace = true, paceVariationRatio = 0.025f)
            var previous = engine.calculateNextPosition(100_000L)
            var minimumSpeed = Float.MAX_VALUE
            var maximumSpeed = 0f
            for (elapsed in interval..360_000L step interval) {
                val current = engine.calculateNextPosition(100_000L + elapsed)
                assertFalse(current.isCompleted)
                assertTrue(current.distanceMeters >= previous.distanceMeters)
                assertTrue(current.speed in targetSpeed * 0.9749f..targetSpeed * 1.0251f)
                minimumSpeed = minOf(minimumSpeed, current.speed)
                maximumSpeed = maxOf(maximumSpeed, current.speed)
                if (elapsed % 60_000L == 0L) {
                    assertEquals(targetSpeed.toDouble() * elapsed / 1_000.0,
                        current.distanceMeters.toDouble(), 0.0003)
                }
                previous = current
            }
            assertTrue(previous.lapCount >= 4)
            assertTrue(maximumSpeed - minimumSpeed > 0.13f)
            // Catch up an entire two minutes without losing time or changing the average.
            val delayed = engine.calculateNextPosition(580_000L)
            assertEquals(480_000L, delayed.elapsedMillis)
            assertEquals(targetSpeed.toDouble() * 480.0, delayed.distanceMeters.toDouble(), 0.0003)
        }
    }

    @Test
    fun uniformConstantPaceKeepsExactSpeedAndDistanceAcrossFiveLapsAtEveryUpdateRate() {
        val points = listOf(
            RoutePoint(0.0, 0.0), RoutePoint(0.0, 0.0012),
            RoutePoint(0.0008, 0.0012), RoutePoint(0.0008, 0.0),
            RoutePoint(0.0, 0.0)
        )
        val targetSpeed = 1_000f / (5f * 60f)
        for (interval in listOf(100L, 200L, 1_000L)) {
            val engine = TrajectoryInterpolator(Route(points, 412f, 0L), 5f, true,
                Random(117), uniformPace = true)
            var previous = engine.calculateNextPosition(100_000L)
            assertEquals(targetSpeed, previous.speed, 0f)
            assertEquals(0f, previous.distanceMeters, 0f)
            var elapsed = 0L
            var minimumChordSpeedRatio = 1.0
            var maximumChordSpeedRatio = 0.0
            while (previous.lapCount < 5 && elapsed < 900_000L) {
                elapsed += interval
                val current = engine.calculateNextPosition(100_000L + elapsed)
                val description = "interval=$interval elapsed=$elapsed lap=${current.lapCount}"
                assertFalse("uniform loop incorrectly ended: $description", current.isCompleted)
                assertEquals("reported speed changed: $description", targetSpeed, current.speed, 0f)
                assertEquals(elapsed, current.elapsedMillis)
                assertEquals("travel distance left the fixed pace: $description",
                    targetSpeed.toDouble() * elapsed / 1_000.0, current.distanceMeters.toDouble(), 0.0003)
                assertEquals("per-tick path distance changed: $description",
                    targetSpeed * interval / 1_000f,
                    current.distanceMeters - previous.distanceMeters, 0.0003f)
                val chordSpeedRatio = distanceMeters(previous, current) * 1_000.0 / interval / targetSpeed
                minimumChordSpeedRatio = minOf(minimumChordSpeedRatio, chordSpeedRatio)
                maximumChordSpeedRatio = maxOf(maximumChordSpeedRatio, chordSpeedRatio)
                // A GPS pair measures the chord, which is slightly shorter than the rounded path.
                assertTrue("coordinate speed left the route chord tolerance: $chordSpeedRatio, $description",
                    chordSpeedRatio in 0.94..1.001)
                if (current.lapCount > previous.lapCount) {
                    assertEquals(previous.lapCount + 1, current.lapCount)
                    assertTrue(previous.progress > 0.98f && current.progress < 0.02f)
                }
                previous = current
            }
            assertEquals(5, previous.lapCount)
        }
    }

    @Test
    fun uniformPaceHasNoRandomCoordinateDriftAndAccountsForDelayedTicks() {
        val route = Route(listOf(RoutePoint(0.0, 0.0), RoutePoint(0.0, 0.001)), 111f, 0L)
        val first = TrajectoryInterpolator(route, 5f, true, Random(1), uniformPace = true)
        val second = TrajectoryInterpolator(route, 5f, true, Random(2), uniformPace = true)
        for (time in 100_000L..160_000L step 200L) {
            assertEquals(first.calculateNextPosition(time), second.calculateNextPosition(time))
        }
        // The ordinary motion model caps catch-up at five seconds; fixed pace must use all twenty.
        val delayed = first.calculateNextPosition(180_000L)
        assertEquals(80_000L, delayed.elapsedMillis)
        assertEquals((1_000f / 300f).toDouble() * 80.0, delayed.distanceMeters.toDouble(), 0.0001)
        assertEquals(1_000f / 300f, delayed.speed, 0f)
        assertFalse(delayed.isCompleted)
    }

    @Test
    fun uniformOpenRouteKeepsItsPaceUntilItFinishesAtTheEndpoint() {
        val route = Route(listOf(RoutePoint(0.0, 0.0), RoutePoint(0.0, 0.001)), 111f, 0L)
        val engine = TrajectoryInterpolator(route, 5f, uniformPace = true)
        val targetSpeed = 1_000f / 300f
        var previous = engine.calculateNextPosition(100_000L)
        var elapsed = 0L
        while (!previous.isCompleted && elapsed < 60_000L) {
            assertEquals(targetSpeed, previous.speed, 0f)
            elapsed += 200L
            val current = engine.calculateNextPosition(100_000L + elapsed)
            assertTrue(current.distanceMeters >= previous.distanceMeters)
            if (current.isCompleted) {
                assertEquals(0f, current.speed, 0f)
                assertEquals(1f, current.progress, 0f)
                assertEquals(0.0, current.latitude, 1e-10)
                assertEquals(0.001, current.longitude, 1e-10)
                assertTrue(current.distanceMeters - previous.distanceMeters <= targetSpeed * 0.2f + 0.0001f)
            }
            previous = current
        }
        assertTrue("open route never finished", previous.isCompleted)
        assertEquals(111.1949, previous.distanceMeters.toDouble(), 0.01)
    }

    @Test
    fun fiveLapSeamsKeepCoordinatesSpeedTimeAndCumulativeDistanceContinuous() {
        // Synthetic rectangle near the origin, independent of any recorded route.
        val points = listOf(
            RoutePoint(0.0, 0.0), RoutePoint(0.0, 0.0012),
            RoutePoint(0.0008, 0.0012), RoutePoint(0.0008, 0.0),
            RoutePoint(0.0, 0.0)
        )
        for (pace in listOf(3f, 5f, 15f)) {
            for (interval in listOf(100L, 200L, 1_000L)) {
                val engine = TrajectoryInterpolator(Route(points, 412f, 0L), pace, true, Random(117))
                val targetSpeed = 1_000f / (pace * 60f)
                var previous = engine.calculateNextPosition(100_000L)
                var elapsed = 0L
                var crossings = 0
                var maximumSeamStep = 0.0
                var maximumSeamSpeedChange = 0f
                // Allow a generous bound independent of route smoothing.
                val timeoutMillis = (3_000f / targetSpeed * 1_000f).toLong()
                while (crossings < 5 && elapsed < timeoutMillis) {
                    elapsed += interval
                    val current = engine.calculateNextPosition(100_000L + elapsed)
                    val description = "pace=$pace interval=$interval elapsed=$elapsed lap=${current.lapCount}"
                    assertFalse("loop incorrectly completed: $description", current.isCompleted)
                    assertFalse("engine incorrectly completed: $description", engine.isCompleted())
                    assertEquals(elapsed, current.elapsedMillis)
                    assertEquals(interval, current.elapsedMillis - previous.elapsedMillis)
                    assertTrue("total distance reset: $description", current.distanceMeters >= previous.distanceMeters)
                    assertTrue("unwrapped progress reversed: $description",
                        current.lapCount + current.progress >= previous.lapCount + previous.progress)
                    val distanceIncrement = current.distanceMeters - previous.distanceMeters
                    assertTrue("total distance jumped: $description",
                        distanceIncrement <= targetSpeed * 1.16f * interval / 1_000f + 0.005f)
                    val stepMeters = distanceMeters(previous, current)
                    if (elapsed >= 5_000L) {
                        val coordinateSpeed = stepMeters * 1_000.0 / interval
                        // The observation limiter deliberately ignores corrections below 1 mm.
                        val speedTolerance = 0.0011 * 1_000.0 / interval
                        assertTrue("coordinate speed discontinuity: $coordinateSpeed, $description",
                            coordinateSpeed in targetSpeed * 0.85 - speedTolerance..
                                targetSpeed * 1.15 + speedTolerance)
                    }
                    if (current.lapCount != previous.lapCount) {
                        crossings++
                        assertEquals(previous.lapCount + 1, current.lapCount)
                        assertTrue("single-lap progress did not wrap: $description",
                            previous.progress > 0.95f && current.progress < 0.05f)
                        assertTrue("speed dropped at lap boundary: $description",
                            current.speed in targetSpeed * 0.85f..targetSpeed * 1.15f)
                        maximumSeamStep = maxOf(maximumSeamStep, stepMeters)
                        maximumSeamSpeedChange = maxOf(maximumSeamSpeedChange, abs(current.speed - previous.speed))
                    }
                    previous = current
                }
                assertEquals("five laps were not completed: pace=$pace interval=$interval", 5, crossings)
                assertTrue(previous.distanceMeters > 1_800f)
            }
        }
    }

    @Test
    fun localTotalsKeepIncreasingAcrossLoopBoundariesAndIgnoreDuplicateTicks() {
        val route = Route(listOf(
            RoutePoint(0.0, 0.0), RoutePoint(0.0, 0.0007),
            RoutePoint(0.0007, 0.0007), RoutePoint(0.0007, 0.0),
            RoutePoint(0.0, 0.0)
        ), 310f, 0L)
        val engine = TrajectoryInterpolator(route, 5f, true, Random(102))
        val profile = SimulationProfile(100.0, 60.0)
        var previous = engine.calculateNextPosition(100_000L)
        for (elapsed in 200L..240_000L step 200L) {
            val result = engine.calculateNextPosition(100_000L + elapsed)
            assertTrue(result.distanceMeters >= previous.distanceMeters)
            assertEquals(elapsed, result.elapsedMillis)
            assertEquals(result, engine.calculateNextPosition(100_000L + elapsed))
            assertEquals(result, engine.calculateNextPosition(99_900L + elapsed))
            val metrics = SimulationMetricsCalculator.calculate(result.distanceMeters.toDouble(), result.elapsedMillis, profile)
            assertTrue(metrics.estimatedSteps >= previous.distanceMeters.toLong())
            previous = result
        }
        assertTrue(previous.lapCount >= 2)
        assertTrue(previous.distanceMeters > 600f)
    }

    @Test
    fun resettingTheTrajectoryStartsANewStatisticsSession() {
        val route = Route(listOf(RoutePoint(0.0, 0.0), RoutePoint(0.0, 0.01)), 1100f, 0L)
        val engine = TrajectoryInterpolator(route, 5f, random = Random(8))
        engine.calculateNextPosition(10_000L)
        var last = engine.calculateNextPosition(10_000L)
        for (time in 10_200L..20_000L step 200L) last = engine.calculateNextPosition(time)
        assertTrue(last.distanceMeters > 0f)
        engine.reset()
        val restarted = engine.calculateNextPosition(30_000L)
        val metrics = SimulationMetricsCalculator.calculate(restarted.distanceMeters.toDouble(), restarted.elapsedMillis, SimulationProfile())
        assertEquals(0.0, metrics.distanceMeters, 0.0)
        assertEquals(0L, metrics.elapsedMillis)
        assertEquals(0L, metrics.estimatedSteps)
        assertNull(metrics.averagePaceSecondsPerKm)
    }

    private fun distanceMeters(first: PositionResult, second: PositionResult): Double {
        val latitudeDelta = Math.toRadians(second.latitude - first.latitude)
        val longitudeDelta = Math.toRadians(second.longitude - first.longitude)
        val a = sin(latitudeDelta / 2).pow(2) +
            cos(Math.toRadians(first.latitude)) * cos(Math.toRadians(second.latitude)) *
            sin(longitudeDelta / 2).pow(2)
        return 2 * 6_371_000.0 * atan2(sqrt(a), sqrt(1 - a))
    }
}
