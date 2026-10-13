package com.xiyunmn.qdhelper.feature.readerfont.hook

import android.content.Context
import android.graphics.Typeface
import android.widget.TextView
import com.xiyunmn.qdhelper.config.SettingsRuntime
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureResources
import com.xiyunmn.qdhelper.feature.api.runtime.HookModule
import com.xiyunmn.qdhelper.feature.api.runtime.InstallableFeature
import com.xiyunmn.qdhelper.feature.readerfont.host.GlobalFontTypefaceProvider
import com.xiyunmn.qdhelper.foundation.runtime.HostSessionStore
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols as QDReaderHost
import com.xiyunmn.qdhelper.host.api.target.HostTargetResolver
import com.xiyunmn.qdhelper.host.api.target.ReaderFontTargetKeys
import com.xiyunmn.qdhelper.shared.fonts.FontLibraryStore
import com.xiyunmn.qdhelper.shared.fonts.FontTypefaceCache
import java.io.File
import java.io.FileInputStream
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.nio.ByteBuffer

object GlobalFontFeature : InstallableFeature {
    override val name: String = "GlobalFontFeature"

    private const val MAX_FLUTTER_FONT_BYTES = 80L * 1024L * 1024L

    init {
        FeatureResources.register("GlobalFontFeature") { FontTypefaceCache.clear() }
    }

    override fun install(
        module: HookModule,
        classLoader: ClassLoader,
        processName: String,
        phase: String,
    ) {
        if (!QDReaderHost.isMainProcess(processName)) return
        var installedHooks = 0
        installedHooks += hookTypefaceGetter(
            module = module,
            method = resolveTypefaceGetter(
                classLoader,
                processName,
                ReaderFontTargetKeys.globalRegularTypefaceGetter,
            ),
            id = "globalFont.regular",
            provider = GlobalFontTypefaceProvider::regular,
        )
        installedHooks += hookTypefaceGetter(
            module = module,
            method = resolveTypefaceGetter(
                classLoader,
                processName,
                ReaderFontTargetKeys.globalEmphasisTypefaceGetter,
            ),
            id = "globalFont.emphasis",
            provider = GlobalFontTypefaceProvider::emphasis,
        )
        installedHooks += hookFontTextView(module, classLoader)
        installedHooks += hookSimpleFontWidget(module, classLoader, QDReaderHost.Classes.QD_FONT_EDIT_TEXT, "editText")
        installedHooks += hookSimpleFontWidget(module, classLoader, QDReaderHost.Classes.QD_FONT_RADIO_BUTTON, "radioButton")
        installedHooks += hookSimpleFontWidget(module, classLoader, QDReaderHost.Classes.QD_FONT_BUTTON, "button")
        installedHooks += hookFlutterFontLoader(module, classLoader)
        installedHooks += hookFlutterChannelRegistration(module, classLoader, processName)
        FeatureHooks.i("Global font phase finished: phase=$phase, installedHooks=$installedHooks")
    }

    private fun resolveTypefaceGetter(
        classLoader: ClassLoader,
        processName: String,
        key: com.xiyunmn.qdhelper.host.api.target.TargetKey.MethodTarget,
    ): Method? = HostTargetResolver.resolveMethod(
        context = HostSessionStore.contextOrNull(),
        classLoader = classLoader,
        processName = processName,
        key = key,
    )

    private fun hookTypefaceGetter(
        module: HookModule,
        method: Method?,
        id: String,
        provider: (android.content.Context) -> Typeface?,
    ): Int {
        if (method == null || !isTypefaceGetter(method)) return 0
        return if (FeatureHooks.hookAround(module, method, "GlobalFont.${method.declaringClass.name}.${method.name}", id) { chain ->
                val context = HostSessionStore.contextOrNull()
                if (context == null) chain.proceed() else provider(context) ?: chain.proceed()
            }) {
            1
        } else {
            0
        }
    }

    private fun hookFontTextView(module: HookModule, classLoader: ClassLoader): Int {
        val clazz = FeatureHooks.findClassOrNull(QDReaderHost.Classes.QD_FONT_TEXT_VIEW, classLoader) ?: return 0
        val helper = FeatureHooks.allDeclaredMethods(clazz).singleOrNull { method ->
            !Modifier.isStatic(method.modifiers) &&
                method.returnType == Void.TYPE &&
                method.parameterTypes.size == 2 &&
                method.parameterTypes[0] == Boolean::class.javaPrimitiveType &&
                method.parameterTypes[1] == Int::class.javaPrimitiveType
        } ?: return 0
        return if (FeatureHooks.hookAfter(module, helper, "GlobalFont.QDFontTextView.apply", "globalFont.widget.text") { chain, _ ->
                val textView = chain.thisObject as? TextView ?: return@hookAfter
                if (chain.args.getOrNull(0) == true) return@hookAfter
                val style = chain.args.getOrNull(1) as? Int ?: Typeface.NORMAL
                GlobalFontTypefaceProvider.apply(textView, style)
            }) {
            1
        } else {
            0
        }
    }

    private fun hookSimpleFontWidget(
        module: HookModule,
        classLoader: ClassLoader,
        className: String,
        idSuffix: String,
    ): Int {
        val clazz = FeatureHooks.findClassOrNull(className, classLoader) ?: return 0
        val helper = FeatureHooks.allDeclaredMethods(clazz).singleOrNull { method ->
            !Modifier.isStatic(method.modifiers) && method.returnType == Void.TYPE && method.parameterTypes.isEmpty()
        } ?: return 0
        return if (FeatureHooks.hookAfter(module, helper, "GlobalFont.$className.apply", "globalFont.widget.$idSuffix") { chain, _ ->
                val textView = chain.thisObject as? TextView ?: return@hookAfter
                if (!GlobalFontTypefaceProvider.isApplied(textView)) {
                    GlobalFontTypefaceProvider.apply(textView, textView.typeface?.style ?: Typeface.NORMAL)
                }
            }) {
            1
        } else {
            0
        }
    }

