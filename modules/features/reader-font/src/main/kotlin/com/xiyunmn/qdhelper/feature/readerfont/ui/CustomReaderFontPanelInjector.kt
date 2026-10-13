package com.xiyunmn.qdhelper.feature.readerfont.ui

import com.xiyunmn.qdhelper.shared.ui.installModuleWatermark
import com.xiyunmn.qdhelper.feature.readerfont.importflow.CustomReaderFontImporter
import com.xiyunmn.qdhelper.shared.fonts.FontEntry
import com.xiyunmn.qdhelper.shared.fonts.FontFileState
import com.xiyunmn.qdhelper.shared.fonts.FontLibraryStore
import com.xiyunmn.qdhelper.shared.fonts.FontRole
import com.xiyunmn.qdhelper.shared.fonts.FontSelectionStore
import com.xiyunmn.qdhelper.shared.fonts.FontTypefaceCache
import com.xiyunmn.qdhelper.shared.fonts.displayLabel
import com.xiyunmn.qdhelper.shared.fonts.isUsable
import com.xiyunmn.qdhelper.shared.ui.showModuleConfirmDialog

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.RelativeLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureResources
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols as QDReaderHost
import com.xiyunmn.qdhelper.config.SettingsRuntime
import com.xiyunmn.qdhelper.shared.ui.DragReorderList
import java.util.WeakHashMap

internal object CustomReaderFontPanelInjector {
    private const val PANEL_CLASS = QDReaderHost.Classes.READER_FONT_PANEL
    private const val ACTIONS_TAG = "qdhelper_custom_reader_font_actions"
    private const val STRIP_TAG = "qdhelper_custom_reader_font_strip"
    private const val CUSTOM_LABEL_TAG = "qdhelper_custom_reader_font_label"
    private val panelOverlays = WeakHashMap<View, ReaderFontPanelOverlay>()

    init {
        FeatureResources.register("reader-font.panel-overlays") {
            val release = Runnable {
                panelOverlays.values.toList().forEach { it.close() }
                panelOverlays.clear()
            }
            if (Looper.myLooper() == Looper.getMainLooper()) release.run()
            else Handler(Looper.getMainLooper()).postAtFrontOfQueue(release)
        }
    }

    fun inject(panel: View): Boolean {
        return runCatching {
            val activity = resolveActivity(panel.context) ?: return@runCatching false
            val palette = palette(panel)
            injectHeaderActions(panel, activity, palette)
            syncFontItems(panel, activity)
            refreshVisibleFontSelection(panel, activity)
            true
        }.getOrElse { throwable ->
            FeatureHooks.w("Custom reader font panel inject failed: ${throwable.javaClass.name}")
            false
        }
    }

