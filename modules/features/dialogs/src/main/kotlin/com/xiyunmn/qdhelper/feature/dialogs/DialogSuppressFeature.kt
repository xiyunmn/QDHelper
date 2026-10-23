package com.xiyunmn.qdhelper.feature.dialogs

import android.content.Context
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.host.api.target.HostTargetResolver
import com.xiyunmn.qdhelper.host.api.target.DialogTargetKeys
import com.xiyunmn.qdhelper.feature.api.runtime.InstallableFeature
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols as QDReaderHost
import com.xiyunmn.qdhelper.foundation.runtime.HostSessionStore
import com.xiyunmn.qdhelper.config.SettingsRuntime
import com.xiyunmn.qdhelper.feature.api.runtime.HookModule
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

object DialogSuppressFeature : InstallableFeature {
    override val name: String = "DialogSuppressFeature"

    private val hitCounters = ConcurrentHashMap<String, AtomicLong>()

    override fun install(
        module: HookModule,
        classLoader: ClassLoader,
        processName: String,
        phase: String,
    ) {
        var installedHooks = 0

        installedHooks += installNoArgNoOp(
            module = module,
            classLoader = classLoader,
            phase = phase,
            id = "dialog.privacy.main.version",
            className = QDReaderHost.Classes.MAIN_GROUP_ACTIVITY,
            methodName = "checkPrivacyVersion",
            hookId = "qdhelper.p1.dialog.privacy_version",
            shouldBlock = SettingsRuntime::isPrivacyVersionDialogSuppressed,
        )
        installedHooks += installNoArgNoOp(
            module = module,
            classLoader = classLoader,
            phase = phase,
            id = "dialog.update.main.check",
            className = QDReaderHost.Classes.MAIN_GROUP_ACTIVITY,
            methodName = "checkUpdate",
            hookId = "qdhelper.p1.dialog.update_main",
            shouldBlock = SettingsRuntime::isUpdateCheckSuppressed,
        )
        installedHooks += installAutoUpdateCore(module, classLoader, phase)
        installedHooks += installUpdatePresenter(module, classLoader, phase)
        installedHooks += ShiplyUpdateGate.install(module, classLoader)
        installedHooks += installMainNotificationNoOp(module, classLoader, phase)
        installedHooks += installNotificationDialogRequestNoOp(module, classLoader, phase)
        installedHooks += installNotificationSceneGate(module, classLoader, phase)
        installedHooks += installPersonalRecommendPushNoOp(module, classLoader, phase)
        installedHooks += installTeenagerEnterDialogGate(module, classLoader, phase)

        FeatureHooks.i("P1 suppress phase finished: phase=$phase, process=$processName, installedHooks=$installedHooks")
    }

    private fun installNoArgNoOp(
        module: HookModule,
        classLoader: ClassLoader,
        phase: String,
        id: String,
        className: String,
        methodName: String,
        hookId: String,
        shouldBlock: (Context?) -> Boolean,
    ): Int {
        val clazz = FeatureHooks.findClassOrNull(className, classLoader) ?: return 0
        val method = FeatureHooks.findDeclaredMethodOrNull(clazz, methodName) ?: return 0
        return installNoOp(module, id, method, hookId, phase, shouldBlock)
    }

    /**
     * Block the host auto-update path at its source. The auto-update class exposes public entries,
     * queued worker callbacks and cached-force-update gates. Older suppression only hooked `void`
     * methods whose second parameter was the callback, which missed cold-start work dispatched before
     * the host Context was published, callbacks whose parameter moved, boolean install helpers and the
     * cached force-update gate.
     *
     * The class short name drifts inside the active interval, so primary discovery uses DexKit
     * (`autoUpdateEntryAnchor`: an update-notify string constant + the class shape). The centralized
     * early registry follows the Activity's callback type before Context publication; every candidate is
     * accepted through the same canonical verifier and cardinality rule. Every
     * callback-bearing `void`/`boolean` method and the cached-force gate are then hooked structurally.
     * Calls owned by `AboutActivity` are deliberately allowed so the user's explicit manual check works.
     */
    private fun installAutoUpdateCore(module: HookModule, classLoader: ClassLoader, phase: String): Int {
        val anchor = HostTargetResolver.resolveEarlyMethod(
            context = HostSessionStore.contextOrNull(),
            classLoader = classLoader,
            processName = HostSessionStore.processName,
            key = DialogTargetKeys.autoUpdateEntry,
        )
        if (anchor == null) {
            if (phase.startsWith("resolverReady:")) {
                FeatureHooks.w("Auto-update core hook unresolved after resolver retry")
            } else {
                FeatureHooks.i("Auto-update core hook deferred until resolver-ready phase: phase=$phase")
            }
            return 0
        }
        val gClass = anchor.declaringClass
        val callbackCandidates = FeatureHooks.nestedClasses(gClass).filter { nested ->
            nested.isInterface && FeatureHooks.allDeclaredMethods(nested).size >= 8
        }
        val judianClass = anchor.parameterTypes.firstOrNull(callbackCandidates::contains)
            ?: callbackCandidates.singleOrNull()
        if (judianClass == null) {
            FeatureHooks.w("Auto-update core hook skipped: judian interface not found on ${gClass.name}")
            return 0
        }
        var count = 0
        AutoUpdateSuppressionPolicy.blockableMethods(FeatureHooks.allDeclaredMethods(gClass), judianClass)
            .forEach { method ->
                count += installAutoUpdateBlock(
                    module = module,
                    method = method,
                    callbackClass = judianClass,
                    phase = phase,
                )
            }
        if (count == 0) {
            FeatureHooks.w("Auto-update core hook skipped: no blockable update method on ${gClass.name}")
        }
        return count
    }

