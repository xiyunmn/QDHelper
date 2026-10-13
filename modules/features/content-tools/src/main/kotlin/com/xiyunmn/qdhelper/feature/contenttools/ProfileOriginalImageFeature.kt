package com.xiyunmn.qdhelper.feature.contenttools

import android.content.Context
import android.view.View
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.feature.api.runtime.InstallableFeature
import com.xiyunmn.qdhelper.capability.originalimage.OriginalImageLongPressBinder
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols as QDReaderHost
import com.xiyunmn.qdhelper.shared.media.RemoteImageDownloader
import com.xiyunmn.qdhelper.config.SettingsRuntime
import com.xiyunmn.qdhelper.capability.originalimage.OriginalImageDialog
import com.xiyunmn.qdhelper.feature.api.runtime.HookModule
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

object ProfileOriginalImageFeature : InstallableFeature {
    override val name: String = "ProfileOriginalImageFeature"

    private const val AUTHOR_HEADER_TYPE = 1
    private const val USER_HEADER_TYPE = 7
    private const val AVATAR_VIEW_ID_NAME = "auth_I"
    private val observedTargetKinds = ConcurrentHashMap.newKeySet<String>()

    override fun install(
        module: HookModule,
        classLoader: ClassLoader,
        processName: String,
        phase: String,
    ) {
        if (!QDReaderHost.isMainProcess(processName)) return
        val installed = installAdapterHook(module, classLoader)
        FeatureHooks.i(
            "Profile original image phase finished: phase=$phase, process=$processName, installed=$installed",
        )
    }

    private fun installAdapterHook(module: HookModule, classLoader: ClassLoader): Boolean {
        val adapterClass = FeatureHooks.findClassOrNull(QDReaderHost.Classes.HOME_PAGE_ADAPTER, classLoader)
            ?: return false
        val itemClass = FeatureHooks.findClassOrNull(QDReaderHost.Classes.HOME_PAGE_ITEM, classLoader)
            ?: return false
        val authorClass = FeatureHooks.findClassOrNull(QDReaderHost.Classes.HOME_PAGE_AUTHOR_INFO, classLoader)
            ?: return false
        val userClass = FeatureHooks.findClassOrNull(QDReaderHost.Classes.HOME_PAGE_USER_INFO, classLoader)
            ?: return false
        val metadata = BindingMetadata.resolve(adapterClass, itemClass, authorClass, userClass)
            ?: return false
        val installed = FeatureHooks.hookAfter(
            module = module,
            executable = metadata.bindMethod,
            feature = "profile.originalImage:home page header bind",
            id = "qdhelper.profile.original_image.header_bind",
        ) { chain, _ ->
            runCatching {
                val adapter = chain.thisObject ?: return@runCatching
                val holder = chain.args.getOrNull(0) ?: return@runCatching
                val position = chain.args.getOrNull(1) as? Int ?: return@runCatching
                attachAvatar(adapter, holder, position, metadata)
            }.onFailure { throwable ->
                FeatureHooks.e("Profile original image bind failed", throwable)
            }
        }
        return installed
    }

    private fun attachAvatar(
        adapter: Any,
        holder: Any,
        position: Int,
        metadata: BindingMetadata,
    ) {
        val root = FeatureHooks.findFieldOrNull(holder.javaClass, "itemView")
            ?.let { field -> FeatureHooks.read(field, holder) as? View }
            ?: return
        val avatarId = root.resources.getIdentifier(AVATAR_VIEW_ID_NAME, "id", root.context.packageName)
        if (avatarId == 0) return
        val avatar = root.findViewById<View>(avatarId) ?: return
        if (!SettingsRuntime.isProfileAvatarOriginalEnabled(root.context)) {
            OriginalImageLongPressBinder.unregister(avatar)
            return
        }
        val item = metadata.itemGetter.invoke(adapter, position) ?: run {
            OriginalImageLongPressBinder.unregister(avatar)
            return
        }
        val type = (metadata.itemTypeGetter.invoke(item) as? Number)?.toInt()
        val target = when (type) {
            AUTHOR_HEADER_TYPE -> metadata.authorTarget(item)
            USER_HEADER_TYPE -> metadata.userTarget(item)
            else -> null
        }
        if (target == null || target.url.isBlank()) {
            OriginalImageLongPressBinder.unregister(avatar)
            return
        }
        OriginalImageLongPressBinder.register(
            view = avatar,
            isEnabled = SettingsRuntime::isProfileAvatarOriginalEnabled,
            open = { context -> openOriginalImage(context, target) },
        )
        if (observedTargetKinds.add(target.kind)) {
            FeatureHooks.i("Profile original image target registered: kind=${target.kind}, hasId=${target.id != null}")
        }
    }

