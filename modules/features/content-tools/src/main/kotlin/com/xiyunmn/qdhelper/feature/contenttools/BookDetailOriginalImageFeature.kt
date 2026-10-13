package com.xiyunmn.qdhelper.feature.contenttools

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.widget.Toast
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.feature.api.runtime.ActivityLifecycleCallbacks
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureResources
import com.xiyunmn.qdhelper.feature.api.runtime.InstallableFeature
import com.xiyunmn.qdhelper.capability.originalimage.OriginalImageLongPressBinder
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols as QDReaderHost
import com.xiyunmn.qdhelper.shared.media.OriginalImageData
import com.xiyunmn.qdhelper.shared.media.RemoteImageDownloader
import com.xiyunmn.qdhelper.feature.api.runtime.BookCoverGateways
import com.xiyunmn.qdhelper.config.SettingsRuntime
import com.xiyunmn.qdhelper.capability.originalimage.OriginalImageDialog
import com.xiyunmn.qdhelper.feature.api.runtime.HookModule
import com.xiyunmn.qdhelper.foundation.identity.OwnerId
import java.lang.reflect.Field
import java.lang.reflect.Modifier

object BookDetailOriginalImageFeature : InstallableFeature {
    override val name: String = "BookDetailOriginalImageFeature"

    @Volatile
    private var coverSubscription: AutoCloseable? = null

    init {
        FeatureResources.register("book-detail-original-image") {
            coverSubscription?.close()
            coverSubscription = null
        }
    }

    override fun install(
        module: HookModule,
        classLoader: ClassLoader,
        processName: String,
        phase: String,
    ) {
        if (!QDReaderHost.isMainProcess(processName)) return
        val coverInstalled = installCoverSubscription()
        val roleInstalled = installRoleHook(module, classLoader)
        val roleListPageInstalled = installRoleListPageHook(module, classLoader)
        val roleListInstalled = installRoleListHook(module, classLoader)
        FeatureHooks.i(
            "Book detail original image phase finished: phase=$phase, process=$processName, " +
                "cover=$coverInstalled, role=$roleInstalled, roleListPage=$roleListPageInstalled, " +
                "roleList=$roleListInstalled",
        )
    }

    private fun installCoverSubscription(): Boolean {
        coverSubscription?.let { return true }
        val registration = ActivityLifecycleCallbacks.subscribe(
            owner = OwnerId("content-tools"),
            id = "qdhelper.book_detail.original_image.cover_resume",
            resumed = { activity ->
                if (activity.javaClass.name == QDReaderHost.Classes.BOOK_DETAIL_ACTIVITY) {
                    activity.window?.decorView?.post { attachCover(activity) }
                }
            },
        ) ?: return false
        coverSubscription = registration
        return true
    }

    private fun installRoleHook(module: HookModule, classLoader: ClassLoader): Boolean {
        val adapterClass = FeatureHooks.findClassOrNull(QDReaderHost.Classes.BOOK_ROLE_ADAPTER, classLoader)
            ?: return false
        val roleClass = FeatureHooks.findClassOrNull(QDReaderHost.Classes.ROLE_ITEM, classLoader)
            ?: return false
        val bindMethod = FeatureHooks.allDeclaredMethods(adapterClass).singleOrNull { method ->
            Modifier.isPublic(method.modifiers) &&
                !Modifier.isStatic(method.modifiers) &&
                method.returnType == Void.TYPE &&
                method.parameterTypes.size == 3 &&
                method.parameterTypes[0].name == QDReaderHost.Classes.RECYCLER_HOLDER &&
                method.parameterTypes[1] == Int::class.javaPrimitiveType &&
                method.parameterTypes[2] == roleClass
        } ?: return false
        return FeatureHooks.hookAfter(
            module = module,
            executable = bindMethod,
            feature = "bookDetail.originalImage:role card bind",
            id = "qdhelper.book_detail.original_image.role_bind",
        ) { chain, _ ->
            val holder = chain.args.getOrNull(0) ?: return@hookAfter
            val role = chain.args.getOrNull(2)?.takeIf(roleClass::isInstance) ?: return@hookAfter
            attachRoleAvatar(holder, role)
        }
    }

