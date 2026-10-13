package com.xiyunmn.qdhelper.feature.contenttools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.xiyunmn.qdhelper.config.SettingsRuntime
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.feature.api.runtime.HookModule
import com.xiyunmn.qdhelper.feature.api.runtime.InstallableFeature
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols as QDReaderHost
import java.lang.reflect.Method
import java.lang.reflect.Modifier

object ProfileUserIdFeature : InstallableFeature {
    override val name: String = "ProfileUserIdFeature"

    override fun install(
        module: HookModule,
        classLoader: ClassLoader,
        processName: String,
        phase: String,
    ) {
        if (!QDReaderHost.isMainProcess(processName)) return
        val installed = installAdapterHook(module, classLoader)
        FeatureHooks.i(
            "Profile userId phase finished: phase=$phase, process=$processName, installed=$installed",
        )
    }

    private fun installAdapterHook(module: HookModule, classLoader: ClassLoader): Boolean {
        val adapterClass = FeatureHooks.findClassOrNull(QDReaderHost.Classes.HOME_PAGE_ADAPTER, classLoader)
            ?: return false
        val itemClass = FeatureHooks.findClassOrNull(QDReaderHost.Classes.HOME_PAGE_ITEM, classLoader)
            ?: return false
        val userClass = FeatureHooks.findClassOrNull(QDReaderHost.Classes.HOME_PAGE_USER_INFO, classLoader)
            ?: return false
        val metadata = BindingMetadata.resolve(adapterClass, itemClass, userClass) ?: return false
        return FeatureHooks.hookAfter(
            module = module,
            executable = metadata.bindMethod,
            feature = "profile.userId:home page header bind",
            id = "qdhelper.profile.user_id.header_bind",
        ) { chain, _ ->
            runCatching {
                val adapter = chain.thisObject ?: return@runCatching
                val holder = chain.args.getOrNull(0) ?: return@runCatching
                val position = chain.args.getOrNull(1) as? Int ?: return@runCatching
                bindUserId(adapter, holder, position, metadata)
            }.onFailure { throwable ->
                FeatureHooks.e("Profile userId bind failed", throwable)
            }
        }
    }

    private fun bindUserId(
        adapter: Any,
        holder: Any,
        position: Int,
        metadata: BindingMetadata,
    ) {
        val root = holder.javaClass.getField("itemView").get(holder) as? View ?: return
        val enabled = SettingsRuntime.isProfileUserIdEnabled(root.context)
        val item = if (enabled) {
            runCatching { metadata.itemGetter.invoke(adapter, position) }.getOrNull()
        } else {
            null
        }
        val itemType = item?.let { value ->
            runCatching { (metadata.itemTypeGetter.invoke(value) as? Number)?.toInt() }.getOrNull()
        }
        val userId = if (item != null && itemType != null) {
            runCatching { metadata.userId(item, itemType) }.getOrNull()
        } else {
            null
        }
        when (ProfileUserIdPolicy.bindAction(enabled, userId, ProfileUserIdViewBinder.hasView(root))) {
            ProfileUserIdBindAction.REMOVE -> ProfileUserIdViewBinder.remove(root)
            ProfileUserIdBindAction.INSERT,
            ProfileUserIdBindAction.UPDATE,
            -> ProfileUserIdViewBinder.bind(root, requireNotNull(userId))
        }
    }

    private data class BindingMetadata(
        val bindMethod: Method,
        val itemGetter: Method,
        val itemTypeGetter: Method,
        val itemUserIdGetter: Method,
        val userGetter: Method,
        val userIdGetter: Method,
    ) {
        fun userId(item: Any, itemType: Int): Long? {
            val itemUserId = (itemUserIdGetter.invoke(item) as? Number)?.toLong()
            val readerUserId = if (itemType == ProfileUserIdPolicy.USER_HEADER_TYPE) {
                userGetter.invoke(item)?.let { user ->
                    (userIdGetter.invoke(user) as? Number)?.toLong()
                }
            } else {
                null
            }
            return ProfileUserIdPolicy.resolveUserId(itemType, itemUserId, readerUserId)
        }

        companion object {
            fun resolve(
                adapterClass: Class<*>,
                itemClass: Class<*>,
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
                val itemGetter = adapterClass.methods.single { method ->
                    Modifier.isPublic(method.modifiers) &&
                        !Modifier.isStatic(method.modifiers) &&
                        method.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType)) &&
                        method.returnType == itemClass
                }
                BindingMetadata(
                    bindMethod = bindMethod,
                    itemGetter = itemGetter,
                    itemTypeGetter = itemClass.getMethod("getItemType"),
                    itemUserIdGetter = itemClass.getMethod("getUserId"),
                    userGetter = itemClass.getMethod("getUserInfoBean"),
                    userIdGetter = userClass.getMethod("getUserId"),
                )
            }.getOrNull()
        }
    }
}

internal enum class ProfileUserIdBindAction {
    INSERT,
    UPDATE,
    REMOVE,
}

internal object ProfileUserIdPolicy {
    const val AUTHOR_HEADER_TYPE = 1
    const val USER_HEADER_TYPE = 7

    fun resolveUserId(itemType: Int, itemUserId: Long?, readerUserId: Long?): Long? = when (itemType) {
        AUTHOR_HEADER_TYPE -> itemUserId.validUserId()
        USER_HEADER_TYPE -> readerUserId.validUserId() ?: itemUserId.validUserId()
        else -> null
    }

    fun bindAction(enabled: Boolean, userId: Long?, hasExistingView: Boolean): ProfileUserIdBindAction {
        if (!enabled || userId.validUserId() == null) return ProfileUserIdBindAction.REMOVE
        return if (hasExistingView) ProfileUserIdBindAction.UPDATE else ProfileUserIdBindAction.INSERT
    }