    private fun openOriginalImage(context: Context, target: ProfileImageTarget) {
        OriginalImageDialog.show(
            context = context,
            title = target.displayName.ifBlank { "头像原图" },
            fileStem = "profile_avatar_${target.kind}_${target.id ?: target.displayName}",
        ) {
            RemoteImageDownloader.download(target.url)
        }
    }

    private data class ProfileImageTarget(
        val kind: String,
        val url: String,
        val displayName: String,
        val id: Long?,
    )

    private data class BindingMetadata(
        val bindMethod: Method,
        val itemGetter: Method,
        val itemTypeGetter: Method,
        val authorGetter: Method,
        val authorHeadImageGetter: Method,
        val authorNameGetter: Method,
        val authorIdGetter: Method,
        val userGetter: Method,
        val userHeadImageGetter: Method,
        val userNameGetter: Method,
        val userIdGetter: Method,
    ) {
        fun authorTarget(item: Any): ProfileImageTarget? {
            val author = authorGetter.invoke(item) ?: return null
            return ProfileImageTarget(
                kind = "author",
                url = (authorHeadImageGetter.invoke(author) as? String)?.trim().orEmpty(),
                displayName = (authorNameGetter.invoke(author) as? String)?.trim().orEmpty(),
                id = (authorIdGetter.invoke(author) as? Number)?.toLong()?.takeIf { it > 0L },
            )
        }

        fun userTarget(item: Any): ProfileImageTarget? {
            val user = userGetter.invoke(item) ?: return null
            return ProfileImageTarget(
                kind = "user",
                url = (userHeadImageGetter.invoke(user) as? String)?.trim().orEmpty(),
                displayName = (userNameGetter.invoke(user) as? String)?.trim().orEmpty(),
                id = (userIdGetter.invoke(user) as? Number)?.toLong()?.takeIf { it > 0L },
            )
        }

        companion object {
            fun resolve(
                adapterClass: Class<*>,
                itemClass: Class<*>,
                authorClass: Class<*>,
                userClass: Class<*>,
            ): BindingMetadata? = runCatching {
                val bindMethod = FeatureHooks.allDeclaredMethods(adapterClass).single { method ->
                    method.name == "onBindContentItemViewHolder" &&
                        !Modifier.isStatic(method.modifiers) &&
                        method.returnType == Void.TYPE &&
                        method.parameterTypes.size == 2 &&
                        method.parameterTypes[0].name == QDReaderHost.Classes.ANDROIDX_RECYCLER_VIEW_HOLDER &&
                        method.parameterTypes[1] == Int::class.javaPrimitiveType
                }
                val itemGetter = FeatureHooks.publicMethods(adapterClass).single { method ->
                    Modifier.isPublic(method.modifiers) &&
                        !Modifier.isStatic(method.modifiers) &&
                        method.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType)) &&
                        method.returnType == itemClass
                }
                BindingMetadata(
                    bindMethod = bindMethod,
                    itemGetter = itemGetter,
                    itemTypeGetter = publicGetter(itemClass, "getItemType"),
                    authorGetter = publicGetter(itemClass, "getAuthorInfoBean"),
                    authorHeadImageGetter = publicGetter(authorClass, "getHeadImage"),
                    authorNameGetter = publicGetter(authorClass, "getAuthorName"),
                    authorIdGetter = publicGetter(authorClass, "getAuthorId"),
                    userGetter = publicGetter(itemClass, "getUserInfoBean"),
                    userHeadImageGetter = publicGetter(userClass, "getHeadImage"),
                    userNameGetter = publicGetter(userClass, "getNickName"),
                    userIdGetter = publicGetter(userClass, "getUserId"),
                )
            }.getOrNull()

            private fun publicGetter(type: Class<*>, name: String): Method =
                FeatureHooks.publicMethodsNamed(type, name, parameterCount = 0).single()
        }
    }
}