    private fun installRoleListHook(module: HookModule, classLoader: ClassLoader): Boolean {
        val adapterClass = FeatureHooks.findClassOrNull(QDReaderHost.Classes.BOOK_ROLE_LIST_ADAPTER, classLoader)
            ?: return false
        val roleClass = FeatureHooks.findClassOrNull(QDReaderHost.Classes.ROLE_ITEM, classLoader)
            ?: return false
        val bindMethod = FeatureHooks.allDeclaredMethods(adapterClass).singleOrNull { method ->
            Modifier.isPublic(method.modifiers) &&
                !Modifier.isStatic(method.modifiers) &&
                method.name == "onBindViewHolder" &&
                method.returnType == Void.TYPE &&
                method.parameterTypes.size == 2 &&
                method.parameterTypes[0].name == QDReaderHost.Classes.ANDROIDX_RECYCLER_VIEW_HOLDER &&
                method.parameterTypes[1] == Int::class.javaPrimitiveType
        } ?: return false
        return FeatureHooks.hookAfter(
            module = module,
            executable = bindMethod,
            feature = "bookDetail.originalImage:role list bind",
            id = "qdhelper.book_detail.original_image.role_list_bind",
        ) { chain, _ ->
            runCatching {
                val adapter = chain.thisObject ?: return@runCatching
                val holder = chain.args.getOrNull(0) ?: return@runCatching
                val position = chain.args.getOrNull(1) as? Int ?: return@runCatching
                val role = roleAt(adapter, position, roleClass) ?: return@runCatching
                val itemView = holder.itemViewOrNull() ?: return@runCatching
                val avatar = itemView.findNamedView("ivHead") ?: return@runCatching
                attachRoleAvatarView(avatar, role)
            }.onFailure { throwable ->
                FeatureHooks.e("Book role list original image bind failed", throwable)
            }
        }
    }

    private fun installRoleListPageHook(module: HookModule, classLoader: ClassLoader): Boolean {
        val adapterClass = FeatureHooks.findClassOrNull(QDReaderHost.Classes.BOOK_ROLE_LIST_PAGE_ADAPTER, classLoader)
            ?: return false
        val roleClass = FeatureHooks.findClassOrNull(QDReaderHost.Classes.ROLE_ITEM, classLoader)
            ?: return false
        val bindMethod = FeatureHooks.allDeclaredMethods(adapterClass).singleOrNull { method ->
            Modifier.isPublic(method.modifiers) &&
                !Modifier.isStatic(method.modifiers) &&
                method.name == "convert" &&
                method.returnType == Void.TYPE &&
                method.parameterTypes.size == 3 &&
                method.parameterTypes[0].name == QDReaderHost.Classes.RECYCLER_HOLDER &&
                method.parameterTypes[1] == Int::class.javaPrimitiveType &&
                method.parameterTypes[2] == roleClass
        } ?: return false
        return FeatureHooks.hookAfter(
            module = module,
            executable = bindMethod,
            feature = "bookDetail.originalImage:more roles bind",
            id = "qdhelper.book_detail.original_image.more_roles_bind",
        ) { chain, _ ->
            runCatching {
                val holder = chain.args.getOrNull(0) ?: return@runCatching
                val role = chain.args.getOrNull(2)?.takeIf(roleClass::isInstance) ?: return@runCatching
                val itemView = holder.itemViewOrNull() ?: return@runCatching
                val avatar = itemView.findNamedView("ivRoleHead") ?: itemView
                attachRoleAvatarView(avatar, role)
            }.onFailure { throwable ->
                FeatureHooks.e("More roles original image bind failed", throwable)
            }
        }
    }

    private fun attachCover(activity: Activity) {
        val id = activity.resources.getIdentifier("ivBookCover", "id", activity.packageName)
        if (id == 0) return
        val view = activity.findViewById<View>(id) ?: return
        if (SettingsRuntime.isBookDetailCoverOriginalEnabled(activity)) {
            registerTarget(view, ImageTarget.Cover)
        } else {
            OriginalImageLongPressBinder.unregister(view)
        }
    }

    private fun attachRoleAvatar(holder: Any, role: Any) {
        val cardView = holder.itemViewOrNull() ?: return
        attachRoleAvatarView(cardView, role)
    }

    private fun attachRoleAvatarView(view: View, role: Any) {
        if (!SettingsRuntime.isBookDetailRoleAvatarOriginalEnabled(view.context)) {
            OriginalImageLongPressBinder.unregister(view)
            return
        }
        val originalUrl = role.stringGetter("getRoleHeadImage")
            ?: role.stringGetter("getRoleHeadIcon")
            ?: role.stringGetter("getRoleIcon")
        if (originalUrl.isNullOrBlank()) {
            OriginalImageLongPressBinder.unregister(view)
            return
        }
        registerTarget(
            view,
            ImageTarget.Role(
                url = originalUrl,
                roleName = role.stringGetter("getRoleName").orEmpty(),
                roleId = role.longGetter("getRoleId"),
            ),
        )
    }

    private fun roleAt(adapter: Any, position: Int, roleClass: Class<*>): Any? {
        if (position < 0) return null
        return allFields(adapter.javaClass)
            .filter { field -> List::class.java.isAssignableFrom(field.type) }
            .mapNotNull { field ->
                val list = readField(field, adapter) as? List<*> ?: return@mapNotNull null
                list.getOrNull(position)?.takeIf(roleClass::isInstance)
            }
            .firstOrNull()
    }

