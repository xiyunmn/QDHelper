package com.xiyunmn.qdhelper.feature.startup

import android.app.Activity
import android.widget.ImageView
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.host.api.target.HostTargetResolver
import com.xiyunmn.qdhelper.host.api.target.StartupTargetKeys
import com.xiyunmn.qdhelper.feature.api.runtime.InstallableFeature
import com.xiyunmn.qdhelper.foundation.identity.OwnerId
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols as QDReaderHost
import com.xiyunmn.qdhelper.foundation.runtime.HostSessionStore
import com.xiyunmn.qdhelper.feature.api.runtime.ActivityCallbacks
import com.xiyunmn.qdhelper.config.SettingsRuntime
import com.xiyunmn.qdhelper.feature.api.runtime.HookModule
import java.lang.reflect.Method
import java.util.ArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

object StartupFeature : InstallableFeature {
    override val name: String = "StartupFeature"

    private val hitCounters = ConcurrentHashMap<String, AtomicLong>()

    override fun install(
        module: HookModule,
        classLoader: ClassLoader,
        processName: String,
        phase: String,
    ) {
        var installedHooks = 0
        installedHooks += installSplashAdBlock(module, classLoader, phase)
        installedHooks += installCustomSplashImage(module, classLoader, phase)
        installedHooks += installCustomSplashRender(module, classLoader, phase)
        installedHooks += installDefaultSplashSkip(module, classLoader, phase)
        installedHooks += OfficialSplashLocalUnlock.install(module, classLoader, phase)
        installActivityResultHandler(processName)
        FeatureHooks.i("Startup install phase finished: phase=$phase, process=$processName, installedHooks=$installedHooks")
    }

    private fun installSplashAdBlock(
        module: HookModule,
        classLoader: ClassLoader,
        phase: String,
    ): Int {
        val method = HostTargetResolver.resolveEarlyMethod(
            context = HostSessionStore.contextOrNull(),
            classLoader = classLoader,
            processName = HostSessionStore.processName,
            key = StartupTargetKeys.splashSkipAd,
        )
            ?: return 0
        val installKey = "startup.splashAd:${method.toGenericString()}"
        val success = FeatureHooks.hookAround(
            module = module,
            executable = method,
            feature = "startup.splashAd:${method.ownerLabel()}.${method.name}",
            id = "qdhelper.startup.splash_ad",
        ) { chain ->
            if (!SettingsRuntime.isSplashAdBlockEnabled(HostSessionStore.contextOrNull())) {
                return@hookAround chain.proceed()
            }
            val activity = chain.args.getOrNull(0) as? Activity
            val callback = chain.args.getOrNull(1)
            if (activity == null || callback == null) {
                FeatureHooks.w("Startup splash ad block fallback: invalid arguments")
                return@hookAround chain.proceed()
            }
            logHit("startup.splashAd", installKey, "callback-no-ad")
            dispatchNoSplashResult(activity, callback)
            null
        }
        if (!success) {
            return 0
        }

        FeatureHooks.i("Startup splash ad block installed: ${method.ownerLabel()}.${method.name}, phase=$phase")
        return 1
    }

    private fun installCustomSplashImage(
        module: HookModule,
        classLoader: ClassLoader,
        phase: String,
    ): Int {
        val helperClass = FeatureHooks.findClassOrNull(QDReaderHost.Classes.QD_APP_CONFIG_HELPER, classLoader) ?: return 0
        val bootWallPapersClass = FeatureHooks.findClassOrNull(QDReaderHost.Classes.BOOT_WALL_PAPERS, classLoader) ?: return 0
        val paperClass = FeatureHooks.findClassOrNull(QDReaderHost.Classes.CONFIG_PAPER, classLoader) ?: return 0
        val methods = FeatureHooks.allDeclaredMethods(helperClass)
            .filter { method ->
                method.parameterTypes.isEmpty() && bootWallPapersClass.isAssignableFrom(method.returnType)
            }
        var count = 0
        methods.forEach { method ->
            count += installCustomSplashImageMethod(
                module = module,
                method = method,
                bootWallPapersClass = bootWallPapersClass,
                paperClass = paperClass,
                phase = phase,
            )
        }
        return count
    }