    private fun installAutoUpdateBlock(
        module: HookModule,
        method: Method,
        callbackClass: Class<*>,
        phase: String,
    ): Int {
        val methodKey = AutoUpdateSuppressionPolicy.hookKey(method)
        val id = "dialog.update.autoUpdate.$methodKey"
        val installKey = "$id:${method.toGenericString()}"
        val success = FeatureHooks.hookAround(
            module = module,
            executable = method,
            feature = "p1.autoUpdate.$id:${method.ownerLabel()}.${method.name}",
            id = "qdhelper.p1.dialog.auto_update_$methodKey",
        ) { chain ->
            val callback = AutoUpdateSuppressionPolicy.callbackArgument(method, callbackClass, chain.args)
            val contexts = AutoUpdateSuppressionPolicy.contexts(chain.thisObject, chain.args, callback)
            val settingsContext = contexts.firstOrNull() ?: HostSessionStore.contextOrNull()
            if (!SettingsRuntime.isUpdateCheckSuppressed(settingsContext) ||
                AutoUpdateSuppressionPolicy.isAboutManualContext(contexts)
            ) {
                return@hookAround chain.proceed()
            }
            logHit(id, installKey, "short-circuit-${method.returnType.simpleName}")
            AutoUpdateSuppressionPolicy.blockedResult(method)
        }
        if (!success) return 0

        FeatureHooks.i(
            "P1 auto-update block installed $id: ${method.ownerLabel()}.${method.name}, phase=$phase",
        )
        return 1
    }

    private fun installUpdatePresenter(module: HookModule, classLoader: ClassLoader, phase: String): Int {
        val anchor = HostTargetResolver.resolveEarlyMethod(
            context = HostSessionStore.contextOrNull(),
            classLoader = classLoader,
            processName = HostSessionStore.processName,
            key = DialogTargetKeys.updatePresenter,
        ) ?: run {
            FeatureHooks.w("Update presentation gate unavailable: phase=$phase")
            return 0
        }
        val methods = FeatureHooks.allDeclaredMethods(anchor.declaringClass)
        val queued = AutoUpdateSuppressionPolicy.presentationMethods(methods)
        if (queued.size != 2) {
            FeatureHooks.w("Update presentation contract mismatch: candidates=" + queued.size)
            return 0
        }
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        return (queued + anchor).count { method ->
            val hookKey = AutoUpdateSuppressionPolicy.hookKey(method)
            FeatureHooks.hookAround(module, method, "dialog.update.presentation", "qdhelper.update.presentation.$hookKey") { chain ->
                val contexts = AutoUpdateSuppressionPolicy.contexts(chain.thisObject, chain.args, chain.thisObject)
                if (!SettingsRuntime.isUpdateCheckSuppressed(contexts.firstOrNull() ?: HostSessionStore.contextOrNull()) ||
                    AutoUpdateSuppressionPolicy.isAboutManualContext(contexts)
                ) {
                    return@hookAround chain.proceed()
                }
                logHit("dialog.update.presentation", hookKey, "suppress-presentation")
                // The queued wrapper marks the dialog active after this factory returns.
                // Continue on the next main-loop turn, just as its dismiss listener would.
                chain.args.filterIsInstance<Runnable>().singleOrNull()?.let { completion ->
                    handler.post {
                        runCatching { completion.run() }.onFailure {
                            FeatureHooks.w("Update dialog queue completion failed: " + it.javaClass.simpleName)
                        }
                    }
                }
                null
            }
        }.also { FeatureHooks.i("Update presentation gates installed: count=$it, phase=$phase") }
    }

