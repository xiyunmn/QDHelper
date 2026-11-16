package com.xiyunmn.qdhelper.feature.contenttools

import android.app.Activity
import com.xiyunmn.qdhelper.capability.freecopy.FreeCopyDialog
import com.xiyunmn.qdhelper.config.ConfigRuntime
import com.xiyunmn.qdhelper.config.SettingIds
import com.xiyunmn.qdhelper.config.SettingsRuntime
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureResources
import com.xiyunmn.qdhelper.feature.api.runtime.FlutterSemanticsSupport
import com.xiyunmn.qdhelper.feature.api.runtime.HookModule
import com.xiyunmn.qdhelper.feature.api.runtime.NativeNetBridgeRuntime
import com.xiyunmn.qdhelper.foundation.runtime.HostSessionStore
import com.xiyunmn.qdhelper.host.api.gateway.NativeNetRequest
import com.xiyunmn.qdhelper.host.api.gateway.NativeNetResponseTransformer
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Network text supplies complete content; semantics only identifies the text being held. */
internal object FlutterCircleFreeCopy {
    private const val ACTIVITY = "com.qidian.QDReader.flutter.QDCircleFlutterActivity"
    private val routes = setOf("circleDetailPage", "circlePostDetailPage", "circleCommentDetailPage")
    private val paths = setOf(
        "/argus/api/v1/circle/getcirclepostlist",
        "/argus/api/v1/circle/getpostdetail",
        "/argus/api/v1/circle/getreviewdetail",
    )
    private data class Content(val styled: CharSequence, val labels: Set<String>)
    private val contents = linkedMapOf<String, Content>()
    private val whitespace = Regex("\\s+")
    private val inlineEmoji = Regex("\\[fn=(\\d+)\\]")
    private var installed = false
    private var reportedData = false

    fun install(module: HookModule, classLoader: ClassLoader, phase: String) {
        if (installed || FeatureHooks.findClassOrNull(ACTIVITY, classLoader) == null) return
        installed = true
        val responses = NativeNetBridgeRuntime.register("circle-copy-content", 100, object : NativeNetResponseTransformer {
            override fun transform(path: String, response: String): String = response
            override fun transform(request: NativeNetRequest, response: String): String {
                if (request.ownerClassName != ACTIVITY || request.path.substringBefore('?') !in paths ||
                    !SettingsRuntime.isCircleFreeCopyEnabled(HostSessionStore.contextOrNull())) return response
                try {
                    val root = JSONObject(response)
                    if (root.optInt("Result", 0) != 0) return response
                    val data = root.optJSONObject("Data") ?: root
                    synchronized(contents) {
                        cache(data.optJSONObject("TopicData"), "post")
                        cache(data.optJSONObject("ReviewData"), "review")
                        cacheList(data.optJSONArray("TopicDataList"), "post")
                        cacheList(data.optJSONArray("ReviewDataList"), "review")
                        cacheList(data.optJSONArray("AuthorReviewDataList"), "review")
                        cacheList(data.optJSONArray("ReplyDataList"), "reply")
                        if (!reportedData && contents.isNotEmpty()) {
                            reportedData = true
                            FeatureHooks.i("Flutter circle copy content ready: items=${contents.size}")
                        }
                    }
                } catch (_: JSONException) {
                    FeatureHooks.w("Flutter circle copy content skipped: invalid JSON")
                }
                return response
            }
        })
        NativeNetBridgeRuntime.install(module, classLoader, phase)
        FlutterSemanticsSupport.register(
            "circle-free-copy",
            enabled = ::enabled,
            longPress = { _, view, node ->
                // Flutter marks rich text containing inline images as an image too.
                // Numeric image labels are the separate reply/like counters, not body text.
                val counter = node.image && node.label.trim().all(Char::isDigit)
                val text = if (counter || node.flags and ((1 shl 3) or (1 shl 4)) != 0) null
                    else contentFor(node.label)
                if (text == null) null else {
                    { FreeCopyDialog.show(view, text, "书友圈内容"); Unit }
                }
            },
        )
        FlutterSemanticsSupport.install(module, classLoader, phase)
        val settings = HostSessionStore.contextOrNull()?.let { context ->
            ConfigRuntime.observeUserChanges(context,
                setOf(SettingIds.KEY_MODULE_ENABLED, SettingIds.KEY_CIRCLE_FREE_COPY_ENABLED)) {
                if (!SettingsRuntime.isCircleFreeCopyEnabled(context)) synchronized(contents) { contents.clear() }
                FlutterSemanticsSupport.refreshCurrentActivities()
            }
        }
        FeatureResources.register("flutter-circle-copy") {
            responses.close()
            settings?.close()
            synchronized(contents) { contents.clear() }
            installed = false
            reportedData = false
        }
        FeatureHooks.i("Flutter circle copy installed: phase=$phase")
    }