    private fun installCustomSplashImageMethod(
        module: HookModule,
        method: Method,
        bootWallPapersClass: Class<*>,
        paperClass: Class<*>,
        phase: String,
    ): Int {
        val installKey = "startup.customSplashImage:${method.toGenericString()}"
        val success = FeatureHooks.hookAround(
            module = module,
            executable = method,
            feature = "startup.customSplashImage:${method.ownerLabel()}.${method.name}",
            id = "qdhelper.startup.custom_splash_image.${method.name}",
        ) { chain ->
            val original = chain.proceed()
            val custom = buildCustomBootWallPapers(bootWallPapersClass, paperClass)
            if (custom != null) {
                logHit("startup.customSplashImage", installKey, "replace-boot-wall-papers")
                custom
            } else {
                original
            }
        }
        if (!success) {
            return 0
        }

        FeatureHooks.i("Startup custom splash image installed: ${method.ownerLabel()}.${method.name}, phase=$phase")
        return 1
    }

    private fun buildCustomBootWallPapers(
        bootWallPapersClass: Class<*>,
        paperClass: Class<*>,
    ): Any? {
        val context = HostSessionStore.contextOrNull()
        // 注入与"同步启动图文件"共用同一真源：url/paperId 一致，official 仅在其直链非空时优先。
        val target = StartupImageStore.activeSplashTarget(context) ?: return null
        return runCatching {
            val paperConstructor = FeatureHooks.findConstructorOrNull(
                paperClass,
                Long::class.javaPrimitiveType!!,
                String::class.java,
                Long::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
            ) ?: return@runCatching null
            val paper = FeatureHooks.newInstance(
                paperConstructor,
                    target.paperId,
                    target.url,
                    System.currentTimeMillis() / 1000L,
                    if (target.darkLogo) 1 else 0,
                ) ?: return@runCatching null
            val papers = ArrayList<Any>(1).apply { add(paper) }
            val containerConstructor = FeatureHooks.findConstructorOrNull(
                bootWallPapersClass,
                List::class.java,
                String::class.java,
                Int::class.javaPrimitiveType!!,
            ) ?: return@runCatching null
            FeatureHooks.newInstance(containerConstructor, papers, "", 1)
        }.getOrElse { throwable ->
            FeatureHooks.e("Startup custom splash image build failed", throwable)
            null
        }
    }

    private fun dispatchNoSplashResult(activity: Activity, callback: Any) {
        val action = Runnable {
            if (activity.isFinishing || activity.isDestroyed) {
                return@Runnable
            }
            // 回调是宿主 SplashManager.judian 单方法接口 search(int, SplashItem, String)；短名会随
            // 版本漂移，改用结构锚定：3 参、首参 int、末参 String、返回 void，非 synthetic 桥接。
            val method = FeatureHooks.allDeclaredMethods(callback.javaClass).singleOrNull { candidate ->
                candidate.parameterTypes.size == 3 &&
                    candidate.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    candidate.parameterTypes[2] == String::class.java &&
                    candidate.returnType == Void.TYPE &&
                    !candidate.isSynthetic &&
                    !candidate.isBridge
            } ?: run {
                FeatureHooks.w("Startup splash ad block fallback: callback method missing")
                return@Runnable
            }
            runCatching {
                FeatureHooks.invoke(method, callback, -1, null, null)
            }.onFailure { throwable ->
                FeatureHooks.e("Startup splash ad block callback failed", throwable)
            }
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            action.run()
        } else {
            activity.runOnUiThread(action)
        }
    }

    private fun installCustomSplashRender(
        module: HookModule,
        classLoader: ClassLoader,
        phase: String,
    ): Int {
        val method = HostTargetResolver.resolveEarlyMethod(
            context = HostSessionStore.contextOrNull(),
            classLoader = classLoader,
            processName = HostSessionStore.processName,
            key = StartupTargetKeys.splashActivityInitCustom,
        )
            ?: return 0
        val installKey = "startup.customRender:${method.toGenericString()}"
        val success = FeatureHooks.hookAround(
            module = module,
            executable = method,
            feature = "startup.customRender:${method.ownerLabel()}.${method.name}",
            id = "qdhelper.startup.custom_render",
        ) { chain ->
            if (SettingsRuntime.isSplashImageSkipEnabled(HostSessionStore.contextOrNull())) {
                logHit("startup.splashImage", installKey, "skip-custom")
                return@hookAround false
            }
            val activity = chain.thisObject as? Activity
            if (activity == null) {
                return@hookAround chain.proceed()
            }
            if (StartupImageStore.activeSplashTarget(HostSessionStore.contextOrNull()) == null) {
                return@hookAround chain.proceed()
            }
            val imageView = resolveSplashImageView(activity)
            if (imageView == null) {
                FeatureHooks.w("Startup custom render: mIvSplash not found")
                return@hookAround chain.proceed()
            }
            val rendered = StartupImageRenderer.renderToImageView(activity, imageView)
            if (rendered) {
                logHit("startup.customRender", installKey, "rendered-custom")
                // initCustomSplashScreen() 返回 boolean：true 表示已接管启动图渲染，短路宿主原逻辑。
                true
            } else {
                chain.proceed()
            }
        }
        if (!success) {
            return 0
        }

        FeatureHooks.i("Startup custom render installed: ${method.ownerLabel()}.${method.name}, phase=$phase")
        return 1
    }

