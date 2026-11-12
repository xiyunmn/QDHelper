package com.xiyunmn.qdhelper.feature.contenttools

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.xiyunmn.qdhelper.capability.originalimage.OriginalImageDialog
import com.xiyunmn.qdhelper.config.ConfigRuntime
import com.xiyunmn.qdhelper.config.SettingIds
import com.xiyunmn.qdhelper.foundation.runtime.HostSessionStore
import com.xiyunmn.qdhelper.config.SettingsRuntime
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureResources
import com.xiyunmn.qdhelper.feature.api.runtime.FlutterBookDetailRuntime
import com.xiyunmn.qdhelper.feature.api.runtime.FlutterSemanticsSupport
import com.xiyunmn.qdhelper.hook.concurrency.ManagedExecutor
import com.xiyunmn.qdhelper.shared.media.ImageGalleryStore
import com.xiyunmn.qdhelper.shared.media.OriginalImageData
import com.xiyunmn.qdhelper.shared.media.RemoteImageDownloader
import java.util.WeakHashMap
import kotlin.math.roundToInt

internal object FlutterBookDetailImages {
    private val buttons = WeakHashMap<Activity, TextView>()
    private val roleTargets = WeakHashMap<Activity, Map<Int, FlutterBookDetailRuntime.Role>>()
    private val coverTargets = WeakHashMap<Activity, Int>()
    private val diagnostics = WeakHashMap<Activity, MutableSet<String>>()
    private val main = Handler(Looper.getMainLooper())
    private val worker = ManagedExecutor("content-tools.cover-save", "QDHelper-cover-save", 1)
    private var configuration: AutoCloseable? = null

    fun register(loadCover: (Context, Long) -> OriginalImageData?) {
        FlutterSemanticsSupport.register(
            "book-original-image",
            enabled = { it.javaClass.name == FlutterBookDetailRuntime.ACTIVITY &&
                (SettingsRuntime.isBookDetailCoverOriginalEnabled(it) || SettingsRuntime.isBookDetailRoleAvatarOriginalEnabled(it)) },
            updated = { activity, view, nodes ->
                updateCover(activity, view, nodes, loadCover)
                val book = FlutterBookDetailRuntime.current(activity)
                val cover = book?.let { coverTarget(view, nodes, it.title) }
                if (cover == null) coverTargets.remove(activity) else coverTargets[activity] = cover.id
                val roles = book?.roles.orEmpty()
                val targets = buildRoleTargets(nodes, roles)
                roleTargets[activity] = targets
                val matches = targets.size
                val shape = if (nodes.size <= 16) nodes.filter { it.image || it.clickable }.take(8)
                    .joinToString { "${it.id}:${it.bounds.toShortString()}:${it.actions}" } else "detail"
                val summary = "roles=${roles.size}, matchedNodes=$matches, images=${nodes.count { it.image }}, coverMatched=${cover != null}, coverSave=${buttons.containsKey(activity)}, shape=$shape"
                val seen = diagnostics.getOrPut(activity) { mutableSetOf() }
                if (seen.size < 6 && seen.add(summary)) FeatureHooks.i("Flutter detail images: $summary")
            },
            longPress = { activity, _, node ->
                val role = roleTargets[activity]?.get(node.id)
                if (role != null && SettingsRuntime.isBookDetailRoleAvatarOriginalEnabled(activity)) {
                    {
                        FeatureHooks.i("Flutter role original image long press matched")
                        OriginalImageDialog.show(activity, role.name, "role_avatar_${role.id}") {
                            RemoteImageDownloader.download(role.imageUrl)
                        }
                    }
                } else null
            },
            tap = { activity, _, node ->
                val book = FlutterBookDetailRuntime.current(activity)
                if (SettingsRuntime.isBookDetailCoverOriginalEnabled(activity) && book != null &&
                    coverTargets[activity] == node.id) {
                    {
                        OriginalImageDialog.show(activity, book.title, "book_cover_${book.id}") {
                            loadCover(activity, book.id)
                        }
                    }
                } else null
            },
        )
        configuration?.close()
        configuration = HostSessionStore.contextOrNull()?.let { context ->
            ConfigRuntime.observeUserChanges(context, setOf(SettingIds.KEY_BOOK_DETAIL_COVER_ORIGINAL_ENABLED, SettingIds.KEY_BOOK_DETAIL_ROLE_AVATAR_ORIGINAL_ENABLED)) {
                main.post {
                    buttons.keys.toList().filterNot(SettingsRuntime::isBookDetailCoverOriginalEnabled).forEach(::detach)
                    FlutterSemanticsSupport.refreshCurrentActivities()
                }
            }
        }
        FeatureResources.register("flutter-book-detail-images") {
            configuration?.close()
            configuration = null
            main.post {
                buttons.keys.toList().forEach(::detach)
                diagnostics.clear()
                roleTargets.clear()
                coverTargets.clear()
            }
        }
    }

