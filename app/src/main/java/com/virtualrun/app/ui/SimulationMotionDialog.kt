package com.virtualrun.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.virtualrun.app.model.PaceMode
import com.virtualrun.app.model.SimulationMotionSettings

@Composable
fun SimulationMotionDialog(
    settings: SimulationMotionSettings,
    onSettingsChanged: (SimulationMotionSettings) -> Unit,
    onDismiss: () -> Unit
) {
    var mode by remember(settings) { mutableStateOf(settings.paceMode) }
    var fastestText by remember(settings) { mutableStateOf(settings.fastestSecondsPerKm.toString().removeSuffix(".0")) }
    var slowestText by remember(settings) { mutableStateOf(settings.slowestSecondsPerKm.toString().removeSuffix(".0")) }
    var localManeuvers by remember(settings) { mutableStateOf(settings.localManeuvers) }
    val fastest = fastestText.trim().toDoubleOrNull()
    val slowest = slowestText.trim().toDoubleOrNull()
    val candidate = if (mode != PaceMode.BOUNDED) {
        settings.copy(paceMode = mode, localManeuvers = localManeuvers)
    } else if (fastest != null && slowest != null) {
        runCatching { SimulationMotionSettings(mode, fastest, slowest, localManeuvers) }.getOrNull()
    } else null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("运动模型") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(Modifier.selectableGroup()) {
                    MotionModeRow("动态配速（默认）", mode == PaceMode.NATURAL) { mode = PaceMode.NATURAL }
                    MotionModeRow("固定配速", mode == PaceMode.CONSTANT) { mode = PaceMode.CONSTANT }
                    MotionModeRow("范围配速", mode == PaceMode.BOUNDED) { mode = PaceMode.BOUNDED }
                }
                Text(
                    when (mode) {
                        PaceMode.NATURAL -> "沿用动态模型，依据主界面的基础配速处理加减速和弯道。"
                        PaceMode.CONSTANT -> "使用主界面的基础配速。延迟更新时按完整经过时间累计距离。"
                        PaceMode.BOUNDED -> "在以下配速范围内平滑变化；范围相等时保持固定配速。"
                    },
                    style = MaterialTheme.typography.bodySmall
                )
                if (mode == PaceMode.BOUNDED) {
                    OutlinedTextField(
                        value = fastestText,
                        onValueChange = { fastestText = it },
                        label = { Text("最快配速（秒/公里）") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        isError = candidate == null,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = slowestText,
                        onValueChange = { slowestText = it },
                        label = { Text("最慢配速（秒/公里）") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        isError = candidate == null,
                        supportingText = {
                            Text(if (candidate == null) "请输入有限正数，最快配速应小于或等于最慢配速。"
                                else "数值越小，速度越快。")
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Row(
                    Modifier.fillMaxWidth().toggleable(
                        value = localManeuvers,
                        role = Role.Checkbox,
                        onValueChange = { localManeuvers = it }
                    ).padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = localManeuvers, onCheckedChange = null)
                    Text("启用局部路线变化", modifier = Modifier.padding(start = 8.dp))
                }
                Text(
                    "默认关闭。开启后仅在少量路段平滑横移并回到原路线，可配合任一配速模式使用。设置仅用于本次应用会话。",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            TextButton(enabled = candidate != null, onClick = {
                candidate?.let {
                    onSettingsChanged(it)
                    onDismiss()
                }
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun MotionModeRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}
