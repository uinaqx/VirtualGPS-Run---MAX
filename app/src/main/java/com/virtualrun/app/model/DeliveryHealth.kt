package com.virtualrun.app.model

const val DELIVERY_FRESHNESS_MILLIS = 2_000L

/** Interface-call diagnostics only; these do not prove that a receiving app accepted a location. */
data class DeliveryChannelHealth(
    val channel: String,
    val count: Long = 0L,
    val lastSuccessElapsedMillis: Long? = null,
    val lastError: String? = null,
    val lastErrorElapsedMillis: Long? = null,
    val status: String = "等待样本",
    val pendingSinceElapsedMillis: Long? = null,
    val note: String? = null
) {
    fun isFresh(nowElapsedMillis: Long, startedAtElapsedMillis: Long? = null): Boolean {
        val successAt = lastSuccessElapsedMillis ?: return false
        return count > 0L && successAt <= nowElapsedMillis &&
            (startedAtElapsedMillis == null || successAt >= startedAtElapsedMillis) &&
            nowElapsedMillis - successAt <= DELIVERY_FRESHNESS_MILLIS
    }
}

/** Immutable, coordinate-free snapshot, safe to render from a separate watchdog thread. */
data class DeliveryHealthSnapshot(
    val nowElapsedMillis: Long,
    val startedAtElapsedMillis: Long?,
    val lastTickElapsedMillis: Long?,
    val tickCount: Long,
    val channels: List<DeliveryChannelHealth>
) {
    private val primaryFreshCount: Int
        get() = listOf("gps", "network").count { name ->
            channels.firstOrNull { it.channel == name }
                ?.isFresh(nowElapsedMillis, startedAtElapsedMillis) == true
        }

    private val preparing: Boolean
        get() = startedAtElapsedMillis?.let {
            nowElapsedMillis >= it && nowElapsedMillis - it <= DELIVERY_FRESHNESS_MILLIS
        } == true

    val summary: String
        get() = when {
            startedAtElapsedMillis == null -> "定位未启动"
            primaryFreshCount == 2 -> "定位投递正常"
            primaryFreshCount == 1 -> "定位投递部分异常"
            preparing -> "正在准备定位投递"
            else -> "定位投递已中断"
        }

    val warning: Boolean
        get() = startedAtElapsedMillis != null &&
            (primaryFreshCount == 1 || (primaryFreshCount == 0 && !preparing))

    val details: String
        get() = buildString {
            append("路线心跳：count=").append(tickCount)
            append("，距上次=").append(ageText(lastTickElapsedMillis))
            channels.forEach { channel ->
                append('\n').append(channel.channel).append("：count=").append(channel.count)
                append("，距成功=").append(ageText(channel.lastSuccessElapsedMillis))
                append("；").append(channel.status)
                channel.pendingSinceElapsedMillis?.let {
                    val age = (nowElapsedMillis - it).coerceAtLeast(0L)
                    append("；等待=").append(age).append("ms")
                    if (age > DELIVERY_FRESHNESS_MILLIS) append("（超过2秒仍未完成）")
                }
                channel.note?.let { append("；").append(it) }
                channel.lastError?.let {
                    append("；最近错误=").append(it)
                    if (channel.lastErrorElapsedMillis != null) {
                        append("（").append(ageText(channel.lastErrorElapsedMillis)).append("前）")
                    }
                }
            }
            append("\n以上为定位接口投递状态，不代表第三方应用或服务器认可。")
        }

    private fun ageText(at: Long?): String = when {
        at == null -> "无"
        at > nowElapsedMillis -> "时钟不匹配"
        else -> "${nowElapsedMillis - at}ms"
    }
}

/** All mutations publish one immutable value, so observers never wait for an Android IPC lock. */
class DeliveryChannelTracker(private val channel: String) {
    @Volatile private var current = DeliveryChannelHealth(channel)

    fun snapshot(): DeliveryChannelHealth = current

    @Synchronized fun reset(status: String = "等待样本") {
        current = DeliveryChannelHealth(channel, status = status)
    }

    @Synchronized fun setStatus(status: String) { current = current.copy(status = status) }

    @Synchronized fun setNote(note: String) { current = current.copy(note = note) }

    @Synchronized fun beginOperation(nowElapsedMillis: Long, operation: String) {
        current = current.copy(status = "$operation 等待中", pendingSinceElapsedMillis = nowElapsedMillis)
    }

    @Synchronized fun finishOperation(status: String) {
        current = current.copy(status = status, pendingSinceElapsedMillis = null)
    }

    @Synchronized fun recordSuccess(nowElapsedMillis: Long) {
        current = current.copy(
            count = current.count + 1L,
            lastSuccessElapsedMillis = nowElapsedMillis,
            status = "投递成功",
            pendingSinceElapsedMillis = null
        )
    }

    /** Callers pass operation names and exception types, never raw exception messages or locations. */
    @Synchronized fun recordError(
        nowElapsedMillis: Long,
        error: String,
        status: String,
        keepPending: Boolean = false
    ) {
        current = current.copy(
            lastError = error,
            lastErrorElapsedMillis = nowElapsedMillis,
            status = status,
            pendingSinceElapsedMillis = if (keepPending) current.pendingSinceElapsedMillis else null
        )
    }
}
