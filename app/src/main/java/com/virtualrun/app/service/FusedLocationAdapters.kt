package com.virtualrun.app.service

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.location.LocationServices
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.tasks.Task
import com.virtualrun.app.model.DeliveryChannelHealth
import com.virtualrun.app.model.DeliveryChannelTracker
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Latest-only mailbox: slow optional providers cannot accumulate a path backlog. */
internal class PlatformFusedAdapter(manager: LocationManager) {
    private val thread = HandlerThread("mock-platform-fused", Process.THREAD_PRIORITY_FOREGROUND).apply { start() }
    private val handler = Handler(thread.looper)
    private val adapter = PlatformLocationAdapter(manager, "fused", "platform-fused")
    private val latest = AtomicReference<Location?>()
    private val queued = AtomicBoolean()
    private val enabled = AtomicBoolean()
    fun start() { adapter.resetHealth(); enabled.set(true) }
    fun healthSnapshot(): DeliveryChannelHealth = adapter.healthSnapshot()
    fun offer(location: Location) {
        if (!enabled.get()) return
        latest.set(location)
        if (queued.compareAndSet(false, true)) handler.post {
            val sample = latest.getAndSet(null)
            if (enabled.get() && sample != null) adapter.push(sample)
            queued.set(false)
            // A concurrent offer will be picked up no later than the next pump tick.
        }
    }
    fun stop() {
        enabled.set(false)
        latest.set(null)
        handler.post { adapter.close() }
    }
    fun close() { stop(); handler.post { thread.quitSafely() } }
}

/**
 * All Play Services calls and callbacks run on a separate looper.
 * At most one operation is awaited and one unsent observation is retained.
 * Slow Tasks are diagnosed without queueing another non-cancellable operation.
 * Explicit failures retry with backoff; a pending Task retains only the latest fix.
 */
@SuppressLint("MissingPermission")
internal class GoogleFlpAdapter(private val context: Context) {
    private val client = LocationServices.getFusedLocationProviderClient(context)
    private val thread = HandlerThread("mock-google-flp", Process.THREAD_PRIORITY_FOREGROUND).apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { if (!handler.post(it)) it.run() }
    private val latest = AtomicReference<Location?>()
    private val enabled = AtomicBoolean()
    private val queued = AtomicBoolean()
    private var mockMode = false
    private var requested = false
    private var busy = false
    private var token = 0L
    private var retryAt = 0L
    private val backoff = DeliveryBackoff()
    private var lastNanos = 0L
    private var closing = false
    private val health = DeliveryChannelTracker("google-flp")

    fun healthSnapshot(): DeliveryChannelHealth = health.snapshot()

    fun start() {
        owner.set(this)
        enabled.set(true)
        health.reset("GMS 正在准备")
        health.setNote("GMS 状态尚未检查")
        handler.post { retryAt = 0; drain() }
    }
    fun offer(location: Location) {
        if (!enabled.get()) return
        latest.set(location)
        if (queued.compareAndSet(false, true)) handler.post {
            queued.set(false)
            drain()
        }
    }

    private val retry = Runnable { drain() }
    private fun drain() {
        if (!enabled.get() || busy) return
        val remaining = retryAt - SystemClock.elapsedRealtime()
        if (remaining > 0) {
            handler.removeCallbacks(retry)
            handler.postDelayed(retry, remaining)
            return
        }
        if (!mockMode) {
            val availability = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
            health.setNote("GMS=${availabilityDescription(availability)} ($availability)")
            if (availability != ConnectionResult.SUCCESS) {
                failed("availability", IllegalStateException("Google Play Services status=$availability"))
                return
            }
            requested = true
            operation("setMockMode(true)", { client.setMockMode(true) }) {
                mockMode = true
                health.finishOperation("GMS mock 模式已启用，等待样本")
                Log.i(TAG, "mockMode=true")
            }
        } else {
            val location = latest.getAndSet(null) ?: return
            operation("setMockLocation", { client.setMockLocation(Location(location).apply { provider = "fused" }) }) {
                health.recordSuccess(SystemClock.elapsedRealtime())
                val count = health.snapshot().count
                backoff.reset()
                lastNanos = DeliveryLog.success("google-flp", count, location, lastNanos)
            }
        }
    }