    private fun enabled(activity: Activity): Boolean = activity.javaClass.name == ACTIVITY &&
        activity.intent?.getStringExtra("RoutePath") in routes && SettingsRuntime.isCircleFreeCopyEnabled(activity)

    private fun cacheList(list: JSONArray?, kind: String) {
        if (list == null) return
        for (index in 0 until list.length()) cache(list.optJSONObject(index), kind)
    }

    private fun cache(data: JSONObject?, kind: String) {
        if (data == null) return
        cacheList(data.optJSONObject("Replys")?.optJSONArray("Items"), "reply")
        val idValue = data.opt("Id").takeUnless { it == null || it == JSONObject.NULL } ?: data.opt("ReplyId")
        val id = idValue?.toString()?.toLongOrNull()?.takeIf { it > 0 } ?: return
        val subject = CircleRichTextFilter.toPlainText(data.opt("Subject") as? String)
        val rawBody = data.opt("Body") as? String ?: ""
        val body = CircleRichTextFilter.toPlainText(rawBody)
        val plain = composeCircleCopyContent(subject, body)
        val key = "$kind:$id"
        contents.remove(key)
        if (plain.isBlank()) return
        val labels = buildSet {
            add(normalize(subject))
            add(normalize(body))
            add(normalize(plain))
            body.lineSequence().forEach { add(normalize(it)) }
            // Rich-text paragraphs can be separate semantics nodes around inline images.
            val parts = if (rawBody.trimStart().startsWith("[")) {
                try { JSONArray(rawBody) } catch (_: JSONException) { null }
            } else null
            if (parts != null) {
                for (index in 0 until parts.length()) {
                    val part = parts.optJSONObject(index) ?: continue
                    if (part.optInt("Type", -1) == 1) add(normalize(CircleRichTextFilter.toPlainText(part.optString("Text"))))
                }
            }
            remove("")
            val parentName = data.opt("ParentNickName") as? String
            if (!parentName.isNullOrBlank()) {
                val prefix = normalize("回复 $parentName：")
                toList().forEach { add(prefix + it) }
            }
        }
        contents[key] = Content(CircleFreeCopyFeature.StyledCopyContent(subject, body).styledText(), labels)
        while (contents.size > 128) contents.remove(contents.keys.first())
    }

    private fun contentFor(label: String): CharSequence? {
        val text = normalize(label)
        if (text.isEmpty()) return null
        // Cards merge author, body and timestamp into separate lines of one semantics node.
        val lines = label.lineSequence().map(::normalize).filter(String::isNotEmpty).toSet() + text
        return synchronized(contents) {
            val matches = contents.values.mapNotNull { content ->
                val length = content.labels.filter { it in lines }.maxOfOrNull(String::length) ?: return@mapNotNull null
                content to length
            }
            // Prefer the complete body over a shared short title; tied different bodies remain ambiguous.
            val longest = matches.maxOfOrNull { it.second } ?: return@synchronized null
            matches.filter { it.second == longest }.map { it.first.styled }.distinctBy { it.toString() }.singleOrNull()
        }
    }

    // Inline emoji are image spans with no spoken label; retain their codes in copied content.
    private fun normalize(text: String): String = text.replace(inlineEmoji) { match ->
        if (match.groupValues[1].toIntOrNull() in 1..64) "" else match.value
    }
        .replace("\uFFFC", "").replace(whitespace, "").trim()
}