    fun displayText(userId: Long): String = "userId：$userId"

    fun clipboardText(userId: Long): String = userId.toString()

    private fun Long?.validUserId(): Long? = this?.takeIf { it > 0L }
}

private object ProfileUserIdViewBinder {
    private const val USER_ID_VIEW_TAG = "qdhelper.profile.user_id.view"
    private const val DESCRIPTION_ID_NAME = "auth_c"
    private const val BOTTOM_MARGIN_DP = 4

    private data class WrapperState(
        val description: TextView,
        val originalDescriptionId: Int,
    )

    fun hasView(root: View): Boolean = root.findTaggedUserIdView() != null

    fun bind(root: View, userId: Long) {
        val existing = root.findTaggedUserIdView()
        if (existing != null) {
            val reference = (existing.parent as? View)?.tag
                .let { it as? WrapperState }
                ?.description
                ?: root.findDescription()
                ?: existing
            style(existing, reference, userId)
            return
        }

        val description = root.findDescription() ?: return
        val parent = description.parent as? ViewGroup ?: return
        val insertionIndex = parent.indexOfChild(description).takeIf { it >= 0 } ?: return
        val userIdView = TextView(root.context).apply { tag = USER_ID_VIEW_TAG }
        if (parent is LinearLayout) {
            parent.addView(
                userIdView,
                insertionIndex,
                directLayoutParams(description, root.dp(BOTTOM_MARGIN_DP)),
            )
        } else {
            wrapDescription(parent, description, userIdView, insertionIndex, root.dp(BOTTOM_MARGIN_DP))
        }
        style(userIdView, description, userId)
    }

    fun remove(root: View) {
        while (true) {
            val userIdView = root.findTaggedUserIdView() ?: break
            val parent = userIdView.parent as? ViewGroup ?: break
            val state = parent.tag as? WrapperState
            if (parent is LinearLayout && state != null) {
                unwrapDescription(parent, state)
            } else {
                parent.removeView(userIdView)
            }
        }
        root.findProfileUserIdWrapper()?.let { wrapper ->
            val state = wrapper.tag as? WrapperState ?: return@let
            unwrapDescription(wrapper, state)
        }
    }

    private fun style(view: TextView, reference: TextView, userId: Long) {
        val displayText = ProfileUserIdPolicy.displayText(userId)
        view.text = displayText
        view.contentDescription = "$displayText，长按复制"
        view.setTextColor(reference.currentTextColor)
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, reference.textSize)
        view.typeface = reference.typeface
        view.includeFontPadding = reference.includeFontPadding
        view.gravity = Gravity.CENTER
        view.maxLines = 1
        view.setPadding(reference.paddingLeft, 0, reference.paddingRight, 0)
        view.visibility = View.VISIBLE
        view.setOnLongClickListener {
            copyUserId(view.context, userId)
            true
        }
    }

    private fun directLayoutParams(description: TextView, bottomMargin: Int): LinearLayout.LayoutParams {
        val source = description.layoutParams as? ViewGroup.MarginLayoutParams
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            if (source != null) {
                leftMargin = source.leftMargin
                rightMargin = source.rightMargin
                marginStart = source.marginStart
                marginEnd = source.marginEnd
            }
            this.bottomMargin = bottomMargin
        }
    }

    private fun wrapDescription(
        parent: ViewGroup,
        description: TextView,
        userIdView: TextView,
        insertionIndex: Int,
        bottomMargin: Int,
    ) {
        val originalParams = description.layoutParams ?: return
        val originalId = description.id
        val state = WrapperState(description, originalId)
        val wrapper = LinearLayout(description.context).apply {
            orientation = LinearLayout.VERTICAL
            id = originalId
            tag = state
        }
        parent.removeViewAt(insertionIndex)
        if (originalId != View.NO_ID) description.id = View.generateViewId()
        wrapper.addView(
            userIdView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { this.bottomMargin = bottomMargin },
        )
        wrapper.addView(
            description,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        parent.addView(wrapper, insertionIndex, originalParams)
    }

    private fun unwrapDescription(wrapper: LinearLayout, state: WrapperState) {
        val parent = wrapper.parent as? ViewGroup ?: return
        val index = parent.indexOfChild(wrapper).takeIf { it >= 0 } ?: return
        val originalParams = wrapper.layoutParams ?: return
        wrapper.removeView(state.description)
        parent.removeViewAt(index)
        state.description.id = state.originalDescriptionId
        parent.addView(state.description, index, originalParams)
    }

    private fun copyUserId(context: Context, userId: Long) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard == null) {
            Toast.makeText(context, "复制失败", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            clipboard.setPrimaryClip(
                ClipData.newPlainText("userId", ProfileUserIdPolicy.clipboardText(userId)),
            )
        }.onSuccess {
            Toast.makeText(context, "已复制 userId", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(context, "复制失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun View.findDescription(): TextView? {
        val id = resources.getIdentifier(DESCRIPTION_ID_NAME, "id", context.packageName)
        if (id == 0) return null
        return findViewById<View>(id) as? TextView
    }

    private fun View.findTaggedUserIdView(): TextView? {
        if (this is TextView && tag == USER_ID_VIEW_TAG) return this
        if (this !is ViewGroup) return null
        for (index in 0 until childCount) {
            getChildAt(index).findTaggedUserIdView()?.let { return it }
        }
        return null
    }

    private fun View.findProfileUserIdWrapper(): LinearLayout? {
        if (this is LinearLayout && tag is WrapperState) return this
        if (this !is ViewGroup) return null
        for (index in 0 until childCount) {
            getChildAt(index).findProfileUserIdWrapper()?.let { return it }
        }
        return null
    }

    private fun View.dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()
}
