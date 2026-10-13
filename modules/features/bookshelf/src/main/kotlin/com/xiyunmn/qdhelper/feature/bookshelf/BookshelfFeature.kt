package com.xiyunmn.qdhelper.feature.bookshelf

import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.host.api.target.HostTargetResolver
import com.xiyunmn.qdhelper.host.api.target.BookshelfTargetKeys
import com.xiyunmn.qdhelper.feature.api.runtime.InstallableFeature
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols as QDReaderHost
import com.xiyunmn.qdhelper.foundation.runtime.HostSessionStore
import com.xiyunmn.qdhelper.config.SettingsRuntime
import com.xiyunmn.qdhelper.feature.api.runtime.HookModule
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

object BookshelfFeature : InstallableFeature {
    override val name: String = "BookshelfFeature"

    private val helperAccessors = listOf(
        HelperAccessor("getBookShelfBorder01", "getBorder01"),
        HelperAccessor("getBookShelfFont", "getFont"),
        HelperAccessor("getBookShelfFontHLight", "getFontHLight"),
        HelperAccessor("getBookShelfFontLight", "getFontLight"),
        HelperAccessor("getBookShelfFontOnSurface", "getFontOnSurface"),
        HelperAccessor("getBookShelfHeadImage", "getHeadImage"),
        HelperAccessor("getBookShelfNewUserColor", "getNewuserFont"),
        HelperAccessor("getBookShelfSurface01", "getSurface01"),
        HelperAccessor("getBookShelfSurfaceIcon", "getSurfaceIcon"),
        HelperAccessor("getBrowsingHistoryWebp", "getBrowsingHistoryWebp"),
        HelperAccessor("getNewUserFont", "getNewuserFont"),
    )

    private val hitCounters = ConcurrentHashMap<String, AtomicLong>()

    override fun install(
        module: HookModule,
        classLoader: ClassLoader,
        processName: String,
        phase: String,
    ) {
        val bookshelfConfigClass = FeatureHooks.findClassOrNull(QDReaderHost.Classes.BOOKSHELF_CONFIG, classLoader)
        val method = bookshelfConfigClass?.let { FeatureHooks.findDeclaredMethodOrNull(it, "getDarkMode") }
        val lightModeField = bookshelfConfigClass?.let(FeatureHooks::allDeclaredFields)
            ?.firstOrNull { it.name == "lightMode" }

        val installedHooks = installDarkModeSameAsDay(module, method, lightModeField, phase) +
            installConfigHelperSameAsDay(module, classLoader, processName, bookshelfConfigClass, phase)
        FeatureHooks.i("P1 bookshelf phase finished: phase=$phase, process=$processName, installedHooks=$installedHooks")
    }

    private fun installDarkModeSameAsDay(
        module: HookModule,
        method: Method?,
        lightModeField: Field?,
        phase: String,
    ): Int {
        if (method == null || lightModeField == null) {
            return 0
        }

        val id = "bookshelf.config.dark.sameAsDay"
        val installKey = "$id:${method.toGenericString()}"
        val success = FeatureHooks.hookAround(
            module = module,
            executable = method,
            feature = "p1.bookshelf.darkSameAsDay:${method.ownerLabel()}.${method.name}",
            id = "qdhelper.p1.bookshelf.dark_same_as_day",
        ) { chain ->
            if (!SettingsRuntime.isBookshelfNightModeSameAsDayEnabled(HostSessionStore.contextOrNull())) {
                return@hookAround chain.proceed()
            }

            val lightMode = runCatching { lightModeField.get(chain.thisObject) }.getOrNull()
            if (lightMode == null) {
                FeatureHooks.w("P1 bookshelf fallback $id: lightMode is null")
                return@hookAround chain.proceed()
            }

            logHit(installKey, "return-light-mode")
            lightMode
        }
        if (!success) {
            return 0
        }

        FeatureHooks.i("P1 bookshelf installed $id: ${method.ownerLabel()}.${method.name}, phase=$phase")
        return 1
    }

    private fun installConfigHelperSameAsDay(
        module: HookModule,
        classLoader: ClassLoader,
        processName: String,
        bookshelfConfigClass: Class<*>?,
        phase: String,
    ): Int {
        if (bookshelfConfigClass == null) {
            return 0
        }

        val helperClass = FeatureHooks.findClassOrNull(QDReaderHost.Classes.QD_APP_CONFIG_HELPER_COMPANION, classLoader)
        val resolver = createLightModeResolver(classLoader, processName, bookshelfConfigClass)
        val configColorsClass = resolver?.configColorsClass
        if (helperClass == null || resolver == null || configColorsClass == null) {
            return 0
        }

        var installedHooks = 0
        helperAccessors.forEach { accessor ->
            val helperMethod = FeatureHooks.findDeclaredMethodOrNull(helperClass, accessor.helperMethodName)
            val configGetter = FeatureHooks.findDeclaredMethodOrNull(configColorsClass, accessor.configGetterName)
            if (installConfigHelperAccessor(module, helperMethod, configGetter, resolver, accessor, phase)) {
                installedHooks++
            }
        }
        return installedHooks
    }

