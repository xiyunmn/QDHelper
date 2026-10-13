package com.xiyunmn.qdhelper.feature.readerfont.ui

import android.view.View
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.shared.fonts.FontLibraryStore
import com.xiyunmn.qdhelper.foundation.runtime.HostSessionStore
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols as QDReaderHost
import com.xiyunmn.qdhelper.host.api.target.HostTargetResolver
import com.xiyunmn.qdhelper.host.api.target.ReaderFontTargetKeys
import com.xiyunmn.qdhelper.host.api.reflection.HostAccess
import java.lang.reflect.Modifier

internal object CustomReaderFontHostBridge {
    private const val FONT_ITEM_CLASS = QDReaderHost.Classes.READER_FONT_ITEM
    private const val CUSTOM_FONT_ID_PREFIX = "qdhelper_custom_reader_font:"

    fun findHostSelectedFontIndex(panel: View): Int? {
        val columnView = findViewByName<View>(panel, "columnView") ?: return null
        val adapter = callNoArg(columnView, "getAdapter") ?: return null
        val values = adapterValues(adapter) ?: return null
        val classLoader = adapter.javaClass.classLoader ?: return null
        val matcherClass = FeatureHooks.findClassOrNull(QDReaderHost.Classes.READER_FONT_MATCHER, classLoader)
            ?: return null
        val matcher = FeatureHooks.allDeclaredFields(matcherClass).singleOrNull { field ->
            Modifier.isStatic(field.modifiers) && matcherClass.isAssignableFrom(field.type)
        }?.let { field -> FeatureHooks.read(field, null) } ?: return null
        val match = FeatureHooks.allDeclaredMethods(matcherClass).singleOrNull { method ->
            method.returnType == java.lang.Boolean.TYPE && method.parameterTypes.size == 1
        } ?: return null
        return values.indexOfFirst { item ->
            item != null && customPathFromFontItem(item) == null &&
                runCatching { FeatureHooks.invoke(match, matcher, item) as? Boolean }.getOrNull() == true
        }.takeIf { it >= 0 }
    }

    fun createHostFontItem(classLoader: ClassLoader?, path: String): Any? {
        classLoader ?: return null
        val normalizedPath = FontLibraryStore.normalizePath(path)
        if (normalizedPath.isBlank()) return null
        val fontItemClass = FeatureHooks.findClassOrNull(FONT_ITEM_CLASS, classLoader) ?: return null
        val constructor = FeatureHooks.findConstructorOrNull(
            fontItemClass,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            Integer.TYPE,
            String::class.java,
            String::class.java,
            String::class.java,
            Integer.TYPE,
            java.lang.Integer::class.java,
        ) ?: return null
        return runCatching {
            FeatureHooks.newInstance(
                constructor,
                CUSTOM_FONT_ID_PREFIX + Integer.toHexString(normalizedPath.hashCode()),
                FontLibraryStore.displayNameForPath(normalizedPath),
                "",
                "",
                0,
                "Local",
                normalizedPath,
                "",
                0,
                0,
            )
        }.getOrNull()
    }

    fun customPathFromFontItem(item: Any?): String? {
        if (item == null) {
            return null
        }
        val id = callString(item, "getId") ?: return null
        if (!id.startsWith(CUSTOM_FONT_ID_PREFIX)) {
            return null
        }
        return callString(item, "getFileName")?.takeIf { it.isNotBlank() }
    }

    fun readerThemeColor(view: View, methodName: String): Int? {
        return hostClassLoaders(view).firstNotNullOfOrNull { classLoader ->
            runCatching {
                val themeClass = FeatureHooks.findClassOrNull(QDReaderHost.Classes.READER_THEME, classLoader)
                    ?: return@runCatching null
                val themeFactory = FeatureHooks.declaredMethodsNamed(themeClass, "v")
                    .singleOrNull { method -> method.parameterTypes.isEmpty() }
                    ?: return@runCatching null
                val theme = FeatureHooks.invoke(themeFactory, null) ?: return@runCatching null
                val colorGetter = FeatureHooks.declaredMethodsNamed(theme.javaClass, methodName)
                    .singleOrNull { method ->
                        method.parameterTypes.isEmpty() && method.returnType == Integer.TYPE
                    } ?: return@runCatching null
                FeatureHooks.invoke(colorGetter, theme) as? Int
            }.getOrNull()
        }
    }

    private fun hostClassLoaders(view: View): List<ClassLoader> {
        return listOfNotNull(
            view.context?.classLoader,
            view.javaClass.classLoader,
            Thread.currentThread().contextClassLoader,
        ).distinct()
    }

