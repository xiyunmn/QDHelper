package com.xiyunmn.qdhelper.feature.reader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.feature.api.runtime.InstallableFeature
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols as QDReaderHost
import com.xiyunmn.qdhelper.foundation.runtime.HostSessionStore
import com.xiyunmn.qdhelper.config.SettingsRuntime
import com.xiyunmn.qdhelper.shared.ui.dp
import com.xiyunmn.qdhelper.shared.ui.setPressedAlpha
import com.xiyunmn.qdhelper.feature.api.runtime.HookModule
import java.lang.reflect.Field

object ReaderBodyCopyFeature : InstallableFeature {
    override val name: String = "ReaderBodyCopyFeature"

    private const val COPY_BUTTON_TAG = "qdhelper.reader.body_copy"

    private val actionViewIds = linkedMapOf(
        "comment" to listOf("llChapterCommentEdit"),
        "listen" to listOf("llAudio"),
        "dictionary" to listOf("llDictSearch"),
        "mark" to listOf("llMarkLine"),
        "reward" to listOf("llReward"),
        "share" to listOf("llShare", "llShare_other"),
        "dubbing" to listOf("llDubbing"),
        "correction" to listOf("llError"),
    )

    override fun install(
        module: HookModule,
        classLoader: ClassLoader,
        processName: String,
        phase: String,
    ) {
        if (!QDReaderHost.isMainProcess(processName)) return
        val popupClass = FeatureHooks.findClassOrNull(QDReaderHost.Classes.QD_POPUP_WINDOW, classLoader)
        if (popupClass == null) {
            FeatureHooks.w("Reader body copy skipped: QDPopupWindow missing, phase=$phase")
            return
        }
        val constructor = FeatureHooks.findConstructorOrNull(
            popupClass,
            View::class.java,
            Int::class.javaPrimitiveType ?: Int::class.java,
            Int::class.javaPrimitiveType ?: Int::class.java,
        )
        if (constructor == null) {
            FeatureHooks.w("Reader body copy skipped: QDPopupWindow three-argument constructor missing, phase=$phase")
            return
        }
        FeatureHooks.hookAfter(
            module = module,
            executable = constructor,
            feature = "reader.bodyCopy:QDPopupWindow constructor",
            id = "qdhelper.reader.body_copy.toolbar",
        ) { chain, _ ->
            val context = HostSessionStore.contextOrNull()
            val window = chain.thisObject as? PopupWindow
            val popup = chain.args.getOrNull(0) as? View
            if (SettingsRuntime.isModuleEnabled(context) && window != null && popup != null) {
                runCatching { customizeToolbar(window, popup) }
                    .onFailure { throwable ->
                        FeatureHooks.w("Reader body copy toolbar attachment failed: ${throwable.javaClass.name}")
                    }
            }
        }
    }

    private fun customizeToolbar(window: PopupWindow, popup: View) {
        if (popup.tag != "Reader") return
        val container = findActionContainer(popup) ?: return
        val copyEnabled = SettingsRuntime.isReaderBodyCopyEnabled(HostSessionStore.contextOrNull())
        val existingCopy = (0 until container.childCount)
            .map(container::getChildAt)
            .firstOrNull { it.tag == COPY_BUTTON_TAG }
        if (copyEnabled && existingCopy == null) {
            val content = selectedContent(popup)
            if (!content.isNullOrBlank()) {
                createCopyButton(popup, container, content, window)?.let(container::addView)
            }
        } else if (!copyEnabled) {
            removeInjectedCopy(container)
        }
        if (copyEnabled) ensureScrollableWidth(popup, container)
        reorderActions(container, SettingsRuntime.paragraphActionOrder(HostSessionStore.contextOrNull()))
    }

    private fun findActionContainer(popup: View): LinearLayout? {
        val id = hostId(popup, "layContainer")
        if (id == 0) return null
        return popup.findViewById<View>(id) as? LinearLayout
    }