    private fun Any?.itemViewOrNull(): View? {
        val holder = this ?: return null
        return allFields(holder.javaClass)
            .firstOrNull { field -> field.name == "itemView" && View::class.java.isAssignableFrom(field.type) }
            ?.let { readField(it, holder) as? View }
    }

    private fun View.findNamedView(name: String): View? {
        val id = resources.getIdentifier(name, "id", context.packageName)
        return id.takeIf { it != 0 }?.let(::findViewById)
    }

    private fun openOriginalImage(context: Context, target: ImageTarget) {
        when (target) {
            ImageTarget.Cover -> {
                val activity = context.unwrapActivity()
                val bookId = activity?.currentBookId()
                if (bookId == null) {
                    Toast.makeText(context, "未读取到书籍信息", Toast.LENGTH_SHORT).show()
                    return
                }
                OriginalImageDialog.show(
                    context = context,
                    title = "书籍封面",
                    fileStem = "book_cover_$bookId",
                ) {
                    val gateway = BookCoverGateways.forClassLoader(context.classLoader)
                    try {
                        gateway.loadCover(bookId.toString())?.let { cover ->
                            OriginalImageData(cover.bytes, cover.mediaType, cover.extension)
                        }
                    } finally {
                        (gateway as? AutoCloseable)?.close()
                    }
                }
            }
            is ImageTarget.Role -> {
                OriginalImageDialog.show(
                    context = context,
                    title = target.roleName.ifBlank { "角色头像" },
                    fileStem = "role_avatar_${target.roleId ?: target.roleName}",
                ) {
                    RemoteImageDownloader.download(target.url)
                }
            }
        }
    }

    private fun registerTarget(view: View, target: ImageTarget) {
        OriginalImageLongPressBinder.register(
            view = view,
            isEnabled = target::isEnabled,
            open = { context -> openOriginalImage(context, target) },
        )
    }

    private fun Activity.currentBookId(): Long? {
        val modelId = runCatching {
            val detail = allFields(javaClass)
                .firstOrNull { it.type.name == QDReaderHost.Classes.BOOK_DETAIL }
                ?.let { readField(it, this) }
            val baseInfo = detail?.let { value ->
                FeatureHooks.publicMethodsNamed(value.javaClass, "getBaseBookInfo", 0)
                    .singleOrNull { method -> method.returnType.name == QDReaderHost.Classes.BASE_BOOK_INFO }
                    ?.let { method -> FeatureHooks.invokeOrNull(method, value) }
            }
            baseInfo?.let { value ->
                FeatureHooks.publicMethodsNamed(value.javaClass, "getBookId", 0)
                    .singleOrNull { method -> method.returnType == Long::class.javaPrimitiveType }
                    ?.let { method -> FeatureHooks.invokeOrNull(method, value) }
            } as? Number
        }.getOrNull()
        return modelId?.toLong()?.takeIf { it > 0L }
            ?: intent?.getLongExtra("BOOK_ID", 0L)?.takeIf { it > 0L }
    }

    private fun Any.stringGetter(name: String): String? {
        val method = FeatureHooks.publicMethodsNamed(javaClass, name, 0)
            .singleOrNull { candidate -> candidate.returnType == String::class.java }
        return (FeatureHooks.invokeOrNull(method, this) as? String)
            ?.trim()?.takeIf(String::isNotEmpty)
    }

    private fun Any.longGetter(name: String): Long? {
        val method = FeatureHooks.publicMethodsNamed(javaClass, name, 0)
            .singleOrNull { candidate -> candidate.returnType == Long::class.javaPrimitiveType }
        return (FeatureHooks.invokeOrNull(method, this) as? Number)
            ?.toLong()?.takeIf { it > 0L }
    }

    private fun Context.unwrapActivity(): Activity? {
        var current: Context? = this
        repeat(12) {
            when (current) {
                is Activity -> return current
                is ContextWrapper -> current = current.baseContext
                else -> return null
            }
        }
        return null
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

    private sealed class ImageTarget {
        abstract fun isEnabled(context: Context): Boolean

        data object Cover : ImageTarget() {
            override fun isEnabled(context: Context): Boolean {
                return SettingsRuntime.isBookDetailCoverOriginalEnabled(context)
            }
        }

        data class Role(
            val url: String,
            val roleName: String,
            val roleId: Long?,
        ) : ImageTarget() {
            override fun isEnabled(context: Context): Boolean {
                return SettingsRuntime.isBookDetailRoleAvatarOriginalEnabled(context)
            }
        }
    }
}
