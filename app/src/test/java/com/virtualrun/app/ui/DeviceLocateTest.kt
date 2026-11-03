package com.virtualrun.app.ui

import android.location.LocationManager
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceLocateTest {
    @Test
    fun historicalNetworkPathStaysWhenThatProviderIsEnabled() {
        assertEquals(LocatePlan.LastKnown("gps"), plan(cached = setOf("gps", "network", "passive")))
        assertEquals(LocatePlan.LastKnown("network"), plan(cached = setOf("network", "passive")))
        assertEquals(LocatePlan.SingleUpdate("network"), plan(cached = setOf("passive")))
        assertEquals(LocatePlan.SingleUpdate("network"), plan())
    }

    @Test
    fun missingNetworkUsesGpsThenPassiveWithoutThrowing() {
        val aosp = setOf("gps", "passive")
        assertEquals(LocatePlan.LastKnown("gps"), plan(known = aosp, cached = setOf("gps")))
        assertEquals(LocatePlan.LastKnown("passive"), plan(known = aosp, cached = setOf("passive")))
        assertEquals(LocatePlan.SingleUpdate("gps"), plan(known = aosp))
        assertEquals(
            LocatePlan.SingleUpdate("passive"),
            plan(known = setOf("passive"), enabled = setOf("passive"))
        )
        assertEquals(
            LocatePlan.SingleUpdate("gps"),
            plan(known = setOf("gps", "network", "passive"), enabled = setOf("gps", "passive"))
        )
    }

    @Test
    fun locationOffKeepsCachedGpsOrNetworkAndOtherwisePrompts() {
        assertEquals(
            LocatePlan.LastKnown("gps"),
            plan(locationEnabled = false, cached = setOf("gps"))
        )
        assertEquals(
            LocatePlan.LastKnown("network"),
            plan(locationEnabled = false, cached = setOf("network", "passive"))
        )
        assertEquals(
            LocatePlan.Unavailable(LocateFailure.LOCATION_OFF),
            plan(locationEnabled = false, cached = setOf("passive"))
        )
        assertEquals(
            LocatePlan.Unavailable(LocateFailure.LOCATION_OFF),
            plan(known = setOf("gps", "passive"), locationEnabled = false)
        )
    }

    @Test
    fun noSupportedProviderIsReportedSeparatelyFromLocationOff() {
        assertEquals(
            LocatePlan.Unavailable(LocateFailure.NO_PROVIDER),
            plan(known = emptySet(), enabled = emptySet())
        )
        assertEquals(
            LocatePlan.Unavailable(LocateFailure.NO_PROVIDER),
            plan(known = setOf("fused"), enabled = setOf("fused"))
        )
        assertEquals(
            LocatePlan.Unavailable(LocateFailure.LOCATION_OFF),
            plan(known = setOf("gps", "network"), enabled = emptySet())
        )
    }

    @Test
    fun timeoutMatchesTheTenSecondLocateBudget() {
        assertEquals(10_000L, DEVICE_LOCATE_TIMEOUT_MS)
        assertEquals(LocationManager.NETWORK_PROVIDER, "network")
    }

    private fun plan(
        known: Set<String> = setOf("gps", "network", "passive"),
        enabled: Set<String> = known,
        locationEnabled: Boolean = true,
        cached: Set<String> = emptySet(),
    ) = planDeviceLocate(known, enabled, locationEnabled) { it in cached }
}