    private fun coverTarget(
        view: View,
        nodes: List<FlutterSemanticsSupport.Node>,
        title: String,
    ): FlutterSemanticsSupport.Node? {
        if (title.isBlank() || FlutterBookDetailRuntime.coverPreviewClose(view, nodes) != null) return null
        val headings = nodes.filter { node -> node.bounds.centerY() in 0..view.height / 2 &&
            node.label.lineSequence().any { it.trim() == title } }
        if (headings.isEmpty()) return null
        // Main cover is the unique portrait image to the left of this book's title.
        // A collapsed toolbar can expose the same title, and one image can have multiple
        // semantics nodes. Resolve the header/image relationship before requiring uniqueness.
        return nodes.filter { node ->
            val area = node.bounds
            node.image && area.left >= 0 &&
                area.width() > view.width / 8 && area.width() < view.width / 2 &&
                area.height() > area.width() && area.height() < view.height / 3 &&
                headings.any { heading -> area.right <= heading.bounds.left &&
                    area.top <= heading.bounds.bottom && area.bottom >= heading.bounds.top }
        }.distinctBy { it.bounds }.singleOrNull()
    }

    private fun buildRoleTargets(
        nodes: List<FlutterSemanticsSupport.Node>,
        roles: List<FlutterBookDetailRuntime.Role>,
    ): Map<Int, FlutterBookDetailRuntime.Role> {
        if (roles.isEmpty()) return emptyMap()
        val byId = nodes.associateBy { it.id }
        val candidates = mutableMapOf<Int, MutableSet<FlutterBookDetailRuntime.Role>>()
        nodes.forEach { node ->
            val lines = node.label.lineSequence().map(String::trim).toSet()
            val matched = roles.filter { it.name.isNotBlank() && it.name in lines }
            if (matched.isEmpty()) return@forEach
            candidates.getOrPut(node.id) { mutableSetOf() }.addAll(matched)
            // The nearest tap target joins the separate name and avatar semantics children.
            var parentId = node.parentId
            val visited = mutableSetOf<Int>()
            while (parentId != null && visited.add(parentId)) {
                val parent = byId[parentId] ?: break
                if (parent.clickable) {
                    candidates.getOrPut(parent.id) { mutableSetOf() }.addAll(matched)
                    break
                }
                parentId = parent.parentId
            }
        }
        return candidates.mapNotNull { (id, values) -> values.singleOrNull()?.let { id to it } }.toMap()
    }

    private fun updateCover(
        activity: Activity,
        view: View,
        nodes: List<FlutterSemanticsSupport.Node>,
        loadCover: (Context, Long) -> OriginalImageData?,
    ) {
        val bookId = FlutterBookDetailRuntime.bookId(activity)
        val parent = view.parent as? FrameLayout
        val close = FlutterBookDetailRuntime.coverPreviewClose(view, nodes)
        if (bookId == null || parent == null || close == null ||
            !SettingsRuntime.isBookDetailCoverOriginalEnabled(activity)) {
            detach(activity)
            return
        }
        val density = view.resources.displayMetrics.density
        val width = maxOf((72 * density).roundToInt(), close.bounds.width())
        val sourceLocation = IntArray(2).also(view::getLocationOnScreen)
        val parentLocation = IntArray(2).also(parent::getLocationOnScreen)
        val button = buttons[activity] ?: TextView(activity).apply {
            text = "保存"
            contentDescription = "保存封面到相册"
            gravity = Gravity.CENTER
            textSize = 16f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(0x66000000)
                cornerRadius = 24 * density
            }
            parent.addView(this, FrameLayout.LayoutParams(width, close.bounds.height()))
            buttons[activity] = this
            setOnClickListener {
                if (!SettingsRuntime.isBookDetailCoverOriginalEnabled(activity) || FlutterBookDetailRuntime.bookId(activity) != bookId) return@setOnClickListener
                isEnabled = false
                text = "保存中"
                val accepted = worker.execute {
                    val saved = runCatching {
                        loadCover(activity, bookId)?.let { data ->
                            ImageGalleryStore.save(activity.applicationContext, data, "book_cover_$bookId")
                        } == true
                    }.onFailure { FeatureHooks.e("Book cover save failed", it) }.getOrDefault(false)
                    main.post {
                        if (isAttachedToWindow && buttons[activity] === this && !activity.isDestroyed) {
                            isEnabled = true
                            text = "保存"
                            Toast.makeText(activity, if (saved) "已保存到相册" else "保存失败", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                if (!accepted) {
                    isEnabled = true
                    text = "保存"
                }
            }
        }
        val params = button.layoutParams as FrameLayout.LayoutParams
        val left = sourceLocation[0] - parentLocation[0] + view.width - close.bounds.left - width
        val top = sourceLocation[1] - parentLocation[1] + close.bounds.top
        if (params.width != width || params.height != close.bounds.height() || params.leftMargin != left || params.topMargin != top) {
            params.width = width
            params.height = close.bounds.height()
            params.leftMargin = left
            params.topMargin = top
            button.layoutParams = params
        }
    }

    fun detach(activity: Activity) {
        buttons.remove(activity)?.let { (it.parent as? FrameLayout)?.removeView(it) }
    }

    fun destroyed(activity: Activity) {
        detach(activity)
        roleTargets.remove(activity)
        coverTargets.remove(activity)
        diagnostics.remove(activity)
    }
}
