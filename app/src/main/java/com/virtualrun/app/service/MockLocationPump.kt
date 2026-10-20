package com.virtualrun.app.service

import android.annotation.SuppressLint
import android.content.Context
import android.location.provider.ProviderProperties
import android.location.Location
import android.location.LocationManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.virtualrun.app.algorithm.PositionResult
import com.virtualrun.app.algorithm.TrajectoryInterpolator
import com.virtualrun.app.model.Route
import com.virtualrun.app.model.SimulationMotionSettings
import com.virtualrun.app.model.DeliveryChannelHealth
import com.virtualrun.app.model.DeliveryChannelTracker
import com.virtualrun.app.model.DeliveryHealthSnapshot
import java.util.concurrent.atomic.AtomicLong

/**
 * Single owner of trajectory state and the GPS/Network delivery clock.
 * UI work is a one-way callback; it never drives this clock.
 */
internal class MockLocationPump(context: Context, private val intervalMs: Long = 200L) {
    init { require(intervalMs in 100L..1000L) }
    private val thread = HandlerThread("mock-location-pump", Process.THREAD_PRIORITY_FOREGROUND).apply { start() }
    private val handler = Handler(thread.looper)
    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val gps = PlatformLocationAdapter(manager, LocationManager.GPS_PROVIDER)
    private val network = PlatformLocationAdapter(manager, LocationManager.NETWORK_PROVIDER)
    private val platformFused = PlatformFusedAdapter(manager)
    private val flp = GoogleFlpAdapter(context)
    private val generation = AtomicLong()
    private var trajectory: TrajectoryInterpolator? = null
    private var listener: ((PositionResult) -> Unit)? = null
    private var samples = LocationSampleFactory()
    private var nextDeadline = 0L
    private var lastTickNanos = 0L
    @Volatile private var count = 0L
    @Volatile private var startedAtElapsedMillis: Long? = null
    @Volatile private var lastTickElapsedMillis: Long? = null
    private var activeGeneration = 0L
    private var lastLoggedLap: Int? = null

    fun start(route: Route, pace: Float, loop: Boolean, settings: SimulationMotionSettings = SimulationMotionSettings(), onPosition: (PositionResult) -> Unit) {
        val id = generation.incrementAndGet()
        startedAtElapsedMillis = SystemClock.elapsedRealtime()
        lastTickElapsedMillis = null
        count = 0L
        handler.post {
            handler.removeCallbacks(tick)
            if (id != generation.get()) return@post
            activeGeneration = id
            trajectory = settings.createInterpolator(route, pace, loop)
            listener = onPosition
            samples = LocationSampleFactory()
            count = 0
            lastTickNanos = 0
            lastLoggedLap = null
            gps.resetHealth()
            network.resetHealth()
            platformFused.start()
            flp.start()
            nextDeadline = SystemClock.uptimeMillis()
            Log.i(TAG, "start session=$id intervalMs=$intervalMs priority=FOREGROUND")
            tick.run()
        }
    }

    fun updatePace(pace: Float) {
        val id = generation.get()
        handler.post { if (id == activeGeneration) trajectory?.updatePace(pace) }
    }

    /** Reads only published values; it never posts onto the potentially blocked delivery looper. */
    fun healthSnapshot(nowElapsedMillis: Long): DeliveryHealthSnapshot = DeliveryHealthSnapshot(
        nowElapsedMillis = nowElapsedMillis,
        startedAtElapsedMillis = startedAtElapsedMillis,
        lastTickElapsedMillis = lastTickElapsedMillis,
        tickCount = count,
        channels = listOf(gps.healthSnapshot(), network.healthSnapshot(), platformFused.healthSnapshot(), flp.healthSnapshot())
    )

    private val tick = object : Runnable {
        override fun run() {
            if (activeGeneration != generation.get()) return
            val interpolator = trajectory ?: return
            try {
                val nanos = SystemClock.elapsedRealtimeNanos()
                lastTickElapsedMillis = nanos / 1_000_000L
                count++
                val result = interpolator.calculateNextPosition(nanos / 1_000_000L)
                val location = samples.create(result, System.currentTimeMillis(), nanos)
                val gap = if (lastTickNanos == 0L) 0.0 else (nanos - lastTickNanos) / 1e6
                lastTickNanos = nanos
                if (lastLoggedLap != result.lapCount || result.isCompleted) {
                    lastLoggedLap = result.lapCount
                    Log.i(TAG, "route lap=${result.lapCount} progress=${result.progress} completed=${result.isCompleted} timeMs=${result.elapsedMillis} distanceMeters=${result.distanceMeters}")
                }
                if (activeGeneration != generation.get()) return
                gps.push(location)
                if (activeGeneration != generation.get()) return
                network.push(location)
                platformFused.offer(location)
                flp.offer(location)
                if (count % 25L == 0L || gap > intervalMs * 2) {
                    Log.i(TAG, "tick=$count intervalMs=$gap gps=${gps.pushCount} network=${network.pushCount}")
                }
                listener?.invoke(result)
                if (result.isCompleted) return
            } catch (error: Exception) {
                // An output failure must not terminate the run or its clock.
                Log.e(TAG, "tick failure type=${error.javaClass.simpleName}; continuing")
            }
            nextDeadline += intervalMs
            val now = SystemClock.uptimeMillis()
            if (nextDeadline <= now) nextDeadline += ((now - nextDeadline) / intervalMs + 1) * intervalMs
            if (activeGeneration == generation.get()) handler.postAtTime(this, nextDeadline)
        }
    }