    private fun selectedContent(popup: View): String? {
        val bookMark = allFields(popup.javaClass).firstNotNullOfOrNull { field ->
            if (field.type.name != QDReaderHost.Classes.QD_BOOK_MARK_ITEM) return@firstNotNullOfOrNull null
            readField(field, popup)
        } ?: return null
        return runCatching {
            bookMark.javaClass.getField("MarkSelectedContent").get(bookMark) as? String
        }.getOrNull()
    }

    private fun createCopyButton(
        popup: View,
        container: LinearLayout,
        content: String,
        window: PopupWindow,
    ): View? {
        val template = actionViewIds["correction"]
            .orEmpty()
            .asSequence()
            .map { hostId(popup, it) }
            .filter { it != 0 }
            .mapNotNull { popup.findViewById<View>(it) }
            .firstOrNull()
            ?: (0 until container.childCount).map(container::getChildAt).firstOrNull()
            ?: return null
        val templateLabel = findDescendant(template, TextView::class.java) ?: return null
        val templateIcon = findDescendant(template, ImageView::class.java)
        val iconWidth = templateIcon?.layoutParams?.width?.takeIf { it > 0 } ?: popup.context.dp(20)
        val iconHeight = templateIcon?.layoutParams?.height?.takeIf { it > 0 } ?: popup.context.dp(20)

        return LinearLayout(popup.context).apply {
            tag = COPY_BUTTON_TAG
            id = View.generateViewId()
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            contentDescription = "复制"
            isClickable = true
            setPressedAlpha()
            addView(
                CopyIconView(context, templateLabel.currentTextColor),
                LinearLayout.LayoutParams(iconWidth, iconHeight),
            )
            addView(TextView(context).apply {
                text = "复制"
                setTextSize(TypedValue.COMPLEX_UNIT_PX, templateLabel.textSize)
                typeface = templateLabel.typeface
                setTextColor(templateLabel.currentTextColor)
                gravity = templateLabel.gravity
                includeFontPadding = templateLabel.includeFontPadding
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = (templateLabel.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin
                    ?: popup.context.dp(4)
            })
            layoutParams = copyLinearParams(template.layoutParams)
            setOnClickListener {
                copyToClipboard(context, content)
                window.dismiss()
            }
        }
    }

    private fun copyToClipboard(context: Context, content: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard == null) {
            Toast.makeText(context, "复制失败", Toast.LENGTH_SHORT).show()
            return
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("正文内容", content))
        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
    }

    private fun removeInjectedCopy(container: LinearLayout) {
        for (index in container.childCount - 1 downTo 0) {
            if (container.getChildAt(index).tag == COPY_BUTTON_TAG) {
                container.removeViewAt(index)
            }
        }
    }

    private fun reorderActions(container: LinearLayout, configuredOrder: List<String>) {
        val rank = ParagraphActionOrderPolicy.normalize(configuredOrder.joinToString(","))
            .withIndex()
            .associate { (index, key) -> key to index }
        val views = (0 until container.childCount).map { index ->
            IndexedValue(index, container.getChildAt(index))
        }
        val sorted = views.sortedWith(
            compareBy<IndexedValue<View>> { rank[actionKey(it.value)] ?: Int.MAX_VALUE }
                .thenBy { it.index },
        )
        if (views.map { it.value } == sorted.map { it.value }) return
        val params = sorted.associate { it.value to it.value.layoutParams }
        container.removeAllViews()
        sorted.forEach { indexed -> container.addView(indexed.value, params.getValue(indexed.value)) }
    }

    private fun actionKey(view: View): String? {
        if (view.tag == COPY_BUTTON_TAG) return "copy"
        return actionViewIds.entries.firstOrNull { (_, names) ->
            names.any { name ->
                val id = hostId(view, name)
                id != 0 && view.id == id
            }
        }?.key
    }

