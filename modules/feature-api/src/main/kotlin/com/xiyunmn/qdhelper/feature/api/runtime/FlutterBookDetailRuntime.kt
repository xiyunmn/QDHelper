package com.xiyunmn.qdhelper.feature.api.runtime

import android.app.Activity
import android.view.View
import com.xiyunmn.qdhelper.foundation.runtime.HostSessionStore
import com.xiyunmn.qdhelper.host.api.gateway.NativeNetRequest
import com.xiyunmn.qdhelper.host.api.gateway.NativeNetResponseTransformer
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

object FlutterBookDetailRuntime {
    const val ACTIVITY = "com.qidian.QDReader.flutter.QDBookDetailFlutterActivity"
    data class Role(val id: Long, val name: String, val imageUrl: String)
    data class Book(
        val id: Long,
        val title: String,
        val author: String,
        val description: String,
        val roles: List<Role>,
    )

    fun coverPreviewClose(view: View, nodes: List<FlutterSemanticsSupport.Node>): FlutterSemanticsSupport.Node? {
        if (nodes.size > 16) return null
        if (nodes.none { it.image && it.bounds.width() > view.width / 2 && it.bounds.height() > view.height / 3 }) return null
        return nodes.singleOrNull {
            it.clickable && it.bounds.width() < view.width / 4 && it.bounds.height() < view.height / 5 &&
                it.bounds.centerX() < view.width / 4 && it.bounds.centerY() < view.height / 4
        }
    }

    private val books = linkedMapOf<Long, Book>()
    private val consumers = java.util.concurrent.ConcurrentHashMap<String, (android.content.Context?) -> Boolean>()
    private var cleanupRegistered = false
    private var hostDataInstalled = false

    fun install(module: HookModule, classLoader: ClassLoader, phase: String, owner: String, enabled: (android.content.Context?) -> Boolean) {
        consumers[owner] = enabled
        NativeNetBridgeRuntime.register("flutter-book-detail-metadata", priority = 100, object : NativeNetResponseTransformer {
            override fun transform(path: String, response: String): String = response
            override fun transform(request: NativeNetRequest, response: String): String {
                if (request.ownerClassName != ACTIVITY || request.path.substringBefore('?') != "/argus/api/v3/bookdetail/get" ||
                    consumers.values.none { it(HostSessionStore.contextOrNull()) }) return response
                try {
                    val root = JSONObject(response)
                    if (root.optInt("Result", -1) != 0) return response
                    val data = root.optJSONObject("Data") ?: return response
                    cache(data, request.parameters["bookId"]?.toLongOrNull(), replace = true, source = "network")
                } catch (_: JSONException) {
                    FeatureHooks.w("Flutter book detail metadata skipped: invalid JSON")
                }
                return response
            }
        })
        NativeNetBridgeRuntime.install(module, classLoader, phase)
        if (!hostDataInstalled) {
            val activityClass = FeatureHooks.findClassOrNull(ACTIVITY, classLoader)
            val received = activityClass?.let { FeatureHooks.findDeclaredMethodOrNull(it,
                "addBookToBrowserHistory", Long::class.javaPrimitiveType!!, Map::class.java) }
            hostDataInstalled = FeatureHooks.hookAfter(module, received, "flutter.detail.host-data", "qdhelper.flutter.detail.host_data") { chain, _ ->
                val activity = chain.thisObject as? Activity ?: return@hookAfter
                if (consumers.values.none { it(activity) }) return@hookAfter
                val id = (chain.args.getOrNull(0) as? Number)?.toLong() ?: return@hookAfter
                if (id <= 0 || bookId(activity) != id) return@hookAfter
                val data = chain.args.getOrNull(1) as? Map<*, *> ?: return@hookAfter
                cache(JSONObject(data), id, replace = false, source = "host-data")
            }
        }
        if (!cleanupRegistered) {
            cleanupRegistered = true
            FeatureResources.register("flutter-book-detail-metadata") {
                synchronized(books) { books.clear() }
                consumers.clear()
                cleanupRegistered = false
                hostDataInstalled = false
            }
        }
    }

    private fun decode(data: JSONObject): Book? {
        val base = data.optJSONObject("BaseBookInfo") ?: return null
        val id = base.optLong("BookId", 0).takeIf { it > 0 } ?: return null
        val roleList = data.optJSONObject("RoleInfo")?.optJSONArray("RoleList")
        val roles = buildList {
            if (roleList != null) for (i in 0 until roleList.length()) {
                val role = roleList.optJSONObject(i) ?: continue
                val url = role.optString("RoleHeadIcon").takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: continue
                add(Role(role.optLong("RoleId"), role.optString("RoleName"), url))
            }
        }
        return Book(id, base.optString("BookName"), data.optJSONObject("AuthorInfo")?.optString("Author").orEmpty(), base.optString("Description"), roles)
    }

    private fun cache(data: JSONObject, requested: Long?, replace: Boolean, source: String) {
        val book = decode(data) ?: return
        if (requested != null && requested != book.id) return
        synchronized(books) {
            if ((!replace && books.containsKey(book.id)) || books[book.id] == book) return
            books.remove(book.id)
            books[book.id] = book
            if (books.size > 8) books.remove(books.keys.first())
        }
        FeatureHooks.i("Flutter book detail metadata cached: source=$source, roles=${book.roles.size}, description=${book.description.isNotBlank()}")
        FlutterSemanticsSupport.refreshCurrentActivities()
    }

    /** Only plain JSON crosses module classloaders; this cache is never written to disk. */
    fun saveState(): String = synchronized(books) {
        JSONArray(books.values.map { book ->
            JSONObject()
                .put("BaseBookInfo", JSONObject().put("BookId", book.id).put("BookName", book.title).put("Description", book.description))
                .put("AuthorInfo", JSONObject().put("Author", book.author))
                .put("RoleInfo", JSONObject().put("RoleList", JSONArray(book.roles.map { role ->
                    JSONObject().put("RoleId", role.id).put("RoleName", role.name).put("RoleHeadIcon", role.imageUrl)
                })))
        }).toString()
    }

    fun restoreState(state: String?) {
        if (state.isNullOrBlank()) return
        try {
            val saved = JSONArray(state)
            val restored = (0 until minOf(saved.length(), 8)).mapNotNull { index -> saved.optJSONObject(index)?.let(::decode) }
            synchronized(books) { restored.forEach { books[it.id] = it } }
            FeatureHooks.i("Flutter book detail metadata restored: books=${restored.size}")
        } catch (_: JSONException) {
            FeatureHooks.w("Flutter book detail metadata restore skipped: invalid JSON")
        }
    }

    fun bookId(activity: Activity): Long? {
        if (activity.javaClass.name != ACTIVITY) return null
        return (FeatureHooks.invokeNoArgOrNull(activity, "getMBookId") as? Number)?.toLong()?.takeIf { it > 0 }
    }

    fun current(activity: Activity): Book? = bookId(activity)?.let { id -> synchronized(books) { books[id] } }
}
