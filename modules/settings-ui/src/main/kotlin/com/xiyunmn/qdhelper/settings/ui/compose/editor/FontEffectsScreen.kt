package com.xiyunmn.qdhelper.settings.ui.compose.editor

import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.widget.TextView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.xiyunmn.qdhelper.config.FontEffectScope
import com.xiyunmn.qdhelper.config.FontEffectSettings
import com.xiyunmn.qdhelper.settings.api.SettingsFontEffectSource
import com.xiyunmn.qdhelper.settings.api.SettingsFontEffectsController
import com.xiyunmn.qdhelper.settings.ui.compose.component.*
import com.xiyunmn.qdhelper.settings.ui.compose.navigation.LocalSettingsEntry
import com.xiyunmn.qdhelper.settings.ui.compose.state.SettingsController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

@Composable
internal fun FontEffectsScreen(fonts: SettingsFontEffectsController, controller: SettingsController) {
    val entry = LocalSettingsEntry.current
    val coroutine = rememberCoroutineScope()
    val context = controller.activity.applicationContext
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val colors = MaterialTheme.colorScheme
    val tabs = remember { listOf(FontEffectScope.Reader.name to "阅读正文",
        FontEffectScope.Regular.name to "界面常规", FontEffectScope.Emphasis.name to "界面强调") }
    val selected = FontEffectScope.entries.firstOrNull { it.name == entry.selectedTab } ?: FontEffectScope.Reader
    val sources = remember(fonts) { mutableStateMapOf<FontEffectScope, SettingsFontEffectSource>() }
    val drafts = entry.fontEffectDrafts
    val inputErrors = remember { mutableStateMapOf<String, Boolean>() }
    var revision by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    var resetVersion by remember { mutableIntStateOf(0) }
    DisposableEffect(fonts) {
        val listener = fonts.addChangedListener { coroutine.launch { revision++ } }
        onDispose { listener.close() }
    }
    LaunchedEffect(fonts, revision, controller.revision.intValue) {
        loading = true
        try {
            val loaded = withContext(Dispatchers.IO) {
                FontEffectScope.entries.associateWith { fonts.load(context, it) }
            }
            loaded.forEach { (scope, source) ->
                if (drafts[scope] == null || drafts[scope] == sources[scope]?.values) drafts[scope] = source.values
            }
            sources.putAll(loaded)
            failed = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        } finally { loading = false }
    }
    val dirty = sources.any { (scope, source) -> drafts[scope] != source.values }
    val latestBack by rememberUpdatedState<() -> Boolean>({
        when { saving -> true; dirty -> { discard = true; true }; else -> false }
    })
    DisposableEffect(entry) {
        val handler = { latestBack() }
        entry.consumeBack = handler
        onDispose { if (entry.consumeBack === handler) entry.consumeBack = null }
    }
    val source = sources[selected]
    val values = drafts[selected] ?: FontEffectSettings()
    val enabled = !loading && !saving && !failed && source?.available == true
    val changed = source != null && values != source.values
    fun dismissKeyboard() { focus.clearFocus(); keyboard?.hide() }
    fun apply() {
        dismissKeyboard()
        val target = selected
        val pending = values
        saving = true
        coroutine.launch {
            try {
                val saved = withContext(Dispatchers.IO) { fonts.save(context, target, pending) }
                if (saved) {
                    sources[target]?.let { sources[target] = it.copy(values = pending) }
                    controller.refreshValues()
                    controller.notify("已应用当前分类，重启起点后生效")
                } else controller.notify("未能保存，请检查所选字体后重试")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                controller.notify("保存失败，请重试")
            } finally { saving = false }
        }
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(Modifier.weight(1f), state = entry.scroll,
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item("scope") {
                ConsoleTabs(tabs, selected.name, {
                    if (!saving) { dismissKeyboard(); entry.selectedTab = it }
                })
            }
            item("preview") {
                FontEffectPreview(source, values, loading && source == null)
            }
            item("status") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("仅用于阅读正文与原生界面", fontSize = 12.sp, color = colors.onSurfaceVariant)
                    if (failed) Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("读取失败", color = colors.error, modifier = Modifier.weight(1f))
                        TextButton({ revision++ }) { Text("重试") }
                    } else source?.message?.let { Text(it, fontSize = 12.sp, color = colors.onSurfaceVariant) }
                }
            }
            item("parameters") {
                ConsoleSurface {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                        key(selected, resetVersion) {
                            val baseWeight = source?.typeface?.weight ?: 400
                            FontEffectParameter("字重", values.weight.takeIf { it != 0 } ?: baseWeight,
                                100..900, steps = 15, enabled = enabled,
                                hint = if (values.weight == 0) "跟随字体" else null,
                                invalid = { inputErrors["weight"] = it }) { drafts[selected] = values.copy(weight = it) }
                            HorizontalDivider(color = colors.outlineVariant.copy(alpha = .35f))
                            FontEffectParameter("字号", values.sizePercent, 75..150, enabled = enabled,
                                suffix = "%", hint = "100% 保持原大小", invalid = { inputErrors["size"] = it }) {
                                drafts[selected] = values.copy(sizePercent = it)
                            }
                            HorizontalDivider(color = colors.outlineVariant.copy(alpha = .35f))
                            FontEffectParameter("字宽", values.widthPercent, 75..125, enabled = enabled,
                                suffix = "%", invalid = { inputErrors["width"] = it }) { drafts[selected] = values.copy(widthPercent = it) }
                            HorizontalDivider(color = colors.outlineVariant.copy(alpha = .35f))
                            FontEffectParameter("字间距", values.spacing, -5..30, enabled = enabled,
                                decimal = true, hint = "0 保持原间距", invalid = { inputErrors["spacing"] = it }) {
                                drafts[selected] = values.copy(spacing = it)
                            }
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp, top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ConsoleActionButton("恢复默认", {
                dismissKeyboard()
                drafts[selected] = FontEffectSettings()
                resetVersion++
                inputErrors.clear()
            }, Modifier.weight(1f), enabled = !loading && !saving && source != null)
            Button(onClick = ::apply, modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                shape = RoundedCornerShape(12.dp),
                enabled = changed && !loading && !failed && !saving && inputErrors.values.none { it } &&
                    (source?.available == true || values == FontEffectSettings())) {
                if (saving) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Text("应用")
            }
        }
    }
    if (discard) ConsoleConfirmationDialog("放弃未应用的修改？", "已应用的设置会保留。", "放弃修改",
        onConfirm = {
            discard = false
            drafts.clear()
            entry.consumeBack = null
            controller.navigator.back()
        }, onDismiss = { discard = false })
}