    private fun hookFlutterFontLoader(module: HookModule, classLoader: ClassLoader): Int {
        val clazz = FeatureHooks.findClassOrNull(QDReaderHost.Classes.FLUTTER_NATIVE_FONT_API_IMPL, classLoader)
            ?: return 0
        val loader = FeatureHooks.allDeclaredMethods(clazz).singleOrNull { method ->
            !Modifier.isStatic(method.modifiers) &&
                method.returnType == ByteBuffer::class.java &&
                method.parameterTypes.contentEquals(arrayOf(String::class.java))
        } ?: return 0
        return if (FeatureHooks.hookAround(module, loader, "GlobalFont.Flutter.loadNativeFont", "globalFont.flutter.loader") { chain ->
                val context = HostSessionStore.contextOrNull()
                val requestedFamily = chain.args.firstOrNull() as? String
                val custom = context
                    ?.let { GlobalFontTypefaceProvider.flutterFontPath(it, requestedFamily) }
                    ?.let(::loadFlutterFontBuffer)
                custom ?: chain.proceed()
            }) {
            1
        } else {
            0
        }
    }

    private fun hookFlutterChannelRegistration(
        module: HookModule,
        classLoader: ClassLoader,
        processName: String,
    ): Int {
        val channelDelegate = FeatureHooks.findClassOrNull(QDReaderHost.Classes.FLUTTER_CHANNEL_DELEGATE, classLoader)
            ?: return 0
        val setupChannel = FeatureHooks.allDeclaredMethods(channelDelegate).singleOrNull { method ->
            !Modifier.isStatic(method.modifiers) &&
                method.returnType == Void.TYPE &&
                method.parameterTypes.size == 3 &&
                Context::class.java.isAssignableFrom(method.parameterTypes[0]) &&
                method.parameterTypes[2].name == QDReaderHost.Classes.FLUTTER_ENGINE
        } ?: return 0
        val registrar = resolveFlutterRegistrar(classLoader, processName) ?: return 0
        val apiClass = FeatureHooks.findClassOrNull(QDReaderHost.Classes.FLUTTER_NATIVE_FONT_API_IMPL, classLoader)
            ?: return 0
        val constructor = FeatureHooks.findConstructorOrNull(apiClass) ?: return 0
        if (!registrar.parameterTypes[1].isAssignableFrom(apiClass)) return 0
        return if (FeatureHooks.hookAfter(module, setupChannel, "GlobalFont.Flutter.setupChannel", "globalFont.flutter.register") { chain, _ ->
                val context = HostSessionStore.contextOrNull() ?: return@hookAfter
                if (!SettingsRuntime.isCustomGlobalFontEnabled(context)) return@hookAfter
                if (GlobalFontTypefaceProvider.flutterFontPath(context, "HYQiH_Light") == null) {
                    return@hookAfter
                }
                val engine = chain.args.getOrNull(2) ?: return@hookAfter
                val api = FeatureHooks.newInstance(constructor) ?: return@hookAfter
                runCatching { FeatureHooks.invoke(registrar, null, engine, api) }
                    .onFailure { throwable ->
                        FeatureHooks.w("Global Flutter font registration failed: ${throwable.javaClass.name}")
                    }
            }) {
            1
        } else {
            0
        }
    }

    private fun resolveFlutterRegistrar(classLoader: ClassLoader, processName: String): Method? =
        HostTargetResolver.resolveMethod(
            context = HostSessionStore.contextOrNull(),
            classLoader = classLoader,
            processName = processName,
            key = ReaderFontTargetKeys.flutterNativeFontRegistrar,
        )

    private fun isFlutterRegistrar(method: Method): Boolean {
        if (!Modifier.isPublic(method.modifiers) || !Modifier.isStatic(method.modifiers) ||
            method.returnType != Void.TYPE || method.parameterTypes.size != 2 ||
            method.parameterTypes[0].name != QDReaderHost.Classes.FLUTTER_ENGINE
        ) {
            return false
        }
        val api = method.parameterTypes[1]
        return api.isInterface && FeatureHooks.allDeclaredMethods(api).singleOrNull()?.let { candidate ->
            candidate.parameterTypes.contentEquals(arrayOf(String::class.java)) &&
                candidate.returnType == ByteBuffer::class.java
        } == true
    }

    private fun loadFlutterFontBuffer(path: String): ByteBuffer? = runCatching {
        val file = File(path)
        val length = file.length()
        if (!file.isFile || length !in 1..MAX_FLUTTER_FONT_BYTES || !FontLibraryStore.validateTypeface(file)) {
            return@runCatching null
        }
        val buffer = ByteBuffer.allocateDirect(length.toInt())
        FileInputStream(file).channel.use { channel ->
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) break
            }
        }
        // The host Pigeon bridge expects the returned buffer position to equal the payload length.
        buffer.takeIf { it.position() == length.toInt() }
    }.onFailure { throwable ->
        FeatureHooks.w("Global Flutter font load failed: ${throwable.javaClass.name}")
    }.getOrNull()

    private fun isTypefaceGetter(method: Method): Boolean =
        !Modifier.isStatic(method.modifiers) && method.parameterTypes.isEmpty() && method.returnType == Typeface::class.java
}