    private fun createLightModeResolver(
        classLoader: ClassLoader,
        processName: String,
        bookshelfConfigClass: Class<*>,
    ): LightModeResolver? {
        val appConfigClass = FeatureHooks.findClassOrNull(QDReaderHost.Classes.APP_CONFIG, classLoader) ?: return null
        val singletonField = FeatureHooks.allDeclaredFields(appConfigClass).firstOrNull { field ->
            Modifier.isStatic(field.modifiers) && field.type == appConfigClass
        } ?: return null
        val currentConfigMethod = HostTargetResolver.resolveMethod(
            context = HostSessionStore.contextOrNull(),
            classLoader = classLoader,
            processName = processName,
            key = BookshelfTargetKeys.appConfigCurrent,
        ) ?: return null
        val appConfigBeanClass = currentConfigMethod.returnType
        val bookshelfConfigGetter = FeatureHooks.findDeclaredMethodOrNull(appConfigBeanClass, "getBookshelfConfig") ?: return null
        val lightModeGetter = FeatureHooks.findDeclaredMethodOrNull(bookshelfConfigClass, "getLightMode") ?: return null
        return LightModeResolver(
            singletonField = singletonField,
            currentConfigMethod = currentConfigMethod,
            bookshelfConfigGetter = bookshelfConfigGetter,
            lightModeGetter = lightModeGetter,
            configColorsClass = lightModeGetter.returnType,
        )
    }

    private fun installConfigHelperAccessor(
        module: HookModule,
        helperMethod: Method?,
        configGetter: Method?,
        resolver: LightModeResolver,
        accessor: HelperAccessor,
        phase: String,
    ): Boolean {
        if (helperMethod == null || configGetter == null) {
            return false
        }

        val id = "bookshelf.helper.${accessor.helperMethodName}.sameAsDay"
        val installKey = "$id:${helperMethod.toGenericString()}"
        val success = FeatureHooks.hookAround(
            module = module,
            executable = helperMethod,
            feature = "p1.bookshelf.helperSameAsDay:${helperMethod.ownerLabel()}.${helperMethod.name}",
            id = "qdhelper.p1.$id",
        ) { chain ->
            if (!SettingsRuntime.isBookshelfNightModeSameAsDayEnabled(HostSessionStore.contextOrNull())) {
                return@hookAround chain.proceed()
            }

            val lightValue = runCatching {
                resolver.resolve(configGetter)
            }.getOrElse { throwable ->
                FeatureHooks.w("P1 bookshelf helper fallback $id: ${throwable.javaClass.name}")
                null
            }
            if (lightValue == null) {
                return@hookAround chain.proceed()
            }

            logHit(installKey, "return-light-${accessor.configGetterName}")
            lightValue
        }
        if (!success) {
            return false
        }

        FeatureHooks.i(
            "P1 bookshelf installed $id: " +
                "${helperMethod.ownerLabel()}.${helperMethod.name}->${accessor.configGetterName}, phase=$phase",
        )
        return true
    }

    private fun logHit(installKey: String, action: String) {
        val count = hitCounters.computeIfAbsent(installKey) { AtomicLong(0L) }.incrementAndGet()
        if (count <= 20L || count % 50L == 0L) {
            FeatureHooks.i("P1 hit bookshelf.config.dark.sameAsDay#$count: action=$action, thread=${Thread.currentThread().name}")
        }
    }

    private fun Method.ownerLabel(): String {
        return QDReaderHost.classLabel(declaringClass.name)
    }

    private data class HelperAccessor(
        val helperMethodName: String,
        val configGetterName: String,
    )

    private data class LightModeResolver(
        val singletonField: Field,
        val currentConfigMethod: Method,
        val bookshelfConfigGetter: Method,
        val lightModeGetter: Method,
        val configColorsClass: Class<*>,
    ) {
        fun resolve(configGetter: Method): Any? {
            val appConfig = singletonField.get(null) ?: return null
            val appConfigBean = currentConfigMethod.invoke(appConfig) ?: return null
            val bookshelfConfig = bookshelfConfigGetter.invoke(appConfigBean) ?: return null
            val lightMode = lightModeGetter.invoke(bookshelfConfig) ?: return null
            return configGetter.invoke(lightMode)
        }
    }
}
