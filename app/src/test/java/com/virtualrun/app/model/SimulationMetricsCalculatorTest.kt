package com.virtualrun.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class SimulationMetricsCalculatorTest {
    @Test
    fun twoKilometresInTenMinutesUsesCorrectDisplayUnits() {
        val result = SimulationMetricsCalculator.calculate(
            distanceMeters = 2_000.0,
            elapsedMillis = 600_000L,
            profile = SimulationProfile(strideLengthCm = 100.0, caloriesPerKm = 75.0)
        )

        assertEquals(2_000L, result.estimatedSteps)
        assertEquals(200.0, result.averageCadence, 0.000001)
        assertEquals(100.0, result.averageStrideCm, 0.000001)
        assertEquals(150.0, result.estimatedCalories, 0.000001)
        assertEquals(300.0, result.averagePaceSecondsPerKm!!, 0.000001)
    }

    @Test
    fun displayedTotalsReconstructDistanceTimeAndStepsConsistently() {
        val profile = SimulationProfile(strideLengthCm = 125.0, caloriesPerKm = 80.0)
        for (distance in listOf(1.25, 2.5, 125.0, 1_250.0, 5_000.0)) {
            for (elapsed in listOf(1_000L, 120_000L, 3_600_000L)) {
                val result = SimulationMetricsCalculator.calculate(distance, elapsed, profile)
                val configuredStrideMeters = profile.strideLengthCm / 100.0

                assertTrue(result.estimatedSteps * configuredStrideMeters <= distance + 1e-8)
                assertTrue((result.estimatedSteps + 1L) * configuredStrideMeters > distance)
                assertEquals(distance,
                    result.estimatedSteps * result.averageStrideCm / 100.0, 1e-8)
                assertEquals(result.estimatedSteps.toDouble(),
                    result.averageCadence * (elapsed / 60_000.0), 1e-8)
                assertEquals(elapsed / 1_000.0,
                    result.averagePaceSecondsPerKm!! * (distance / 1_000.0), 1e-8)
                assertEquals(distance / 1_000.0,
                    result.estimatedCalories / profile.caloriesPerKm, 1e-8)
            }
        }
    }

    @Test
    fun doublingTimeChangesCadenceAndPaceWithoutCreatingMoreStepsOrCalories() {
        val profile = SimulationProfile(120.0, 65.0)
        val faster = SimulationMetricsCalculator.calculate(1_000.0, 300_000L, profile)
        val slower = SimulationMetricsCalculator.calculate(1_000.0, 600_000L, profile)

        assertEquals(faster.estimatedSteps, slower.estimatedSteps)
        assertEquals(faster.estimatedCalories, slower.estimatedCalories, 0.0)
        assertEquals(faster.averageStrideCm, slower.averageStrideCm, 0.0)
        assertEquals(faster.averageCadence / 2.0, slower.averageCadence, 1e-8)
        assertEquals(faster.averagePaceSecondsPerKm!! * 2.0,
            slower.averagePaceSecondsPerKm!!, 1e-8)
    }

    @Test
    fun cumulativeDistancePreservesPartialStepsBetweenSmallUpdates() {
        val profile = SimulationProfile(strideLengthCm = 100.0)
        val results = listOf(0.9, 1.8, 2.7).mapIndexed { index, totalDistance ->
            SimulationMetricsCalculator.calculate(totalDistance, (index + 1L) * 1_000L, profile)
        }

        assertEquals(listOf(0L, 1L, 2L), results.map { it.estimatedSteps })
        assertEquals(2.7, results.last().estimatedSteps * results.last().averageStrideCm / 100.0, 1e-8)
    }

    @Test
    fun initialAndStationarySamplesHaveNoUndefinedValues() {
        val profile = SimulationProfile()
        assertEquals(SimulationMetrics(), SimulationMetricsCalculator.calculate(0.0, 0L, profile))

        val stationary = SimulationMetricsCalculator.calculate(0.0, 60_000L, profile)
        assertEquals(0L, stationary.estimatedSteps)
        assertEquals(0.0, stationary.averageCadence, 0.0)
        assertEquals(0.0, stationary.averageStrideCm, 0.0)
        assertEquals(0.0, stationary.estimatedCalories, 0.0)
        assertNull(stationary.averagePaceSecondsPerKm)

        val noTime = SimulationMetricsCalculator.calculate(100.0, 0L, profile)
        assertEquals(100L, noTime.estimatedSteps)
        assertEquals(0.0, noTime.averageCadence, 0.0)
        assertNull(noTime.averagePaceSecondsPerKm)

        val subStep = SimulationMetricsCalculator.calculate(0.5, 1_000L, profile)
        assertEquals(0L, subStep.estimatedSteps)
        assertEquals(0.0, subStep.averageStrideCm, 0.0)
        assertTrue(subStep.estimatedCalories > 0.0)
        assertTrue(subStep.averagePaceSecondsPerKm!!.isFinite())
    }

    @Test
    fun invalidInputsAreRejectedInsteadOfProducingNegativeOrUndefinedStatistics() {
        val profile = SimulationProfile()
        for (distance in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) {
                SimulationMetricsCalculator.calculate(distance, 1_000L, profile)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            SimulationMetricsCalculator.calculate(1.0, -1L, profile)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SimulationMetricsCalculator.calculate(Double.MAX_VALUE, 1L, profile)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SimulationMetricsCalculator.calculate(Double.MIN_VALUE, Long.MAX_VALUE, profile)
        }

        val normalized = SimulationMetricsCalculator.calculate(-0.0, 0L, profile)
        assertEquals(0.0.toBits(), normalized.distanceMeters.toBits())
    }

    @Test
    fun simulationProfileRejectsNonfiniteAndOutOfRangeAssumptions() {
        for (stride in listOf(0.0, 29.9, 250.1, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) {
                SimulationProfile(strideLengthCm = stride)
            }
        }
        for (coefficient in listOf(0.0, 0.9, 300.1, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) {
                SimulationProfile(caloriesPerKm = coefficient)
            }
        }
        // Valid boundaries remain usable, including very long elapsed times.
        for (profile in listOf(SimulationProfile(30.0, 1.0), SimulationProfile(250.0, 300.0))) {
            val result = SimulationMetricsCalculator.calculate(10_000.0, Long.MAX_VALUE, profile)
            assertTrue(result.averageCadence.isFinite())
            assertTrue(result.averagePaceSecondsPerKm!!.isFinite())
        }
    }
}
