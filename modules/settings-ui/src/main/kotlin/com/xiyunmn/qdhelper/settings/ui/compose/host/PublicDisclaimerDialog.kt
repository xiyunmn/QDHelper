package com.xiyunmn.qdhelper.settings.ui.compose.host

import com.xiyunmn.qdhelper.shared.ui.installModuleWatermark
import android.os.SystemClock
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.xiyunmn.qdhelper.config.ConfigRuntime
import com.xiyunmn.qdhelper.config.SettingIds
import kotlinx.coroutines.delay

@Composable
internal fun PublicDisclaimerDialog(onExit: () -> Unit, onAccepted: () -> Unit) {
    var remaining by remember { mutableIntStateOf(10) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onExit,
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text("使用前请阅读") },
        text = {
            val view = LocalView.current
            val lifecycle = LocalLifecycleOwner.current.lifecycle
            DisposableEffect(view) {
                val watermark = view.installModuleWatermark()
                onDispose { watermark.close() }
            }
            LaunchedEffect(view, lifecycle) {
                val timer = DisclaimerReadTimer()
                while (remaining > 0) {
                    remaining = timer.update(SystemClock.uptimeMillis(),
                        view.hasWindowFocus() && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                    delay(100)
                }
            }
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DisclaimerParagraph("本模块免费。若您通过付费购买、付费入群等方式获得本模块，请联系卖家或交易平台申请退款，谨防倒卖。",
                    "本模块免费", "联系卖家或交易平台申请退款", "谨防倒卖")
                DisclaimerParagraph("QDHelper 是独立开发的非官方工具，与阅文集团、起点中文网、起点读书及其运营主体无隶属、合作或授权关系。相关名称、商标与内容归各自权利人所有。",
                    "非官方工具", "无隶属、合作或授权关系")
                DisclaimerParagraph("仅供学习、研究与交流。请在下载后 24 小时内删除用于研究测试的安装包及相关副本；此提醒不改变开源许可证授予的权利。",
                    "仅供学习、研究与交流", "24 小时内删除", "不改变开源许可证授予的权利")
                DisclaimerParagraph("软件按现状提供，不保证功能持续可用。使用可能导致应用异常、数据损坏或账号受限，请遵守法律法规和相关服务条款，提前备份，自行评估并承担使用风险。",
                    "应用异常、数据损坏或账号受限", "提前备份", "自行评估并承担使用风险")
                error?.let { Text(it) }
            }
        },
        confirmButton = {
            TextButton(enabled = remaining == 0, onClick = {
                if (remaining == 0 && ConfigRuntime.putBoolean(SettingIds.KEY_DISCLAIMER_ACCEPTED, true)) onAccepted()
                else error = "确认状态保存失败，请重试。"
            }) { Text(if (remaining > 0) "请阅读 ${remaining}s" else "我已阅读并知悉") }
        },
        dismissButton = { TextButton(onClick = onExit) { Text("退出设置") } },
    )
}

@Composable
private fun DisclaimerParagraph(text: String, vararg emphasis: String) {
    val color = MaterialTheme.colorScheme.onSurface
    Text(buildAnnotatedString {
        append(text)
        emphasis.forEach { phrase ->
            val start = text.indexOf(phrase)
            if (start >= 0) addStyle(SpanStyle(fontWeight = FontWeight.Bold, color = color), start, start + phrase.length)
        }
    })
}