    private fun operation(name: String, call: () -> Task<Void>, success: () -> Unit) {
        busy = true
        health.beginOperation(SystemClock.elapsedRealtime(), name)
        val id = ++token
        val timeout = Runnable {
            if (id == token && busy) {
                // Google Task cannot be cancelled. Keep the single-flight fence rather
                // than enqueueing more stale locations inside Play Services.
                health.recordError(SystemClock.elapsedRealtime(), "$name: Timeout", "GMS 调用等待超时，保留单次调用", keepPending = true)
                Log.e(TAG, "$name pending over 2000ms; retaining only latest fix, GPS/Network unaffected")
            }
        }
        handler.postDelayed(timeout, 2000L)
        try {
            call().addOnCompleteListener(executor) { task ->
                if (id != token) {
                    Log.w(TAG, "late completion ignored operation=$name success=${task.isSuccessful}")
                    if (!enabled.get() && name == "setMockMode(true)" && task.isSuccessful) {
                        requested = true
                        disable()
                    }
                    return@addOnCompleteListener
                }
                handler.removeCallbacks(timeout)
                busy = false
                health.finishOperation(if (task.isSuccessful) "$name 已完成" else "$name 失败")
                if (!enabled.get()) {
                    if (name == "setMockMode(true)" && task.isSuccessful) requested = true
                    disable()
                    return@addOnCompleteListener
                }
                if (task.isSuccessful) { success(); drain() }
                else failed(name, task.exception ?: IllegalStateException("$name failed without exception"))
            }
        } catch (error: Exception) {
            handler.removeCallbacks(timeout)
            busy = false
            failed(name, error)
        }
    }

    private fun failed(name: String, error: Exception) {
        mockMode = false
        val delay = backoff.nextDelayMs()
        retryAt = SystemClock.elapsedRealtime() + delay
        health.recordError(SystemClock.elapsedRealtime(), "$name: ${error.javaClass.simpleName}", "GMS 等待重试 ${delay}ms")
        Log.e(TAG, "$name failed type=${error.javaClass.simpleName} mockMode=false retryMs=$delay; GPS/Network unaffected")
        handler.removeCallbacks(retry)
        if (enabled.get()) handler.postDelayed(retry, delay)
        else disable()
    }

    fun stop() {
        enabled.set(false)
        latest.set(null)
        health.finishOperation("已停止，等待 GMS 清理")
        handler.post {
            handler.removeCallbacks(retry)
            mockMode = false
            // Keep the in-flight completion alive so late enable cannot leave mock mode on.
            if (!busy) disable()
        }
    }

    private fun disable() {
        if (owner.get() !== this || enabled.get()) return
        if (!requested) { finishClose(); return }
        requested = false
        try {
            client.setMockMode(false).addOnCompleteListener(executor) {
                if (it.isSuccessful) {
                    health.finishOperation("已停止，GMS mock 模式已关闭")
                    Log.i(TAG, "mockMode=false")
                } else {
                    val type = it.exception?.javaClass?.simpleName ?: "UnknownFailure"
                    health.recordError(SystemClock.elapsedRealtime(), "disable: $type", "停止 GMS mock 模式失败")
                    Log.e(TAG, "disable mock mode failed type=$type")
                }
                finishClose()
            }
        } catch (error: Exception) {
            health.recordError(SystemClock.elapsedRealtime(), "disable: ${error.javaClass.simpleName}", "停止 GMS mock 模式失败")
            Log.e(TAG, "disable mock mode failed type=${error.javaClass.simpleName}")
            finishClose()
        }
    }

    fun close() {
        stop()
        handler.post {
            closing = true
            // Leave callbacks alive briefly for late mode-enable and its compensating disable.
            handler.postDelayed({ if (!enabled.get()) { disable(); thread.quitSafely() } }, 10_000L)
        }
    }
    private fun finishClose() {
        // The bounded grace period also covers an enable Task completing after a timeout.
        if (closing) Log.d(TAG, "closed delivery; waiting for late callbacks")
    }
    private fun availabilityDescription(code: Int): String = when (code) {
        ConnectionResult.SUCCESS -> "可用"
        ConnectionResult.SERVICE_MISSING -> "未安装"
        ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED -> "需要更新"
        ConnectionResult.SERVICE_DISABLED -> "已停用"
        ConnectionResult.SERVICE_INVALID -> "服务无效"
        ConnectionResult.SERVICE_UPDATING -> "正在更新"
        else -> "不可用"
    }
    private companion object {
        const val TAG = "MockGoogleFlp"
        val owner = AtomicReference<GoogleFlpAdapter?>()
    }
}