    private fun installDefaultSplashSkip(
        module: HookModule,
        classLoader: ClassLoader,
        phase: String,
    ): Int {
        val method = HostTargetResolver.resolveEarlyMethod(
            context = HostSessionStore.contextOrNull(),
            classLoader = classLoader,
            processName = HostSessionStore.processName,
            key = StartupTargetKeys.splashActivityInitDefault,
        ) ?: return 0
        val installKey = "startup.defaultSplash:${method.toGenericString()}"
        val success = FeatureHooks.hookAround(
            module = module,
            executable = method,
            feature = "startup.defaultSplash:${method.ownerLabel()}.${method.name}",
            id = "qdhelper.startup.default_splash",
        ) { chain ->
            if (!SettingsRuntime.isSplashImageSkipEnabled(HostSessionStore.contextOrNull())) {
                return@hookAround chain.proceed()
            }
            logHit("startup.splashImage", installKey, "skip-default")
            null
        }
        if (!success) return 0

        FeatureHooks.i("Startup default splash skip installed: ${method.ownerLabel()}.${method.name}, phase=$phase")
        return 1
    }

    private fun installActivityResultHandler(processName: String) {
        if (!QDReaderHost.isMainProcess(processName)) {
            return
        }
        val result = ActivityCallbacks.registerResult(
            OwnerId("startup"),
            StartupImagePicker.REQUEST_CODE,
        ) { activity, resultCode, data ->
            StartupImagePicker.handleActivityResult(activity, StartupImagePicker.REQUEST_CODE, resultCode, data)
        }
        when (result) {
            is com.xiyunmn.qdhelper.hook.activity.ActivityResultRegistry.RegistrationResult.Registered ->
                FeatureHooks.i("Startup activityResult handler registered")

            is com.xiyunmn.qdhelper.hook.activity.ActivityResultRegistry.RegistrationResult.AlreadyRegistered ->
                FeatureHooks.i("Startup activityResult handler already registered")

            is com.xiyunmn.qdhelper.hook.activity.ActivityResultRegistry.RegistrationResult.RequestCodeConflict ->
                FeatureHooks.w("Startup activityResult registration rejected: $result")
        }
    }

    /**
     * 定位 SplashActivity 承载启动图的 ImageView。优先按名 `mIvSplash`（7.9.469 反编译确认，另有
     * mIvBottom/mIvSlogan/mIvYingYongBao 三个 ImageView 字段，不能靠"第一个 ImageView 字段"盲选）；
     * 名称被混淆时回落到唯一非空 ImageView 字段。字段值可能未初始化（返回 null），交由调用方放行。
     */
    private fun resolveSplashImageView(activity: Activity): ImageView? {
        return runCatching {
            val fields = FeatureHooks.allDeclaredFields(activity.javaClass)
                .filter { it.type == ImageView::class.java }
            val byName = fields.firstOrNull { it.name == "mIvSplash" }
            if (byName != null) {
                return@runCatching FeatureHooks.read(byName, activity) as? ImageView
            }
            val nonNull = fields.mapNotNull { field -> FeatureHooks.read(field, activity) as? ImageView }
            nonNull.singleOrNull()
        }.getOrNull()
    }

    private fun logHit(id: String, installKey: String, action: String) {
        val count = hitCounters.computeIfAbsent(installKey) { AtomicLong(0L) }.incrementAndGet()
        if (count <= 20L || count % 50L == 0L) {
            FeatureHooks.i("Startup hit $id#$count: action=$action, thread=${Thread.currentThread().name}")
        }
    }

    private fun Method.ownerLabel(): String {
        return QDReaderHost.classLabel(declaringClass.name)
    }
}