    @Suppress("UNCHECKED_CAST")
    fun adapterValues(adapter: Any): MutableList<Any?>? {
        return callNoArg(adapter, "getValues") as? MutableList<Any?>
    }

    fun itemViewFromHolder(holder: Any?): View? {
        if (holder == null) {
            return null
        }
        val field = FeatureHooks.findFieldOrNull(holder.javaClass, "itemView") ?: return null
        return FeatureHooks.read(field, holder) as? View
    }

    fun callNoArg(target: Any, methodName: String): Any? {
        return runCatching {
            val method = FeatureHooks.declaredMethodsNamed(target.javaClass, methodName)
                .singleOrNull { candidate -> candidate.parameterTypes.isEmpty() }
                ?: return@runCatching null
            FeatureHooks.invoke(method, target)
        }.getOrNull()
    }

    private fun callString(target: Any, methodName: String): String? {
        return callNoArg(target, methodName) as? String
    }

    private fun postReaderEvent(view: View, eventName: String): Boolean =
        runCatching {
            val context = view.context
            val classLoader = requireNotNull(view.javaClass.classLoader)

            // Both the Otto bus holder and the menu-event class drift by obfuscated short name,
            // so locate them name-independently via DexKit: the bus getter is the sole static no-arg
            // method returning the stable-named otto Bus on the class that holds a SingleBusManager
            // field; the event class is the single parameter type of the @Subscribe handler that
            // carries "EVENT_CURRENTCHAPTER" on QDSuperEngineView. Both targets are resolver-only.
            val busGetter = HostTargetResolver.resolveMethod(
                context = context,
                classLoader = classLoader,
                processName = HostSessionStore.processName,
                key = ReaderFontTargetKeys.eventBusGetter,
            ) ?: error("event_bus_target_unavailable")

            val eventHandler = HostTargetResolver.resolveMethod(
                context = context,
                classLoader = classLoader,
                processName = HostSessionStore.processName,
                key = ReaderFontTargetKeys.menuEventHandler,
            ) ?: error("menu_event_target_unavailable")

            val eventClass = eventHandler.parameterTypes[0]
            val eventConstructor = FeatureHooks.findConstructorOrNull(eventClass, String::class.java)
                ?: error("event_constructor_unavailable")
            val event = FeatureHooks.newInstance(eventConstructor, eventName) ?: error("event_creation_failed")

            val bus = FeatureHooks.invoke(busGetter, null) ?: error("event_bus_unavailable")
            val post = FeatureHooks.declaredMethodsNamed(bus.javaClass, "f")
                .singleOrNull { method -> method.parameterTypes.size == 1 && method.parameterTypes[0].isInstance(event) }
                ?: error("event_post_unavailable")
            check(FeatureHooks.invokeResult(post, bus, event) is HostAccess.Value) { "event_post_failed" }
            true
        }.onFailure { throwable ->
            FeatureHooks.w("Custom reader font post event failed: $eventName, error=${throwable.message}")
        }.getOrDefault(false)

    fun reloadReaderFontAndCurrentChapter(view: View): Boolean {
        if (!reloadReaderTypeface(view)) return false
        return postReaderEvent(view, "EVENT_CURRENTCHAPTER")
    }

    private fun reloadReaderTypeface(view: View): Boolean =
        runCatching {
            val classLoader = requireNotNull(view.javaClass.classLoader)
            val managerFactory = HostTargetResolver.resolveMethod(
                view.context, classLoader, HostSessionStore.processName, ReaderFontTargetKeys.engineInstanceGetter,
            ) ?: error("engine_instance_target_unavailable")
            val reload = HostTargetResolver.resolveMethod(
                view.context, classLoader, HostSessionStore.processName, ReaderFontTargetKeys.engineTypefaceReload,
            ) ?: error("engine_reload_target_unavailable")
            val manager = FeatureHooks.invoke(managerFactory, null) ?: error("engine_instance_unavailable")
            check(reload.declaringClass.isInstance(manager)) { "engine_target_owner_mismatch" }
            check(FeatureHooks.invokeResult(reload, manager) is HostAccess.Value) { "engine_reload_failed" }
            FeatureHooks.i("Custom reader font typeface and text paints reloaded")
            true
        }.onFailure { throwable ->
            FeatureHooks.w("Custom reader font reload typeface failed: ${throwable.message}")
        }.getOrDefault(false)


    private fun <T : View> findViewByName(root: View, name: String): T? {
        val id = root.resources.getIdentifier(name, "id", root.context.packageName)
        if (id == 0) return null
        @Suppress("UNCHECKED_CAST")
        return root.findViewById<View>(id) as? T
    }
}