@Composable
private fun FontEffectPreview(source: SettingsFontEffectSource?, values: FontEffectSettings, loading: Boolean) {
    val colors = MaterialTheme.colorScheme
    val base = source?.typeface ?: Typeface.DEFAULT
    val face = remember(base, values.weight) {
        if (values.weight == 0) base else Typeface.create(base, values.weight, base.isItalic)
    }
    val sample = remember {
        val text = "山河远阔，人间烟火\n一页好书，慢慢读完。"
        SpannableString(text).apply {
            setSpan(RelativeSizeSpan(.74f), text.indexOf('\n') + 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
    ConsoleSurface {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("实时预览", fontSize = 13.sp, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
                if (loading) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp)
            }
            Text(source?.name ?: "读取字体…", fontSize = 12.sp, color = colors.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            AndroidView(modifier = Modifier.fillMaxWidth().heightIn(min = 76.dp),
                factory = { context -> TextView(context).apply {
                    includeFontPadding = false
                    setPadding(0, 0, 0, 0)
                    setLineSpacing(5 * resources.displayMetrics.density, 1f)
                    text = sample
                } },
                update = { view ->
                    view.setTextColor(colors.onSurface.toArgb())
                    view.typeface = face
                    view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f * values.sizeScale)
                    view.textScaleX = values.widthScale
                    view.letterSpacing = values.letterSpacing
                })
        }
    }
}

@Composable
private fun FontEffectParameter(title: String, value: Int, range: IntRange, enabled: Boolean,
    steps: Int = range.last - range.first - 1, suffix: String = "", hint: String? = null,
    decimal: Boolean = false, invalid: (Boolean) -> Unit, changed: (Int) -> Unit) {
    fun display(number: Int) = if (decimal) String.format(Locale.ROOT, "%.2f", number / 100f) else number.toString()
    fun parse(text: String): Int? = if (decimal) text.toFloatOrNull()?.takeIf { it.isFinite() }?.let {
        if (it < range.first / 100f || it > range.last / 100f) null else (it * 100).roundToInt()
    } else text.toIntOrNull()?.takeIf { it in range }
    var text by remember { mutableStateOf(display(value)) }
    var error by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val latestInvalid by rememberUpdatedState(invalid)
    LaunchedEffect(value) {
        if (parse(text) != value) { text = display(value); error = false; invalid(false) }
    }
    DisposableEffect(Unit) { onDispose { latestInvalid(false) } }
    Column(Modifier.padding(vertical = 9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                hint?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            ConsoleTextField(text, { entered ->
                text = entered
                val number = parse(entered)
                error = number == null
                invalid(error)
                if (number != null) changed(number)
            }, Modifier.width(if (decimal) 96.dp else 88.dp).semantics { contentDescription = title },
                singleLine = true, enabled = enabled, isError = error,
                trailingIcon = if (suffix.isEmpty()) null else ({ Text(suffix, fontSize = 12.sp) }),
                textStyle = MaterialTheme.typography.bodyMedium.copy(textAlign = TextAlign.Center),
                keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number,
                    imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }))
        }
        Slider(value.toFloat().coerceIn(range.first.toFloat(), range.last.toFloat()), {
            focus.clearFocus()
            val number = it.roundToInt()
            text = display(number)
            error = false
            invalid(false)
            changed(number)
        }, enabled = enabled, steps = steps, valueRange = range.first.toFloat()..range.last.toFloat(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 36.dp).semantics { contentDescription = title })
        if (error) Text("请输入 ${display(range.first)}–${display(range.last)}$suffix", fontSize = 12.sp,
            color = MaterialTheme.colorScheme.error)
    }
}
