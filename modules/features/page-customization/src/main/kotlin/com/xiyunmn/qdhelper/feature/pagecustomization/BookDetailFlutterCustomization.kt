package com.xiyunmn.qdhelper.feature.pagecustomization

import android.net.Uri
import com.xiyunmn.qdhelper.config.SettingsRuntime
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureResources
import com.xiyunmn.qdhelper.feature.api.runtime.HookModule
import com.xiyunmn.qdhelper.feature.api.runtime.NativeNetBridgeRuntime
import com.xiyunmn.qdhelper.foundation.runtime.HostSessionStore
import com.xiyunmn.qdhelper.host.api.gateway.NativeNetRequest
import com.xiyunmn.qdhelper.host.api.gateway.NativeNetResponseTransformer
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal object BookDetailFlutterCustomization : NativeNetResponseTransformer {
    private const val OWNER = "com.qidian.QDReader.flutter.QDBookDetailFlutterActivity"
    private const val DETAIL_PATH = "/argus/api/v3/bookdetail/get"
    private const val LOOK_FOR_PATH = "/argus/api/v3/bookdetail/lookfor"
    private const val AD_PATH = "/argus/api/v1/adv/getadvlist"
    private var registration: AutoCloseable? = null
    private val diagnostics = mutableSetOf<String>()

    init {
        FeatureResources.register("page-customization.book-detail-flutter") {
            synchronized(this) {
                registration?.close()
                registration = null
                diagnostics.clear()
            }
        }
    }

    @Synchronized
    fun install(module: HookModule, classLoader: ClassLoader, phase: String): Int {
        registration?.close()
        registration = NativeNetBridgeRuntime.register("page-custom-book-detail-flutter", 40, this)
        return NativeNetBridgeRuntime.install(module, classLoader, phase)
    }

    // The unscoped callback cannot establish the page or the requested book.
    override fun transform(path: String, response: String): String = response

    override fun transform(request: NativeNetRequest, response: String): String {
        val context = HostSessionStore.contextOrNull()
        if (!SettingsRuntime.isBookDetailPageCustomEnabled(context)) return response
        val hidden = BookDetailPageCustomization.hiddenKeys(context)
        if (hidden.isEmpty() || request.ownerClassName != OWNER) return response
        val path = Uri.parse(request.path).path ?: return response
        if (path !in setOf(DETAIL_PATH, LOOK_FOR_PATH, AD_PATH)) return response
        val params = request.parameters
        val bookId = params["bookId"]?.toLongOrNull()
        val item = when (path) {
            DETAIL_PATH -> {
                if (bookId == null || bookId <= 0L || params["cbid"] != "-1" || params["isOutBook"] != "0") {
                    trace("detail-request-scope")
                    return response
                }
                null
            }
            LOOK_FOR_PATH -> {
                if (BookDetailPageCustomization.ITEM_LOOK_FOR !in hidden) return response
                if (bookId == null || bookId <= 0L || params["isOutBook"] != "0") {
                    trace("look-for-request-scope")
                    return response
                }
                BookDetailPageCustomization.ITEM_LOOK_FOR
            }
            else -> {
                val adItem = when (params["position"]) {
                    "android_showbook_xuanfuicon" -> BookDetailPageCustomization.ITEM_ACTIVITY_FLOAT
                    "newshowbook" -> BookDetailPageCustomization.ITEM_ACTIVITY_BANNER
                    else -> return response
                }
                if (adItem !in hidden) return response
                if (bookId == null || bookId <= 0L || params["objId"]?.toLongOrNull() != bookId || params["objType"] != "1") {
                    trace("ad-request-scope")
                    return response
                }
                adItem
            }
        }
        val root = try {
            JSONObject(response)
        } catch (_: JSONException) {
            trace("invalid-json")
            return response
        }
        val result = root.opt("Result")
        if (result !is Int) {
            trace("unknown-result")
            return response
        }
        if (result != 0) return response
        if (path == AD_PATH) {
            val ads = root.opt("Data") as? JSONArray ?: run {
                trace("unknown-ad-data")
                return response
            }
            if (ads.length() == 0) return response
            root.put("Data", JSONArray())
            trace("applied-$item")
            return root.toString()
        }
        val data = root.optJSONObject("Data") ?: run {
            trace("unknown-object-data")
            return response
        }
        var changed = false
        var knownStructure = true
        fun replace(parent: JSONObject, key: String, replacement: Any, accepts: (Any) -> Boolean) {
            if (!parent.has(key) || parent.isNull(key)) return
            val value = parent.get(key)
            if (!accepts(value)) {
                knownStructure = false
                trace("unknown-field-$key")
                return
            }
            if (value == replacement || value is JSONArray && value.length() == 0 && replacement is JSONArray) return
            parent.put(key, replacement)
            changed = true
        }
        fun clearObject(parent: JSONObject, key: String) = replace(parent, key, JSONObject.NULL) { it is JSONObject }
        fun clearList(parent: JSONObject, key: String) = replace(parent, key, JSONArray()) { it is JSONArray }
        if (path == LOOK_FOR_PATH) {
            if (!data.has("BookLookForDetail")) {
                trace("unknown-look-for-data")
                return response
            }
            clearList(data, "BookLookForDetail")
        } else {
            val base = data.optJSONObject("BaseBookInfo") ?: run {
                trace("unknown-base-book-info")
                return response
            }
            if (BookDetailPageCustomization.ITEM_CIRCLE in hidden) {
                replace(data, "CircleSwitch", 0) { it is Int || it is Long }
                clearObject(data, "BookCircleScope")
            }
            if (BookDetailPageCustomization.ITEM_AUTHOR in hidden) {
                clearObject(data, "AuthorInfo")
                clearList(data, "AuthorInfos")
            }
            if (BookDetailPageCustomization.ITEM_ACTIVITY_BANNER in hidden) clearObject(data, "ActivityBanner")
            if (BookDetailPageCustomization.ITEM_SAME_RECOMMEND in hidden) clearList(data, "SameRecommend")
            if (BookDetailPageCustomization.ITEM_OTHER_READ in hidden) clearList(data, "BookFriendsRecommend")
            if (BookDetailPageCustomization.ITEM_MONTH_TICKET_SPONSOR in hidden) clearList(base, "MonthTopUser")
            if (BookDetailPageCustomization.ITEM_FANS_RANK in hidden) {
                val sponsors = base.opt("MonthTopUser")
                // A nonempty sponsor list builds a row with an unconditional fans half, even when FansInfo is null.
                when {
                    sponsors == null || sponsors == JSONObject.NULL || sponsors is JSONArray && sponsors.length() == 0 ->
                        clearObject(base, "FansInfo")
                    sponsors is JSONArray -> trace("fans-rank-needs-widget-gate")
                    else -> {
                        knownStructure = false
                        trace("unknown-field-MonthTopUser")
                    }
                }
            }
            if (BookDetailPageCustomization.ITEM_COPYRIGHT in hidden) {
                clearObject(base, "CopyRightDetail")
                listOf("DescCopyRight", "AuthCopyRight", "JoinTimeCopyRight").forEach { key ->
                    replace(base, key, "") { it is String }
                }
            }
        }
        if (!knownStructure || !changed) return response
        trace("applied-${path.substringAfterLast('/')}")
        return root.toString()
    }

    @Synchronized
    private fun trace(reason: String) {
        if (diagnostics.size < 12 && diagnostics.add(reason)) {
            FeatureHooks.i("Book detail Flutter customization: $reason")
        }
    }
}
