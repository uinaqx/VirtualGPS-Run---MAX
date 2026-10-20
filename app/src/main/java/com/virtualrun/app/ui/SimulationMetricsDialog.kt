package com.virtualrun.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.virtualrun.app.model.SimulationMetrics
import com.virtualrun.app.model.SimulationProfile
import java.util.Locale
import kotlin.math.roundToLong

@Composable
fun SimulationMetricsDialog(
    metrics: SimulationMetrics,
    profile: SimulationProfile,
    isRunning: Boolean,
    onProfileChanged: (SimulationProfile) -> Unit,
    onDismiss: () -> Unit
) {
    var strideText by remember(profile.strideLengthCm) {
        mutableStateOf(editableNumber(profile.strideLengthCm))
    }
    var caloriesText by remember(profile.caloriesPerKm) {
        mutableStateOf(editableNumber(profile.caloriesPerKm))
    }
    val stride = validParameter(strideText, 30.0, 250.0)
    val calories = validParameter(caloriesText, 1.0, 300.0)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("本地模拟数据") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "以下均为本地模拟或估算值，不代表真实运动。仅在本应用显示，" +
                        "不会写入其他应用的步数、传感器和运动记录。",
                    style = MaterialTheme.typography.bodySmall
                )
                MetricRow("模拟距离", "${decimal(metrics.distanceMeters / 1000.0, 2)} 公里")
                MetricRow("平均配速（估算）", paceText(metrics.averagePaceSecondsPerKm))
                MetricRow("模拟时长", durationText(metrics.elapsedMillis))
                MetricRow("运动量（估算）", "${decimal(metrics.estimatedCalories, 1)} 千卡")
                MetricRow("步数（估算）", "${metrics.estimatedSteps.coerceAtLeast(0L)} 步")
                MetricRow("平均步频（估算）", "${decimal(metrics.averageCadence, 1)} 步/分钟")
                MetricRow("平均步幅（估算）", "${decimal(metrics.averageStrideCm, 1)} 厘米")
                Text(
                    "平均步频就是每分钟的平均步数：累计步数 ÷ 时长（分钟）。" +
                        "平均步幅为累计距离 ÷ 累计步数，单位是厘米。",
                    style = MaterialTheme.typography.bodySmall
                )
                Divider()
                Text("下次模拟参数", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (isRunning) "运行中无法修改参数，停止后可设置。"
                    else "步数按距离和步幅估算，热量按距离和系数估算；参数保存后用于下次模拟。",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = strideText,
                    onValueChange = { strideText = it },
                    label = { Text("估算步幅（厘米）") },
                    supportingText = {
                        Text(if (stride == null) "请输入 30～250 的有限数字" else "范围：30～250 厘米")
                    },
                    isError = stride == null,
                    enabled = !isRunning,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = caloriesText,
                    onValueChange = { caloriesText = it },
                    label = { Text("热量系数（千卡/公里）") },
                    supportingText = {
                        Text(if (calories == null) "请输入 1～300 的有限数字" else "范围：1～300 千卡/公里")
                    },
                    isError = calories == null,
                    enabled = !isRunning,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                Text("上述系数仅用于模拟计算，不用于健康或运动评估。", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            if (!isRunning) {
                TextButton(
                    enabled = stride != null && calories != null,
                    onClick = {
                        if (stride != null && calories != null) {
                            onProfileChanged(SimulationProfile(stride, calories))
                            onDismiss()
                        }
                    }
                ) { Text("保存参数") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@Composable
fun SimulationStepSummary(
    metrics: SimulationMetrics,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val steps = "${metrics.estimatedSteps} 步"
    val cadence = if (metrics.elapsedMillis > 0L) "${decimal(metrics.averageCadence, 1)} 步/分钟" else "— 步/分钟"
    val stride = if (metrics.estimatedSteps > 0L) "${decimal(metrics.averageStrideCm, 1)} 厘米" else "— 厘米"
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(
                role = Role.Button,
                onClickLabel = "查看模拟统计详情和参数",
                onClick = onClick
            )
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("本地估算", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
            Text("详情与参数 ›", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StepSummaryValue("累计步数", steps, Modifier.weight(1f))
            StepSummaryValue("平均步频", cadence, Modifier.weight(1.3f))
            StepSummaryValue("平均步幅", stride, Modifier.weight(1f))
        }
    }
}

@Composable
private fun StepSummaryValue(label: String, value: String, modifier: Modifier) {
    Column(modifier.semantics(mergeDescendants = true) { contentDescription = "$label，$value，本地估算" }) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

private fun validParameter(text: String, minimum: Double, maximum: Double): Double? =
    text.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it in minimum..maximum }

private fun editableNumber(value: Double): String = value.toString().removeSuffix(".0")

private fun decimal(value: Double, digits: Int): String =
    if (value.isFinite() && value >= 0.0) String.format(Locale.ROOT, "%.${digits}f", value) else "—"

private fun paceText(secondsPerKm: Double?): String {
    if (secondsPerKm == null || !secondsPerKm.isFinite() || secondsPerKm <= 0.0) return "—"
    val seconds = secondsPerKm.roundToLong()
    return String.format(Locale.ROOT, "%d:%02d /公里", seconds / 60, seconds % 60)
}

private fun durationText(elapsedMillis: Long): String {
    val seconds = elapsedMillis.coerceAtLeast(0L) / 1000
    return String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
}
