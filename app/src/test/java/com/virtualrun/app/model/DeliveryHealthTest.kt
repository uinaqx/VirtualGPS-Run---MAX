package com.virtualrun.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeliveryHealthTest {
    private fun snapshot(
        now: Long = 10_000L,
        start: Long? = 1_000L,
        channels: Array<DeliveryChannelHealth> = emptyArray()
    ) = DeliveryHealthSnapshot(now, start, (now - 100L).coerceAtLeast(0L), 45L, channels.toList())

    private fun delivered(channel: String, at: Long) = DeliveryChannelHealth(
        channel = channel, count = 25L, lastSuccessElapsedMillis = at, status = "投递成功"
    )

    @Test fun optionalGmsFailureDoesNotDowngradeFreshPrimaryProviders() {
        val state = snapshot(
            channels = arrayOf(
                delivered("gps", 9_900L), delivered("network", 9_800L),
                DeliveryChannelHealth("google-flp", lastError = "availability: IllegalStateException",
                    status = "等待重试", note = "GMS=未安装 (1)")
            )
        )
        assertEquals("定位投递正常", state.summary)
        assertFalse(state.warning)
        assertTrue(state.details.contains("GMS=未安装 (1)"))
    }

    @Test fun oneFreshPrimaryProviderReportsPartialFailure() {
        val state = snapshot(channels = arrayOf(delivered("gps", 9_500L), delivered("network", 7_999L)))
        assertEquals("定位投递部分异常", state.summary)
        assertTrue(state.warning)
    }

    @Test fun freshnessExpiresImmediatelyAfterTwoSeconds() {
        val channels = arrayOf(delivered("gps", 8_000L), delivered("network", 8_000L))
        assertFalse(snapshot(now = 10_000L, channels = channels).warning)
        assertEquals("定位投递已中断", snapshot(now = 10_001L, channels = channels).summary)
        assertTrue(snapshot(now = 10_001L, channels = channels).warning)
    }

    @Test fun startupGraceExpiresEvenWhenRouteHeartbeatKeepsAdvancing() {
        val channels = arrayOf(DeliveryChannelHealth("gps"), DeliveryChannelHealth("network"))
        assertEquals("正在准备定位投递", snapshot(now = 3_000L, channels = channels).summary)
        val stalled = snapshot(now = 3_001L, channels = channels)
        assertEquals("定位投递已中断", stalled.summary)
        assertTrue(stalled.warning)
    }

    @Test fun freshOptionalProviderCannotHideMissingPrimaryDelivery() {
        val state = snapshot(channels = arrayOf(delivered("platform-fused", 9_999L), delivered("google-flp", 9_999L)))
        assertEquals("定位投递已中断", state.summary)
        assertTrue(state.warning)
    }

    @Test fun previousSessionOrFutureTimestampIsNotFreshDelivery() {
        val channels = arrayOf(delivered("gps", 9_999L), delivered("network", 10_001L))
        val state = snapshot(start = 10_000L, now = 10_000L, channels = channels)
        assertEquals("正在准备定位投递", state.summary)
        assertFalse(state.warning)
    }

    @Test fun stoppedSessionDoesNotReportAnOutage() {
        val state = snapshot(start = null, channels = arrayOf(DeliveryChannelHealth("gps")))
        assertEquals("定位未启动", state.summary)
        assertFalse(state.warning)
    }

    @Test fun pendingOperationShowsStalledWithoutNeedingDeliveryThreadCallback() {
        val tracker = DeliveryChannelTracker("google-flp")
        tracker.setNote("GMS=可用 (0)")
        tracker.beginOperation(7_999L, "setMockLocation")
        val state = snapshot(channels = arrayOf(delivered("gps", 9_900L), delivered("network", 9_900L), tracker.snapshot()))
        assertTrue(state.details.contains("超过2秒仍未完成"))
        assertFalse(state.warning)
    }

    @Test fun recoveryKeepsLastErrorHistoryWithoutPoisoningFreshDelivery() {
        val tracker = DeliveryChannelTracker("gps")
        tracker.recordError(8_500L, "push: SecurityException", "等待重试")
        tracker.recordSuccess(9_500L)
        val state = snapshot(channels = arrayOf(tracker.snapshot(), delivered("network", 9_500L)))
        assertEquals("定位投递正常", state.summary)
        assertFalse(state.warning)
        assertEquals("push: SecurityException", tracker.snapshot().lastError)
        assertEquals(1L, tracker.snapshot().count)
    }
}