    fun stop() {
        generation.incrementAndGet() // Prevent the next send immediately, without waiting for the UI/looper.
        startedAtElapsedMillis = null
        platformFused.stop()
        flp.stop()
        handler.post {
            handler.removeCallbacks(tick)
            trajectory = null
            listener = null
            gps.close()
            network.close()
            Log.i(TAG, "stopped gps=${gps.pushCount} network=${network.pushCount}")
        }
    }

    fun close() {
        stop()
        handler.post { thread.quitSafely() }
        platformFused.close()
        flp.close()
    }

    private companion object { const val TAG = "MockLocationPump" }
}

/** Primary adapters are independent: recovery failure in one never skips the other. */
@SuppressLint("MissingPermission", "InlinedApi")
internal class PlatformLocationAdapter(
    private val manager: LocationManager,
    private val provider: String,
    healthChannel: String = provider
) {
    private val lease = ProviderLease.slots.computeIfAbsent(provider) { ProviderLease() }
    private var initialized = false
    private var owned = false
    private var retryAt = 0L
    private val backoff = DeliveryBackoff()
    private val health = DeliveryChannelTracker(healthChannel)
    val pushCount: Long get() = health.snapshot().count
    private var lastNanos = 0L

    fun resetHealth() { health.reset() }
    fun healthSnapshot(): DeliveryChannelHealth = health.snapshot()

    fun push(sample: Location): Unit = synchronized(lease) {
        val now = SystemClock.elapsedRealtime()
        if (now < retryAt) return
        health.beginOperation(now, "定位接口")
        try {
            if (!initialized || !manager.isProviderEnabled(provider)) recover()
            val location = Location(sample).apply {
                this.provider = this@PlatformLocationAdapter.provider
                accuracy = when (provider) {
                    LocationManager.NETWORK_PROVIDER -> (sample.accuracy + 2.2f).coerceAtMost(8f)
                    "fused" -> (sample.accuracy + 0.35f).coerceAtMost(6f)
                    else -> sample.accuracy
                }
            }
            manager.setTestProviderLocation(provider, location)
            health.recordSuccess(SystemClock.elapsedRealtime())
            lastNanos = DeliveryLog.success(provider, pushCount, location, lastNanos)
            backoff.reset()
        } catch (error: Exception) {
            initialized = false
            retryAt = now + backoff.nextDelayMs()
            health.recordError(SystemClock.elapsedRealtime(), "push: ${error.javaClass.simpleName}", "等待重试，retryAt=$retryAt")
            Log.e("MockLocationPump", "provider=$provider push failed type=${error.javaClass.simpleName}; recoverAt=$retryAt")
        }
    }

    @Suppress("DEPRECATION")
    private fun recover() {
        lease.owner = this
        Log.i("MockLocationPump", "provider=$provider initialize/recover")
        try { manager.removeTestProvider(provider) } catch (error: IllegalArgumentException) {
            Log.d("MockLocationPump", "provider=$provider no previous test provider")
        }
        manager.addTestProvider(
            provider, provider == LocationManager.NETWORK_PROVIDER,
            provider == LocationManager.GPS_PROVIDER, provider == LocationManager.NETWORK_PROVIDER,
            false, true, true, true,
            if (provider == LocationManager.GPS_PROVIDER) ProviderProperties.POWER_USAGE_HIGH else ProviderProperties.POWER_USAGE_LOW,
            ProviderProperties.ACCURACY_FINE
        )
        owned = true
        manager.setTestProviderEnabled(provider, true)
        initialized = true
        Log.i("MockLocationPump", "provider=$provider ready")
    }

    fun close(): Unit = synchronized(lease) {
        if (lease.owner !== this) return@synchronized
        if (owned) try {
            manager.removeTestProvider(provider)
        } catch (error: Exception) {
            health.recordError(SystemClock.elapsedRealtime(), "cleanup: ${error.javaClass.simpleName}", "停止清理失败")
            Log.e("MockLocationPump", "provider=$provider cleanup failed type=${error.javaClass.simpleName}")
        }
        health.finishOperation("已停止")
        owned = false
        initialized = false
        retryAt = 0
        backoff.reset()
        lease.owner = null
    }
}

/** A destroyed service cannot remove providers already claimed by its replacement. */
internal class ProviderLease {
    var owner: PlatformLocationAdapter? = null
    companion object {
        val slots = java.util.concurrent.ConcurrentHashMap<String, ProviderLease>()
    }
}

internal object DeliveryLog {
    fun success(channel: String, count: Long, location: Location, previousNanos: Long): Long {
        val sendNanos = SystemClock.elapsedRealtimeNanos()
        val gap = if (previousNanos == 0L) 0.0 else (sendNanos - previousNanos) / 1e6
        if (count == 1L || count % 25L == 0L || gap > 1_000.0) {
            Log.d("MockLocationPush", "channel=$channel count=$count intervalMs=$gap speed=${location.speed} time=${location.time} elapsedRealtimeNanos=${location.elapsedRealtimeNanos} sendElapsedNanos=$sendNanos")
        }
        return sendNanos
    }
}
