package com.virtualrun.app.model

import com.virtualrun.app.algorithm.BoundedPaceProfile
import com.virtualrun.app.algorithm.TrajectoryInterpolator

enum class PaceMode { NATURAL, CONSTANT, BOUNDED }

/** Optional preview controls. Defaults retain the original dynamic motion model. */
data class SimulationMotionSettings(
    val paceMode: PaceMode = PaceMode.NATURAL,
    val fastestSecondsPerKm: Double = 300.0,
    val slowestSecondsPerKm: Double = 600.0,
    val localManeuvers: Boolean = false
) {
    init {
        require(fastestSecondsPerKm.isFinite() && fastestSecondsPerKm > 0.0) {
            "Fastest pace must be finite and positive."
        }
        require(slowestSecondsPerKm.isFinite() && slowestSecondsPerKm >= fastestSecondsPerKm) {
            "Slowest pace must be finite and at least the fastest pace."
        }
        require((1000.0 / fastestSecondsPerKm).toFloat().isFinite() &&
            (1000.0 / slowestSecondsPerKm).toFloat() > 0f) {
            "Pace bounds must be representable as positive finite speeds."
        }
    }

    fun createInterpolator(route: Route, basePace: Float, loop: Boolean): TrajectoryInterpolator =
        TrajectoryInterpolator(
            route = route,
            basePace = basePace,
            isLoopMode = loop,
            uniformPace = paceMode != PaceMode.NATURAL,
            paceProfile = if (paceMode == PaceMode.BOUNDED) {
                BoundedPaceProfile(fastestSecondsPerKm, slowestSecondsPerKm)
            } else null,
            localManeuvers = localManeuvers
        )
}
