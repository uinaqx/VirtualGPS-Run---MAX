package com.virtualrun.app.algorithm

import kotlin.math.floor
import kotlin.math.min
import kotlin.random.Random

/** A few localized lane changes; the rest of the selected route remains unchanged. */
class SparseRouteManeuvers(private val lengthMeters: Double, private val random: Random = Random.Default) {
    private data class Maneuver(val start: Double, val width: Double, val offset: Double)
    private val plans = mutableMapOf<Int, List<Maneuver>>()
    private var sessionSeed = random.nextLong()

    fun reset() { plans.clear(); sessionSeed = random.nextLong() }

    fun lateralOffset(totalDistanceMeters: Double): Double {
        if (lengthMeters < 8.0) return 0.0
        val lap = floor(totalDistanceMeters / lengthMeters).toInt().coerceAtLeast(0)
        val distance = totalDistanceMeters - lap * lengthMeters
        val plan = plans.getOrPut(lap) { makePlan(lap) }
        val maneuver = plan.firstOrNull { distance >= it.start && distance <= it.start + it.width } ?: return 0.0
        val progress = (distance - maneuver.start) / maneuver.width
        val edgeProgress = when {
            progress < 0.3 -> progress / 0.3
            progress > 0.7 -> (1.0 - progress) / 0.3
            else -> 1.0
        }.coerceIn(0.0, 1.0)
        // Quintic easing: position, slope and curvature meet the original path at both ends.
        val eased = edgeProgress * edgeProgress * edgeProgress * (10.0 + edgeProgress * (-15.0 + 6.0 * edgeProgress))
        return maneuver.offset * eased
    }

    private fun makePlan(lap: Int): List<Maneuver> {
        val random = Random(sessionSeed xor (lap.toLong() * 1_000_003L))
        val count = if (lengthMeters >= 200.0) random.nextInt(1, 4) else 1
        val slot = lengthMeters / count
        return List(count) { index ->
            val width = min(slot * 0.42, random.nextDouble(35.0, 65.0))
            val center = slot * (index + random.nextDouble(0.40, 0.60))
            val magnitude = min(lengthMeters * 0.01, random.nextDouble(0.9, 2.4))
            Maneuver(center - width / 2.0, width, if (random.nextBoolean()) magnitude else -magnitude)
        }
    }
}
