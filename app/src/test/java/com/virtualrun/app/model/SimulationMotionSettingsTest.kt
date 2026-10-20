package com.virtualrun.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SimulationMotionSettingsTest {
    private val route = Route(listOf(RoutePoint(0.0, 0.0), RoutePoint(0.0, 0.1)), 11_000f, 0L)

    @Test fun defaultModeRetainsDynamicAccelerationAndUsesSelectedPace() {
        val defaults = SimulationMotionSettings()
        assertEquals(PaceMode.NATURAL, defaults.paceMode)
        assertFalse(defaults.localManeuvers)
        val fast = defaults.createInterpolator(route, 4f, false)
        val slow = defaults.createInterpolator(route, 10f, false)
        assertEquals(0f, fast.calculateNextPosition(1_000L).speed, 0f)
        slow.calculateNextPosition(1_000L)
        for (time in 1_200L..31_000L step 200L) {
            fast.calculateNextPosition(time)
            slow.calculateNextPosition(time)
        }
        assertTrue(fast.calculateNextPosition(31_000L).distanceMeters >
            slow.calculateNextPosition(31_000L).distanceMeters * 1.5f)
    }

    @Test fun constantModeUsesSelectedPaceAndSupportsUpdatesWithoutResettingTotals() {
        val engine = SimulationMotionSettings(PaceMode.CONSTANT).createInterpolator(route, 5f, false)
        val first = engine.calculateNextPosition(1_000L)
        assertEquals(1_000f / 300f, first.speed, 0f)
        val beforeUpdate = engine.calculateNextPosition(11_000L)
        engine.updatePace(10f)
        val afterUpdate = engine.calculateNextPosition(21_000L)
        assertEquals(1_000f / 600f, afterUpdate.speed, 0f)
        assertEquals(50f, afterUpdate.distanceMeters, 0.001f)
        assertEquals(20_000L, afterUpdate.elapsedMillis)
        assertTrue(afterUpdate.distanceMeters > beforeUpdate.distanceMeters)
    }

    @Test fun boundedModeUsesConfiguredLimitsAndAllowsEqualBounds() {
        for (bounds in listOf(240.0 to 480.0, 360.0 to 360.0)) {
            val engine = SimulationMotionSettings(PaceMode.BOUNDED, bounds.first, bounds.second)
                .createInterpolator(route, 15f, false)
            var previous = engine.calculateNextPosition(1_000L)
            for (time in 1_200L..121_000L step 200L) {
                val current = engine.calculateNextPosition(time)
                val pace = 1_000.0 / current.speed
                assertTrue(pace >= bounds.first - 0.001 && pace <= bounds.second + 0.001)
                assertTrue(current.distanceMeters >= previous.distanceMeters)
                previous = current
            }
            if (bounds.first == bounds.second) {
                assertEquals(120_000.0 / bounds.first, previous.distanceMeters.toDouble(), 0.001)
            }
        }
    }

    @Test fun localManeuversAreOptionalAndIndependentOfPaceMode() {
        val loop = Route(listOf(RoutePoint(0.0, 0.0), RoutePoint(0.0, 0.001),
            RoutePoint(0.001, 0.001), RoutePoint(0.001, 0.0), RoutePoint(0.0, 0.0)), 445f, 0L)
        for (mode in PaceMode.values()) {
            val plain = SimulationMotionSettings(mode).createInterpolator(loop, 5f, true)
            val local = SimulationMotionSettings(mode, localManeuvers = true).createInterpolator(loop, 5f, true)
            var differentPosition = false
            for (time in 1_000L..301_000L step 1_000L) {
                val a = plain.calculateNextPosition(time)
                val b = local.calculateNextPosition(time)
                assertTrue(b.latitude.isFinite() && b.longitude.isFinite())
                assertFalse(b.isCompleted)
                differentPosition = differentPosition || abs(a.latitude - b.latitude) > 0.000001 ||
                    abs(a.longitude - b.longitude) > 0.000001
            }
            assertTrue(differentPosition)
        }
    }

    @Test fun invalidOrUnrepresentableBoundsAreRejected() {
        for (bounds in listOf(0.0 to 1.0, -1.0 to 1.0, 600.0 to 300.0,
            Double.NaN to 600.0, 300.0 to Double.POSITIVE_INFINITY,
            Double.MIN_VALUE to 1.0, 1.0 to Double.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) {
                SimulationMotionSettings(PaceMode.BOUNDED, bounds.first, bounds.second)
            }
        }
    }
}