    private fun installMainNotificationNoOp(
        module: HookModule,
        classLoader: ClassLoader,
        phase: String,
    ): Int {
        val clazz = FeatureHooks.findClassOrNull(QDReaderHost.Classes.MAIN_GROUP_ACTIVITY, classLoader) ?: return 0
        val method = FeatureHooks.findDeclaredMethodOrNull(
            clazz,
            "checkNotificationPermission",
            String::class.java,
            Long::class.javaPrimitiveType ?: Long::class.java,
            Int::class.javaPrimitiveType ?: Int::class.java,
        ) ?: return 0
        return installNoOp(
            module = module,
            id = "notification.permission.main",
            method = method,
            hookId = "qdhelper.p1.notification.main",
            phase = phase,
            shouldBlock = SettingsRuntime::isNotificationPermissionSuppressed,
        )
    }

    private fun installNotificationDialogRequestNoOp(
        module: HookModule,
        classLoader: ClassLoader,
        phase: String,
    ): Int {
        val clazz = FeatureHooks.findClassOrNull(QDReaderHost.Classes.NOTIFICATION_PERMISSION_UTIL, classLoader)
        val method = HostTargetResolver.resolveMethod(
            context = HostSessionStore.contextOrNull(),
            classLoader = classLoader,
            processName = HostSessionStore.processName,
            key = DialogTargetKeys.notificationRequest,
        )
            ?: return 0
        return installNoOp(
            module = module,
            id = "notification.permission.dialogRequest",
            method = method,
            hookId = "qdhelper.p1.notification.dialog_request",
            phase = phase,
            shouldBlock = SettingsRuntime::isNotificationPermissionSuppressed,
        )
    }

    private fun installNotificationSceneGate(
        module: HookModule,
        classLoader: ClassLoader,
        phase: String,
    ): Int {
        val clazz = FeatureHooks.findClassOrNull(QDReaderHost.Classes.NOTIFICATION_PERMISSION_UTIL, classLoader)
        val rxActivityClass = FeatureHooks.findClassOrNull(QDReaderHost.Classes.RX_APP_COMPAT_ACTIVITY, classLoader) ?: return 0
        val method = HostTargetResolver.resolveMethod(
            context = HostSessionStore.contextOrNull(),
            classLoader = classLoader,
            processName = HostSessionStore.processName,
            key = DialogTargetKeys.notificationSceneGuide,
        )
            ?: return 0
        return installBooleanFalse(
            module = module,
            id = "notification.permission.sceneGuide",
            method = method,
            hookId = "qdhelper.p1.notification.scene_guide",
            phase = phase,
            shouldBlock = SettingsRuntime::isNotificationPermissionSuppressed,
        )
    }

    private fun installPersonalRecommendPushNoOp(
        module: HookModule,
        classLoader: ClassLoader,
        phase: String,
    ): Int {
        val clazz = FeatureHooks.findClassOrNull(QDReaderHost.Classes.NOTIFICATION_PERMISSION_UTIL, classLoader) ?: return 0
        val pushViewClass = FeatureHooks.findClassOrNull(QDReaderHost.Classes.PERSONAL_RECOMMEND_PUSH_VIEW, classLoader) ?: return 0
        val callbackClassName = "${QDReaderHost.Classes.PERSONAL_RECOMMEND_PUSH_VIEW}\$search"
        val method = FeatureHooks.allDeclaredMethods(clazz).singleOrNull { candidate ->
            Modifier.isStatic(candidate.modifiers) &&
                candidate.returnType == Void.TYPE &&
                candidate.parameterTypes.size == 4 &&
                android.app.Activity::class.java.isAssignableFrom(candidate.parameterTypes[0]) &&
                candidate.parameterTypes[1] == String::class.java &&
                candidate.parameterTypes[2] == pushViewClass &&
                candidate.parameterTypes[3].name == callbackClassName
        } ?: return 0
        return installNoOp(
            module = module,
            id = "notification.permission.personalRecommendPush",
            method = method,
            hookId = "qdhelper.p1.notification.personal_recommend_push",
            phase = phase,
            shouldBlock = SettingsRuntime::isNotificationPermissionSuppressed,
        )
    }

