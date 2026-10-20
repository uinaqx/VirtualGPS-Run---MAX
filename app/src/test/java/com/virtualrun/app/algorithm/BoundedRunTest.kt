package com.virtualrun.app.algorithm

import com.virtualrun.app.model.Route
import com.virtualrun.app.model.RoutePoint
import com.virtualrun.app.model.reverseRoute
import kotlin.math.*
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class BoundedRunTest {
    @Test fun paceStaysInBoundsVariesAndMatchesIntegratedDistance() {
        val profile = BoundedPaceProfile(300.0, 330.0, Random(76))
        var previous = profile.sample(0.0)
        var integrated = 0.0
        var slowest = 0.0
        var fastest = 1_000.0
        for (tick in 1..18_000) {
            val sample = profile.sample(tick / 10.0)
            val pace = 1_000.0 / sample.speed
            assertTrue(pace in 299.9999..330.0001)
            assertTrue(abs(sample.speed - previous.speed) < 0.004)
            integrated += (sample.speed + previous.speed) * 0.05
            assertTrue(sample.distanceMeters >= previous.distanceMeters)
            fastest = min(fastest, pace); slowest = max(slowest, pace)
            previous = sample
        }
        assertEquals(integrated, previous.distanceMeters, 0.02)
        assertTrue(slowest - fastest > 18.0)
    }

    @Test fun differentSessionsHaveDifferentAveragesAndDelayedReadsLoseNoDistance() {
        val averages = (1..12).map { seed ->
            val profile = BoundedPaceProfile(300.0, 330.0, Random(seed))
            val direct = profile.sample(600.0)
            val stepped = BoundedPaceProfile(300.0, 330.0, Random(seed))
            for (time in 0..600) stepped.sample(time.toDouble())
            assertEquals(direct, stepped.sample(600.0))
            600_000.0 / direct.distanceMeters
        }
        assertTrue(averages.max() - averages.min() > 1.0)
    }

    @Test fun localManeuversLeaveMostOfRouteUnchangedAndDoNotRepeatEveryLap() {
        val maneuvers = SparseRouteManeuvers(410.0, Random(17))
        var unchanged = 0
        var changed = 0
        var differentLaps = 0
        var previous = 0.0
        for (tick in 0..4100) {
            val offset = maneuvers.lateralOffset(tick / 10.0)
            if (offset == 0.0) unchanged++ else changed++
            if (abs(offset - maneuvers.lateralOffset(410.0 + tick / 10.0)) > 0.1) differentLaps++
            assertTrue(abs(offset) <= 2.4)
            assertTrue(abs(offset - previous) < 0.15)
            previous = offset
        }
        assertTrue(unchanged > 2400)
        assertTrue(changed > 200)
        assertTrue(differentLaps > 200)
        assertEquals(0.0, maneuvers.lateralOffset(410.0), 0.0)
    }

    @Test fun reversedLoopKeepsStartClosesAndChangesOrientation() {
        val points = listOf(0.0 to 0.0, 2.0 to 0.0, 2.0 to 3.0, 0.0 to 3.0, 0.0 to 0.0)
        val reversed = reverseRoute(points, true)
        fun area(p: List<Pair<Double,Double>>) = p.zipWithNext().sumOf { (a,b) -> a.first*b.second-b.first*a.second }
        assertEquals(points.first(), reversed.first())
        assertEquals(reversed.first(), reversed.last())
        assertEquals(-area(points), area(reversed), 0.0)
        assertEquals(points, reverseRoute(reversed, true))
        assertEquals(points.reversed(), reverseRoute(points, false))
    }

    @Test fun boundedRouteKeepsCoordinateSpeedAndCrossesMultipleLaps() {
        val route = Route(listOf(RoutePoint(0.0,0.0), RoutePoint(0.0,0.0012),
            RoutePoint(0.0008,0.0012), RoutePoint(0.0008,0.0), RoutePoint(0.0,0.0)), 440f, 0L)
        for (interval in listOf(100L,200L,1000L)) {
            val engine = TrajectoryInterpolator(route, 315f/60f, true, Random(54), uniformPace=true,
                paceProfile=BoundedPaceProfile(300.0,330.0,Random(21)))
            var previous = engine.calculateNextPosition(10_000L)
            for (elapsed in interval..900_000L step interval) {
                val current = engine.calculateNextPosition(10_000L+elapsed)
                val chordSpeed = distance(previous,current)*1000.0/interval
                assertTrue("coordinate pace ${1000.0/chordSpeed}, interval=$interval elapsed=$elapsed",
                    chordSpeed in (1000.0/330.0-0.001)..(1000.0/300.0+0.001))
                assertTrue(1000.0/current.speed in 299.9999..330.0001)
                assertFalse(current.isCompleted)
                assertTrue(current.distanceMeters >= previous.distanceMeters)
                previous=current
            }
            assertTrue(previous.lapCount >= 4)
            val delayed=engine.calculateNextPosition(1_030_000L)
            assertEquals(1_020_000L,delayed.elapsedMillis)
            assertTrue(delayed.distanceMeters>previous.distanceMeters+250f)
            assertFalse(delayed.isCompleted)
        }
    }

    private fun distance(a: PositionResult,b: PositionResult): Double {
        val lat=Math.toRadians(b.latitude-a.latitude); val lng=Math.toRadians(b.longitude-a.longitude)
        val h=sin(lat/2).pow(2)+cos(Math.toRadians(a.latitude))*cos(Math.toRadians(b.latitude))*sin(lng/2).pow(2)
        return 6_371_000.0*2*atan2(sqrt(h),sqrt(1-h))
    }
}
