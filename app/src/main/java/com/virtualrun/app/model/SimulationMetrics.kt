package com.virtualrun.app.model

import kotlin.math.floor

/**
 * User assumptions for a local simulation preview, not measurements from a person or sensor.
 * [caloriesPerKm] is a user-configured kcal/km coefficient, not a physiological measurement.
 */
data class SimulationProfile(
    val strideLengthCm: Double = 100.0,
    val caloriesPerKm: Double = 60.0
) {
    init {
        require(strideLengthCm.isFinite() && strideLengthCm in 30.0..250.0) {
            "Simulation stride length must be between 30 and 250 cm."
        }
        require(caloriesPerKm.isFinite() && caloriesPerKm in 1.0..300.0) {
            "Simulation calorie coefficient must be between 1 and 300 kcal/km."
        }
    }
}

/**
 * Local simulation totals. All step, cadence, stride and calorie fields are estimates derived
 * from the profile and route distance; they are not hardware sensor readings.
 *
 * [averageCadence] is estimated steps/minute and [averageStrideCm] is estimated cm/step.
 * [averagePaceSecondsPerKm] is seconds/km, or null before both time and distance are available.
 */
data class SimulationMetrics(
    val distanceMeters: Double = 0.0,
    val elapsedMillis: Long = 0L,
    val estimatedSteps: Long = 0L,
    val averageCadence: Double = 0.0,
    val averageStrideCm: Double = 0.0,
    val estimatedCalories: Double = 0.0,
    val averagePaceSecondsPerKm: Double? = null
) {
    init {
        require(distanceMeters.isFinite() && distanceMeters >= 0.0)
        require(elapsedMillis >= 0L)
        require(estimatedSteps >= 0L)
        require(averageCadence.isFinite() && averageCadence >= 0.0)
        require(averageStrideCm.isFinite() && averageStrideCm >= 0.0)
        require(estimatedCalories.isFinite() && estimatedCalories >= 0.0)
        require(averagePaceSecondsPerKm == null ||
            averagePaceSecondsPerKm.isFinite() && averagePaceSecondsPerKm > 0.0)
    }
}

/** Pure calculations for local previews, with no Android or sensor dependencies. */
object SimulationMetricsCalculator {
    /**
     * Derives statistics from cumulative totals, so update frequency cannot lose partial steps.
     * Invalid inputs and values too large to represent are rejected with IllegalArgumentException.
     * Zero elapsed time leaves cadence at zero and pace unavailable, rather than dividing by zero.
     */
    fun calculate(
        distanceMeters: Double,
        elapsedMillis: Long,
        profile: SimulationProfile
    ): SimulationMetrics {
        require(distanceMeters.isFinite() && distanceMeters >= 0.0) {
            "Simulation distance must be finite and nonnegative."
        }
        require(elapsedMillis >= 0L) { "Simulation elapsed time must be nonnegative." }

        // Normalize negative zero so it cannot appear as a negative distance in a preview.
        val distance = if (distanceMeters == 0.0) 0.0 else distanceMeters
        val wholeSteps = floor(distance / (profile.strideLengthCm / 100.0))
        // Long.MAX_VALUE rounds upward as a Double; strict comparison also prevents that overflow.
        require(wholeSteps.isFinite() && wholeSteps < Long.MAX_VALUE.toDouble()) {
            "Simulation step count is too large to represent."
        }
        val steps = wholeSteps.toLong()
        val cadence = if (elapsedMillis > 0L) {
            steps.toDouble() * 60_000.0 / elapsedMillis.toDouble()
        } else {
            0.0
        }
        val strideCm = if (steps > 0L) distance / steps.toDouble() * 100.0 else 0.0
        val calories = distance / 1_000.0 * profile.caloriesPerKm
        // milliseconds/metre has the same numeric value as seconds/kilometre.
        val pace = if (distance > 0.0 && elapsedMillis > 0L) {
            (elapsedMillis.toDouble() / distance).also {
                require(it.isFinite()) { "Simulation average pace is too large to represent." }
            }
        } else {
            null
        }

        return SimulationMetrics(
            distanceMeters = distance,
            elapsedMillis = elapsedMillis,
            estimatedSteps = steps,
            averageCadence = cadence,
            averageStrideCm = strideCm,
            estimatedCalories = calories,
            averagePaceSecondsPerKm = pace
        )
    }
}