    private fun ensureScrollableWidth(popup: View, container: LinearLayout) {
        val visibleWidth = (0 until container.childCount).sumOf { index ->
            val child = container.getChildAt(index)
            if (child.visibility == View.GONE) 0 else child.layoutParams?.width?.takeIf { it > 0 } ?: popup.context.dp(48)
        }
        container.layoutParams = container.layoutParams.apply { width = visibleWidth }
        val desiredWidth = visibleWidth + popup.context.dp(16)
        val maxWidth = popup.resources.displayMetrics.widthPixels - popup.context.dp(16)
        if (desiredWidth <= maxWidth) return

        popup.findViewById<View>(hostId(popup, "layRoot"))?.let { root ->
            (root.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
                params.width = maxWidth
                params.marginStart = popup.context.dp(8)
                params.marginEnd = popup.context.dp(8)
                root.layoutParams = params
            }
        }
        popup.findViewById<View>(hostId(popup, "layoutContent"))?.let { content ->
            content.layoutParams = content.layoutParams.apply { width = maxWidth }
        }
        val scroll = popup.findViewById<View>(hostId(popup, "scrollHead")) as? HorizontalScrollView
            ?: return
        val rightArrow = popup.findViewById<View>(hostId(popup, "ivArrow"))
        val leftArrow = popup.findViewById<View>(hostId(popup, "ivArrowLeft"))
        val arrowInset = rightArrow?.layoutParams?.width?.takeIf { it > 0 } ?: popup.context.dp(40)
        scroll.setPaddingRelative(
            scroll.paddingStart,
            scroll.paddingTop,
            maxOf(scroll.paddingEnd, arrowInset),
            scroll.paddingBottom,
        )
        scroll.setOnTouchListener(null)

        fun updateArrows() {
            val hasScrolled = scroll.scrollX > 0
            leftArrow?.visibility = if (hasScrolled) View.VISIBLE else View.GONE
            rightArrow?.visibility = if (!hasScrolled && scroll.canScrollHorizontally(1)) {
                View.VISIBLE
            } else {
                View.GONE
            }
        }
        scroll.setOnScrollChangeListener { _, _, _, _, _ -> updateArrows() }
        scroll.post { updateArrows() }
    }

    private fun hostId(view: View, name: String): Int {
        return view.resources.getIdentifier(name, "id", view.context.packageName)
    }

    private fun allFields(type: Class<*>): Sequence<Field> = sequence {
        var current: Class<*>? = type
        while (current != null && current != Any::class.java) {
            yieldAll(FeatureHooks.allDeclaredFields(current).asSequence())
            current = current.superclass
        }
    }

    private fun readField(field: Field, receiver: Any): Any? {
        return FeatureHooks.read(field, receiver)
    }

    private fun <T : View> findDescendant(root: View, type: Class<T>): T? {
        if (type.isInstance(root)) return type.cast(root)
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                findDescendant(root.getChildAt(index), type)?.let { return it }
            }
        }
        return null
    }

    private fun copyLinearParams(source: ViewGroup.LayoutParams?): LinearLayout.LayoutParams {
        return if (source is LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams(source)
        } else {
            LinearLayout.LayoutParams(
                source?.width ?: ViewGroup.LayoutParams.WRAP_CONTENT,
                source?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
    }

    private class CopyIconView(context: android.content.Context, color: Int) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = resources.displayMetrics.density * 1.6f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            this.color = color
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val unit = resources.displayMetrics.density
            val left = (width - 15f * unit) / 2f
            val top = (height - 16f * unit) / 2f
            val radius = 1.5f * unit
            canvas.drawRoundRect(
                RectF(left + 4f * unit, top, left + 15f * unit, top + 12f * unit),
                radius,
                radius,
                paint,
            )
            canvas.drawRoundRect(
                RectF(left, top + 4f * unit, left + 11f * unit, top + 16f * unit),
                radius,
                radius,
                paint,
            )
        }
    }
}
