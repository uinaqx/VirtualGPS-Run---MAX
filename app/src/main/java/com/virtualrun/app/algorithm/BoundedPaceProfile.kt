package com.virtualrun.app.algorithm

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Smooth, bounded speed changes with analytically integrated distance and irregular segment lengths. */
class BoundedPaceProfile(
    fastestSecondsPerKm: Double,
    slowestSecondsPerKm: Double,
    private val random: Random = Random.Default
) {
    val minimumSpeed = (1000.0 / slowestSecondsPerKm).toFloat()
    val maximumSpeed = (1000.0 / fastestSecondsPerKm).toFloat()
    private data class Segment(val start: Double, val distance: Double, val duration: Double, val from: Double, val to: Double) {
        val end get() = start + duration
        fun speedAt(seconds: Double) = from + (to - from) * (1.0 - cos(PI * seconds / duration)) / 2.0
        fun distanceAt(seconds: Double) = from * seconds + (to - from) / 2.0 *
            (seconds - duration / PI * sin(PI * seconds / duration))
    }
    data class Sample(val speed: Float, val distanceMeters: Double)
    private val segments = mutableListOf<Segment>()

    init {
        require(fastestSecondsPerKm.isFinite() && slowestSecondsPerKm.isFinite())
        require(fastestSecondsPerKm > 0.0 && slowestSecondsPerKm >= fastestSecondsPerKm)
        require(minimumSpeed > 0f && maximumSpeed.isFinite())
        reset()
    }

    fun reset() {
        segments.clear()
        appendSegment(0.0, 0.0, chooseSpeed())
    }

    fun sample(elapsedSeconds: Double): Sample {
        require(elapsedSeconds.isFinite() && elapsedSeconds >= 0.0)
        while (segments.last().end < elapsedSeconds) {
            val last = segments.last()
            appendSegment(last.end, last.distance + last.distanceAt(last.duration), last.to)
        }
        val segment = segments.first { elapsedSeconds <= it.end }
        val elapsed = elapsedSeconds - segment.start
        return Sample(segment.speedAt(elapsed).toFloat(), segment.distance + segment.distanceAt(elapsed))
    }

    private fun appendSegment(start: Double, distance: Double, from: Double) {
        segments += Segment(start, distance, random.nextDouble(14.0, 38.0), from, chooseSpeed())
    }

    private fun chooseSpeed() = if (minimumSpeed == maximumSpeed) minimumSpeed.toDouble()
        else random.nextDouble(minimumSpeed.toDouble(), maximumSpeed.toDouble())
}