    fun refreshVisible(activity: Activity) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        traverse(root) { view ->
            if (view.javaClass.name == PANEL_CLASS) {
                inject(view)
            }
        }
    }

    fun schedulePanelShown(panel: View) {
        restoreHostBottomBar(panel)
        panelOverlays[panel] = ReaderFontPanelOverlay(panel) { shownPanel ->
            inject(shownPanel)
            val menu = shownPanel.parent as? View ?: return@ReaderFontPanelOverlay null
            val bottomBar = findViewByName<View>(menu, "bottomBarLayout")
                ?.takeIf { it.visibility == View.VISIBLE } ?: return@ReaderFontPanelOverlay null
            (bottomBar.parent as? View)?.takeUnless { it === menu } ?: bottomBar
        }
    }

    fun restoreHostBottomBar(panel: View) {
        panelOverlays.remove(panel)?.close()
    }

    private fun injectHeaderActions(panel: View, activity: Activity, palette: Palette) {
        val header = findViewByName<View>(panel, "ivBack")?.parent as? RelativeLayout
            ?: findViewByName<View>(panel, "tvTitle")?.parent as? RelativeLayout
            ?: return
        if (header.findViewWithTag<View>(ACTIONS_TAG) != null) {
            return
        }
        val actions = LinearLayout(activity).apply {
            tag = ACTIONS_TAG
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        actions.addView(
            IconButtonView(activity, IconKind.IMPORT, palette.icon).apply {
                contentDescription = "导入字体"
                setOnClickListener {
                    CustomReaderFontImporter.open(activity)
                }
            },
            LinearLayout.LayoutParams(dp(activity, 42), dp(activity, 42)),
        )
        actions.addView(
            IconButtonView(activity, IconKind.MANAGE, palette.icon).apply {
                contentDescription = "管理字体"
                setOnClickListener {
                    showManager(panel, activity, palette)
                }
            },
            LinearLayout.LayoutParams(dp(activity, 42), dp(activity, 42)),
        )
        header.addView(
            actions,
            RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                addRule(RelativeLayout.ALIGN_PARENT_END)
                addRule(RelativeLayout.CENTER_VERTICAL)
                marginEnd = dp(activity, 12)
            },
        )
    }

    fun bindAdapterItem(holder: Any?, item: Any?): Boolean {
        val itemView = CustomReaderFontHostBridge.itemViewFromHolder(holder) ?: return false
        val path = CustomReaderFontHostBridge.customPathFromFontItem(item)
        if (path == null) {
            clearCustomFontItem(itemView)
            return false
        }
        val normalizedPath = FontLibraryStore.normalizePath(path)
        val activity = resolveActivity(itemView.context) ?: return false
        styleCustomFontItem(itemView, activity, normalizedPath)
        return true
    }

    fun clearCustomSelection(panel: View) {
        val activity = resolveActivity(panel.context) ?: return
        if (!SettingsRuntime.isCustomReaderFontEnabled(activity)) {
            return
        }
        val previousPath = SettingsRuntime.customReaderFontPath(activity).takeIf { it.isNotBlank() }
        FontSelectionStore.clear(activity, FontRole.READER)
        notifyFontItemsChanged(panel, listOfNotNull(previousPath))
        refreshVisibleFontSelection(panel, activity)
    }

    private fun syncFontItems(panel: View, activity: Activity) {
        val columnView = findViewByName<View>(panel, "columnView") ?: return
        (columnView.parent as? ViewGroup)?.let { parent -> removeTaggedChild(parent, STRIP_TAG) }

        val fonts = FontLibraryStore.readFonts(activity)
        val adapter = CustomReaderFontHostBridge.callNoArg(columnView, "getAdapter") ?: return
        val values = CustomReaderFontHostBridge.adapterValues(adapter) ?: return
        val nativeItemCount = values.count { value -> CustomReaderFontHostBridge.customPathFromFontItem(value) == null }
        val existingCustomPaths = values.mapNotNull(CustomReaderFontHostBridge::customPathFromFontItem)
        if (existingCustomPaths == fonts) {
            applyFontGridHeight(columnView, activity, nativeItemCount, fonts.isNotEmpty())
            columnView.post {
                refreshVisibleFontSelection(panel, activity)
            }
            return
        }
        if (existingCustomPaths != fonts) {
            values.removeAll { value -> CustomReaderFontHostBridge.customPathFromFontItem(value) != null }
            fonts.mapNotNullTo(values) { path ->
                CustomReaderFontHostBridge.createHostFontItem(
                    adapter.javaClass.classLoader ?: columnView.javaClass.classLoader,
                    path,
                )
            }
        }
        applyFontGridHeight(columnView, activity, nativeItemCount, fonts.isNotEmpty())
        CustomReaderFontHostBridge.callNoArg(adapter, "notifyDataSetChanged")
        columnView.requestLayout()
        columnView.post {
            refreshVisibleFontSelection(panel, activity)
        }
    }

    private fun showManager(panel: View, activity: Activity, palette: Palette) {
        val entries = FontLibraryStore.readFontEntries(activity)
        if (entries.isEmpty()) {
            Toast.makeText(activity, "暂无已导入字体", Toast.LENGTH_SHORT).show()
            return
        }
        val metrics = activity.resources.displayMetrics
        val popupWidth = (metrics.widthPixels - dp(activity, 32)).coerceAtMost(dp(activity, 460))
        val popupHeight = (metrics.heightPixels * 6 / 10).coerceAtMost(dp(activity, 520))
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 16), dp(activity, 12), dp(activity, 16), dp(activity, 14))
            background = rounded(palette.popupBackground, dp(activity, 10).toFloat())
        }
        content.installModuleWatermark()
        val popup = PopupWindow(content, popupWidth, popupHeight, true).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }

        val titleRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(
            TextView(activity).apply {
                text = "管理共享字体库"
                textSize = 17f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(palette.text)
                includeFontPadding = false
            },
            LinearLayout.LayoutParams(0, dp(activity, 44), 1f),
        )
        titleRow.addView(
            IconButtonView(activity, IconKind.CLOSE, palette.icon).apply {
                contentDescription = "关闭"
                setOnClickListener { popup.dismiss() }
            },
            LinearLayout.LayoutParams(dp(activity, 42), dp(activity, 42)),
        )
        content.addView(titleRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val list = DragReorderList(
            activity,
            handleColor = palette.icon,
            elevatedColor = palette.fontItemBackground,
            dividerColor = palette.disabledIcon,
        )
        list.setRows(
            keys = entries.map(FontEntry::path),
            renderContent = { path ->
                val entry = entries.firstOrNull { it.path == path }
                    ?: FontEntry(path, FontFileState.MISSING, managed = false)
                createManagerRowContent(panel, activity, palette, entry, popup)
            },
            onReorder = { newOrder ->
                if (FontLibraryStore.writeFonts(activity, newOrder)) {
                    refreshVisible(activity)
                }
            },
        )
        content.addView(
            ScrollView(activity).apply {
                isVerticalScrollBarEnabled = true
                addView(list, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        popup.showAtLocation(panel, Gravity.CENTER, 0, 0)
    }

    private fun createManagerRowContent(
        panel: View,
        activity: Activity,
        palette: Palette,
        entry: FontEntry,
        popup: PopupWindow,
    ): View {
        val path = entry.path
        val selected = SettingsRuntime.isCustomReaderFontEnabled(activity) &&
            FontLibraryStore.normalizePath(SettingsRuntime.customReaderFontPath(activity)) == path
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(activity, 60)
            val selectArea = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                if (entry.state.isUsable) {
                    setOnClickListener {
                        selectFont(panel, activity, path)
                        popup.dismiss()
                    }
                } else {
                    isClickable = false
                    alpha = 0.72f
                }
                addView(
                    LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, 0, dp(activity, 10), 0)
                        addView(TextView(activity).apply {
                            text = FontLibraryStore.displayNameForPath(path)
                            textSize = 15f
                            setTextColor(palette.text)
                            maxLines = 1
                            ellipsize = TextUtils.TruncateAt.END
                            includeFontPadding = false
                            loadTypeface(path)?.let { typeface = it }
                        })
                        addView(TextView(activity).apply {
                            text = buildString {
                                append(path)
                                if (!entry.managed) append(" · 外部文件")
                                if (!entry.state.isUsable) append(" · ").append(entry.state.displayLabel())
                            }
                            textSize = 11f
                            setTextColor(palette.subText)
                            maxLines = 1
                            ellipsize = TextUtils.TruncateAt.MIDDLE
                            includeFontPadding = false
                        })
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
                addView(
                    IconButtonView(activity, IconKind.CHECK, if (selected) palette.accent else Color.TRANSPARENT).apply {
                        contentDescription = "已选择"
                    },
                    LinearLayout.LayoutParams(dp(activity, 38), dp(activity, 38)),
                )
            }
            addView(selectArea, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(
                IconButtonView(activity, IconKind.DELETE, palette.icon).apply {
                    contentDescription = "删除字体"
                    setOnClickListener {
                        showModuleConfirmDialog(
                            activity = activity,
                            title = "确认删除",
                            message = "删除“${FontLibraryStore.displayNameForPath(path)}”？此操作无法撤销。",
                            confirmText = "删除",
                        ) {
                            val result = FontLibraryStore.deleteFont(activity, path)
                            if (!result.settingsUpdated) {
                                Toast.makeText(activity, "删除字体失败", Toast.LENGTH_SHORT).show()
                            } else {
                                syncFontItems(panel, activity)
                                val message = if (result.fileDeleted) {
                                    "已删除 ${FontLibraryStore.displayNameForPath(path)}"
                                } else {
                                    "已从列表移除，但字体文件删除失败"
                                }
                                Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
                                popup.dismiss()
                            }
                        }
                    }
                },
                LinearLayout.LayoutParams(dp(activity, 42), dp(activity, 42)).apply {
                    marginEnd = dp(activity, 2)
                },
            )
        }
    }

    private fun selectFont(panel: View, activity: Activity, path: String) {
        val normalizedPath = FontLibraryStore.normalizePath(path)
        val customWasEnabled = SettingsRuntime.isCustomReaderFontEnabled(activity)
        val previousPath = SettingsRuntime.customReaderFontPath(activity).takeIf { customWasEnabled && it.isNotBlank() }
        val previousHostIndex = if (customWasEnabled) null else CustomReaderFontHostBridge.findHostSelectedFontIndex(panel)
        val selected = customWasEnabled &&
            previousPath != null && FontLibraryStore.normalizePath(previousPath) == normalizedPath
        if (selected) {
            return
        }
        if (!FontSelectionStore.select(activity, FontRole.READER, normalizedPath)) {
            Toast.makeText(activity, "字体无法使用", Toast.LENGTH_SHORT).show()
            return
        }
        notifyFontItemsChanged(
            panel = panel,
            paths = listOfNotNull(previousPath, normalizedPath),
            indices = previousHostIndex?.let(::setOf).orEmpty(),
        )
        refreshVisibleFontSelection(panel, activity)
        if (!CustomReaderFontHostBridge.reloadReaderFontAndCurrentChapter(panel)) {
            Toast.makeText(activity, "字体已保存，阅读页刷新失败，请重新进入阅读页", Toast.LENGTH_SHORT).show()
        }
    }

    fun selectCustomFont(panel: View, path: String) {
        val activity = resolveActivity(panel.context) ?: return
        selectFont(panel, activity, path)
    }

    private fun styleCustomFontItem(itemView: View, activity: Activity, path: String) {
        val panel = findPanelAncestor(itemView)
        val palette = palette(panel ?: itemView)
        val selected = SettingsRuntime.isCustomReaderFontEnabled(activity) &&
            FontLibraryStore.normalizePath(SettingsRuntime.customReaderFontPath(activity)) == FontLibraryStore.normalizePath(path)
        val rootView = findViewByName<View>(itemView, "rootView") ?: itemView
        val rootGroup = rootView as? ViewGroup ?: return
        removeTaggedDescendant(rootGroup, CUSTOM_LABEL_TAG)

        findViewByName<View>(itemView, "ivFont")?.visibility = View.INVISIBLE
        findViewByName<View>(itemView, "sizeTagView")?.visibility = View.GONE
        findViewByName<View>(itemView, "downloadLayout")?.visibility = View.GONE
        val check = findViewByName<View>(itemView, "ivCheck")
        check?.visibility = if (selected) View.VISIBLE else View.GONE
        tintImageView(check, palette.accent)
        setFontItemBackground(
            rootView,
            if (selected) palette.selectedBackground else palette.fontItemBackground,
        )

        rootGroup.addView(
            TextView(activity).apply {
                tag = CustomLabelTag(path)
                text = FontLibraryStore.displayNameForPath(path)
                textSize = 14f
                gravity = Gravity.CENTER_VERTICAL
                includeFontPadding = false
                setPadding(0, 0, dp(activity, 8), 0)
                setTextColor(if (selected) palette.selectedText else palette.text)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
            RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                addRule(RelativeLayout.CENTER_VERTICAL)
                check?.id?.takeIf { it != View.NO_ID }?.let { checkId ->
                    addRule(RelativeLayout.END_OF, checkId)
                }
            },
        )
    }

    private fun refreshVisibleFontSelection(panel: View, activity: Activity) {
        val selectedPath = if (SettingsRuntime.isCustomReaderFontEnabled(activity)) {
            SettingsRuntime.customReaderFontPath(activity).takeIf { it.isNotBlank() }?.let(FontLibraryStore::normalizePath)
        } else {
            null
        }
        traverse(panel) { view ->
            val rootGroup = view as? ViewGroup ?: return@traverse
            val check = findViewByName<View>(rootGroup, "ivCheck") ?: return@traverse
            val fontIcon = findViewByName<View>(rootGroup, "ivFont") ?: return@traverse
            if (check.parent !== rootGroup || fontIcon.parent !== rootGroup) {
                return@traverse
            }
            val customLabel = findCustomLabel(rootGroup)
            val itemPalette = palette(findPanelAncestor(rootGroup) ?: panel)
            if (customLabel != null) {
                val selected = selectedPath != null && FontLibraryStore.normalizePath(customLabel.path) == selectedPath
                check.visibility = if (selected) View.VISIBLE else View.GONE
                tintImageView(check, itemPalette.accent)
                fontIcon.visibility = View.INVISIBLE
                findViewByName<View>(rootGroup, "sizeTagView")?.visibility = View.GONE
                findViewByName<View>(rootGroup, "downloadLayout")?.visibility = View.GONE
                setFontItemBackground(
                    rootGroup,
                    if (selected) itemPalette.selectedBackground else itemPalette.fontItemBackground,
                )
                customLabel.label.setTextColor(if (selected) itemPalette.selectedText else itemPalette.text)
                (customLabel.label.layoutParams as? RelativeLayout.LayoutParams)?.let { params ->
                    val checkId = check.id
                    if (checkId != View.NO_ID) {
                        params.addRule(RelativeLayout.END_OF, checkId)
                    }
                    if (params.marginStart != 0) {
                        params.marginStart = 0
                        customLabel.label.layoutParams = params
                    }
                }
            } else if (selectedPath != null) {
                check.visibility = View.GONE
                setFontItemBackground(rootGroup, itemPalette.fontItemBackground)
            }
        }
    }

    private fun clearCustomFontItem(itemView: View) {
        removeTaggedDescendant(itemView, CUSTOM_LABEL_TAG)
        findViewByName<View>(itemView, "ivFont")?.visibility = View.VISIBLE
        if (SettingsRuntime.isCustomReaderFontEnabled(itemView.context)) {
            val check = findViewByName<View>(itemView, "ivCheck")
            val wasChecked = check?.visibility == View.VISIBLE
            check?.visibility = View.GONE
            if (wasChecked) {
                val panel = findPanelAncestor(itemView)
                val palette = palette(panel ?: itemView)
                val rootView = findViewByName<View>(itemView, "rootView") ?: itemView
                setFontItemBackground(rootView, palette.fontItemBackground)
            }
        }
    }

    private fun notifyFontItemsChanged(panel: View, paths: List<String>, indices: Set<Int> = emptySet()) {
        if (paths.isEmpty() && indices.isEmpty()) {
            return
        }
        val columnView = findViewByName<View>(panel, "columnView") ?: return
        val adapter = CustomReaderFontHostBridge.callNoArg(columnView, "getAdapter") ?: return
        val values = CustomReaderFontHostBridge.adapterValues(adapter) ?: return
        val normalizedPaths = paths.map(FontLibraryStore::normalizePath).toSet()
        val notifyItemChanged = FeatureHooks.declaredMethodsNamed(adapter.javaClass, "notifyItemChanged").singleOrNull { method ->
            method.name == "notifyItemChanged" &&
                method.parameterTypes.size == 1 &&
                method.parameterTypes[0] == Integer.TYPE
        } ?: return
        values.forEachIndexed { index, item ->
            val itemPath = CustomReaderFontHostBridge.customPathFromFontItem(item)?.let(FontLibraryStore::normalizePath)
            if (index in indices || (itemPath != null && itemPath in normalizedPaths)) {
                runCatching { FeatureHooks.invoke(notifyItemChanged, adapter, index) }
            }
        }
    }

    private fun palette(root: View): Palette {
        val context = root.context
        val title = findViewByName<TextView>(root, "tvTitle")
        val fallbackText = title?.currentTextColor ?: Color.rgb(40, 40, 40)
        val rootContainer = findViewByName<View>(root, "rootContainer") ?: findViewByName(root, "rootView") ?: root
        val background = resolveBackgroundColor(rootContainer) ?: resolveBackgroundColor(root) ?: Color.WHITE
        val night = luminance(background) < 0.35f
        val themeText = CustomReaderFontHostBridge.readerThemeColor(root, "s")
        val text = themeText?.let { withAlpha(it, 0.7f) } ?: fallbackText
        val accent = CustomReaderFontHostBridge.readerThemeColor(root, "u")
            ?: title?.linkTextColors?.defaultColor
            ?: text
        return Palette(
            text = text,
            subText = withAlpha(text, if (night) 0.62f else 0.58f),
            icon = withAlpha(text, 0.74f),
            disabledIcon = withAlpha(text, 0.24f),
            popupBackground = opaque(background),
            chipBackground = withAlpha(text, if (night) 0.14f else 0.06f),
            fontItemBackground = withAlpha(themeText ?: text, 0.04f),
            selectedBackground = withAlpha(accent, 0.08f),
            selectedText = accent,
            accent = accent,
        )
    }

    private fun <T : View> findViewByName(root: View, name: String): T? {
        val id = root.resources.getIdentifier(name, "id", root.context.packageName)
        if (id == 0) {
            return null
        }
        @Suppress("UNCHECKED_CAST")
        return root.findViewById<View>(id) as? T
    }

    private fun applyFontGridHeight(columnView: View, activity: Activity, nativeItemCount: Int, hasCustomFonts: Boolean) {
        val layoutParams = columnView.layoutParams ?: return
        if (!hasCustomFonts) {
            if (layoutParams.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
                layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                columnView.layoutParams = layoutParams
            }
            columnView.isNestedScrollingEnabled = false
            columnView.isVerticalScrollBarEnabled = false
            return
        }
        val columnCount = if (activity.resources.configuration.screenWidthDp >= 640) 3 else 2
        val nativeRows = ((nativeItemCount + columnCount - 1) / columnCount).coerceAtLeast(1)
        val targetHeight = nativeRows * dp(activity, 48) + columnView.paddingTop + columnView.paddingBottom
        val maxHeight = (activity.resources.displayMetrics.heightPixels * 0.34f).toInt()
        val height = targetHeight.coerceAtMost(maxHeight).coerceAtLeast(dp(activity, 120))
        if (layoutParams.height != height) {
            layoutParams.height = height
            columnView.layoutParams = layoutParams
        }
        columnView.isNestedScrollingEnabled = true
        columnView.isVerticalScrollBarEnabled = true
        columnView.overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
    }

    private fun removeTaggedChild(parent: ViewGroup, tag: String) {
        for (index in parent.childCount - 1 downTo 0) {
            if (parent.getChildAt(index).tag == tag) {
                parent.removeViewAt(index)
            }
        }
    }

    private fun removeTaggedDescendant(view: View, tag: String) {
        if (view is ViewGroup) {
            for (index in view.childCount - 1 downTo 0) {
                val child = view.getChildAt(index)
                if (isCustomTag(child.tag, tag)) {
                    view.removeViewAt(index)
                } else {
                    removeTaggedDescendant(child, tag)
                }
            }
        }
    }

    private fun findCustomLabel(view: View): CustomLabel? {
        if (view is TextView) {
            val tag = view.tag
            if (tag is CustomLabelTag) {
                return CustomLabel(tag.path, view)
            }
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findCustomLabel(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    private fun isCustomTag(value: Any?, tag: String): Boolean {
        return value == tag || (tag == CUSTOM_LABEL_TAG && value is CustomLabelTag)
    }

    private fun findPanelAncestor(view: View): View? {
        var current: View? = view
        while (current != null) {
            if (current.javaClass.name == PANEL_CLASS) {
                return current
            }
            current = current.parent as? View
        }
        return null
    }

    private fun resolveBackgroundColor(view: View): Int? {
        val color = (view.background as? ColorDrawable)?.color
        if (color != null && Color.alpha(color) > 0) {
            return color
        }
        val solid = view.solidColor
        return solid.takeIf { Color.alpha(it) > 0 }
    }

    private fun resolveActivity(context: Context?): Activity? {
        var current = context
        while (current is ContextWrapper) {
            if (current is Activity) {
                return current
            }
            current = current.baseContext
        }
        return current as? Activity
    }

    private fun loadTypeface(path: String): Typeface? {
        return FontTypefaceCache.load(path)
    }

    private fun setFontItemBackground(view: View, color: Int) {
        view.setBackgroundColor(color)
    }

    private fun tintImageView(view: View?, color: Int) {
        val imageView = view as? ImageView ?: return
        val drawable = imageView.drawable ?: return
        val tinted = drawable.constantState?.newDrawable(imageView.resources)?.mutate() ?: drawable.mutate()
        tinted.setTint(color)
        imageView.setImageDrawable(tinted)
    }

    private fun rounded(color: Int, radius: Float): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
        }
    }

    private fun traverse(view: View, action: (View) -> Unit) {
        action(view)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                traverse(view.getChildAt(index), action)
            }
        }
    }

    private fun withAlpha(color: Int, alpha: Float): Int {
        return Color.argb((alpha * 255).toInt().coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
    }

    private fun opaque(color: Int): Int {
        return Color.rgb(Color.red(color), Color.green(color), Color.blue(color))
    }

    private fun luminance(color: Int): Float {
        return (0.299f * Color.red(color) + 0.587f * Color.green(color) + 0.114f * Color.blue(color)) / 255f
    }

    private fun dp(context: Context, value: Int): Int {
        return (value * context.resources.displayMetrics.density + 0.5f).toInt()
    }

    private data class Palette(
        val text: Int,
        val subText: Int,
        val icon: Int,
        val disabledIcon: Int,
        val popupBackground: Int,
        val chipBackground: Int,
        val fontItemBackground: Int,
        val selectedBackground: Int,
        val selectedText: Int,
        val accent: Int,
    )

    private data class CustomLabelTag(val path: String)

    private data class CustomLabel(val path: String, val label: TextView)

}