    private fun installTeenagerEnterDialogGate(
        module: HookModule,
        classLoader: ClassLoader,
        phase: String,
    ): Int {
        val clazz = FeatureHooks.findClassOrNull(QDReaderHost.Classes.QD_TEENAGER_MANAGER, classLoader) ?: return 0
        val baseActivityClass = FeatureHooks.findClassOrNull(QDReaderHost.Classes.BASE_ACTIVITY, classLoader) ?: return 0
        val method = FeatureHooks.findDeclaredMethodOrNull(clazz, "showEnterDialog", baseActivityClass) ?: return 0
        return installBooleanFalse(
            module = module,
            id = "teenager.manager.enterDialog",
            method = method,
            hookId = "qdhelper.p1.teenager.enter_dialog",
            phase = phase,
            shouldBlock = SettingsRuntime::isTeenagerEnterDialogSuppressed,
        )
    }

    private fun installNoOp(
        module: HookModule,
        id: String,
        method: Method,
        hookId: String,
        phase: String,
        shouldBlock: (Context?) -> Boolean,
    ): Int {
        val installKey = "$id:${method.toGenericString()}"
        val success = FeatureHooks.hookAround(
            module = module,
            executable = method,
            feature = "p1.noop.$id:${method.ownerLabel()}.${method.name}",
            id = hookId,
        ) { chain ->
            val context = AutoUpdateSuppressionPolicy.contexts(chain.thisObject, chain.args, null).firstOrNull()
                ?: HostSessionStore.contextOrNull()
            if (!shouldBlock(context)) {
                return@hookAround chain.proceed()
            }
            logHit(id, installKey, "short-circuit")
            null
        }
        if (!success) {
            return 0
        }

        FeatureHooks.i("P1 no-op installed $id: ${method.ownerLabel()}.${method.name}, phase=$phase")
        return 1
    }

    private fun installBooleanFalse(
        module: HookModule,
        id: String,
        method: Method,
        hookId: String,
        phase: String,
        shouldBlock: (Context?) -> Boolean,
    ): Int {
        val installKey = "$id:${method.toGenericString()}"
        val success = FeatureHooks.hookAround(
            module = module,
            executable = method,
            feature = "p1.booleanFalse.$id:${method.ownerLabel()}.${method.name}",
            id = hookId,
        ) { chain ->
            val context = AutoUpdateSuppressionPolicy.contexts(chain.thisObject, chain.args, null).firstOrNull()
                ?: HostSessionStore.contextOrNull()
            if (!shouldBlock(context)) {
                return@hookAround chain.proceed()
            }
            logHit(id, installKey, "return-false")
            false
        }
        if (!success) {
            return 0
        }

        FeatureHooks.i("P1 boolean gate installed $id: ${method.ownerLabel()}.${method.name}, phase=$phase")
        return 1
    }

    private fun logHit(id: String, installKey: String, action: String) {
        val count = hitCounters.computeIfAbsent(installKey) { AtomicLong(0L) }.incrementAndGet()
        if (count <= 20L || count % 50L == 0L) {
            FeatureHooks.i("P1 hit $id#$count: action=$action, thread=${Thread.currentThread().name}")
        }
    }

    private fun Method.ownerLabel(): String {
        return QDReaderHost.classLabel(declaringClass.name)
    }

    private fun isNotificationDialogRequestMethod(method: Method): Boolean {
        val params = method.parameterTypes
        val callback = params.getOrNull(5)
        return Modifier.isPublic(method.modifiers) &&
            Modifier.isStatic(method.modifiers) &&
            method.returnType == Void.TYPE &&
            params.size == 6 &&
            params[0].name == QDReaderHost.Classes.QD_DIALOG_MANAGER &&
            params[1].name == QDReaderHost.Classes.RX_APP_COMPAT_ACTIVITY &&
            params[2].name == QDReaderHost.Classes.COMMON_LIB_DIALOG_CALLBACK &&
            params[3] == String::class.java &&
            params[4] == Int::class.javaPrimitiveType &&
            callback?.isInterface == true &&
            callback.enclosingClass?.name == QDReaderHost.Classes.NOTIFICATION_PERMISSION_UTIL &&
            FeatureHooks.allDeclaredMethods(callback).let { methods ->
                methods.size == 3 && methods.all { candidate ->
                    Modifier.isAbstract(candidate.modifiers) &&
                        candidate.returnType == Void.TYPE &&
                        candidate.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType))
                }
            }
    }

    private fun isNotificationSceneGuideMethod(method: Method, rxActivityClass: Class<*>): Boolean {
        val params = method.parameterTypes
        return Modifier.isPublic(method.modifiers) &&
            Modifier.isStatic(method.modifiers) &&
            method.returnType == Boolean::class.javaPrimitiveType &&
            params.size == 4 &&
            params[0] == Int::class.javaPrimitiveType &&
            params[1] == rxActivityClass &&
            params[2] == Long::class.javaPrimitiveType &&
            params[3] == String::class.java
    }
}
