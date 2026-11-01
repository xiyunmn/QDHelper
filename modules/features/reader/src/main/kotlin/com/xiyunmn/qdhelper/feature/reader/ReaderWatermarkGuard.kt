package com.xiyunmn.qdhelper.feature.reader

import android.content.Context
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import com.xiyunmn.qdhelper.config.ConfigRuntime
import com.xiyunmn.qdhelper.config.SettingIds
import com.xiyunmn.qdhelper.config.SettingsRuntime
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureResources
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureRuntimeServices
import com.xiyunmn.qdhelper.feature.api.runtime.HookModule
import com.xiyunmn.qdhelper.foundation.runtime.HostSessionStore
import com.xiyunmn.qdhelper.foundation.runtime.RuntimeCapabilities
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/** The Fock overlay and the two reader headers are independent producers. */
internal object ReaderWatermarkGuard {
    private val main = Handler(Looper.getMainLooper())
    private val headers = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<View, Boolean>()))
    private val overlays = Collections.synchronizedMap(WeakHashMap<View, Int>())
    private val barcodeViews = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<ImageView, Boolean>()))
    private val hiddenBarcodes = Collections.synchronizedMap(WeakHashMap<ImageView, Int>())
    private val hits = ConcurrentHashMap.newKeySet<String>()
    private val visibilityWrite = ThreadLocal.withInitial { false }
    private var settings: AutoCloseable? = null
    private var cleanupRegistered = false
    private var readerClass: Class<*>? = null
    private var overlayClass: Class<*>? = null
    private var overlayField: Field? = null
    private var headerClasses: List<Class<*>> = emptyList()
    @Volatile private var barcodeId = 0
    private val refresh = Runnable {
        runCatching {
            val enabled = enabled()
            if (enabled) collectExistingViews()
            synchronized(headers) { headers.toList() }.forEach { clearBarcode(it, enabled) }
            synchronized(overlays) { overlays.keys.toList() }.forEach { applyOverlay(it, enabled) }
        }.onFailure { cleanupFailure(it) }
    }

    fun install(module: HookModule, loader: ClassLoader, process: String, phase: String) {
        if (!HostSymbols.isMainProcess(process)) return
        val context = HostSessionStore.contextOrNull()
        if (context != null) {
            barcodeId = context.resources.getIdentifier("barCodeView", "id", HostSymbols.PACKAGE)
            if (settings == null) settings = ConfigRuntime.observeUserChanges(context,
                setOf(SettingIds.KEY_MODULE_ENABLED, SettingIds.KEY_DISABLE_READER_WATERMARK)) {
                main.removeCallbacks(refresh)
                main.post(refresh)
            }
        }
        if (!cleanupRegistered) {
            cleanupRegistered = true
            FeatureResources.register("ReaderWatermarkGuard", ::clear)
        }
        val installed = linkedMapOf<String, Boolean>()
        val activity = FeatureHooks.findClassOrNull(HostSymbols.Classes.QD_READER_ACTIVITY, loader)
        readerClass = activity
        installed["entry"] = around(module, activity?.let { voidMethod(it, "setWaterMark") }, "entry") { chain ->
            if (!enabled()) chain.proceed() else { hit("entry"); null }
        }
        val overlay = FeatureHooks.findClassOrNull(HostSymbols.Classes.FOCK_WATERMARK_VIEW, loader)
        overlayClass = overlay
        overlayField = activity?.let { FeatureHooks.allDeclaredFields(it) }
            ?.singleOrNull { overlay != null && it.type == overlay && !Modifier.isStatic(it.modifiers) }
        headerClasses = listOf(HostSymbols.Classes.READ_HEAD_VIEW, HostSymbols.Classes.SCROLL_FLIP_HEAD_VIEW)
            .mapNotNull { FeatureHooks.findClassOrNull(it, loader) }
        installed["overlay.draw"] = around(module, overlay?.let { voidMethod(it, "onDraw", Canvas::class.java) }, "overlay.draw") { chain ->
            if (!enabled()) chain.proceed() else { hit("overlay.draw"); null }
        }
        installed["view.draw"] = around(module, View::class.java.getDeclaredMethod("draw", Canvas::class.java), "view.draw") { chain ->
            val view = chain.thisObject
            val protected = overlay?.isInstance(view) == true || view is ImageView && barcodeViews.contains(view)
            if (protected && enabled()) { hit("view.draw"); null } else chain.proceed()
        }
        installed["overlay.constructor"] = overlay?.let {
            FeatureHooks.hookAfter(module, FeatureHooks.findConstructorOrNull(it, Context::class.java,
                String::class.java, Boolean::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!), "reader.watermark.overlay", "qdhelper.watermark.overlay.construct") { chain, _ ->
                (chain.thisObject as? View)?.let { view ->
                    synchronized(overlays) { overlays.putIfAbsent(view, view.visibility) }
                    applyOverlay(view, enabled())
                }
            }
        } == true
        // Visibility also removes an already-recorded render node, including a protected background.
        installed["overlay.visibility"] = overlay != null && around(module,
            View::class.java.getDeclaredMethod("setVisibility", Int::class.javaPrimitiveType), "overlay.visibility") { chain ->
            val view = chain.thisObject as? View
            val isOverlay = view != null && overlay.isInstance(view)
            val isBarcode = view is ImageView && barcodeViews.contains(view)
            if (view == null || (!isOverlay && !isBarcode) || visibilityWrite.get() == true) chain.proceed()
            else {
                val requested = chain.args[0] as Int
                val block = enabled()
                if (isOverlay) overlays[view] = requested
                else if (block) hiddenBarcodes[view as ImageView] = requested
                if (block && requested == View.VISIBLE) chain.proceed(arrayOf(View.INVISIBLE)) else chain.proceed()
            }
        }
        installHeader(module, loader, HostSymbols.Classes.READ_HEAD_VIEW, "page", arrayOf(String::class.java), installed)
        installHeader(module, loader, HostSymbols.Classes.SCROLL_FLIP_HEAD_VIEW, "scroll", emptyArray(), installed)
        installed["barcode.resource"] = barcodeId != 0
        installed["settings.observer"] = settings != null
        val missing = installed.filterValues { !it }.keys
        val detail = if (missing.isEmpty()) "入口、Fock绘制、两种页眉及已有标记清理均已安装"
            else "未完整覆盖：${missing.joinToString()}"
        if (RuntimeCapabilities.record("reader.watermark", "阅读页去水印", missing.isEmpty(), detail)) {
            if (missing.isEmpty()) FeatureHooks.i("Reader watermark protection installed: phase=$phase")
            else FeatureHooks.w("Reader watermark protection incomplete: phase=$phase, missing=${missing.joinToString()}")
        }
        main.removeCallbacks(refresh)
        main.post(refresh)
    }

    private fun installHeader(module: HookModule, loader: ClassLoader, name: String, id: String,
        parameters: Array<Class<*>>, installed: MutableMap<String, Boolean>) {
        val owner = FeatureHooks.findClassOrNull(name, loader)
        installed["$id.generate"] = around(module, owner?.let { voidMethod(it, "showBarCode", *parameters) }, "$id.generate") { chain ->
            val view = chain.thisObject as? View
            if (view != null) headers.add(view)
            if (!enabled()) chain.proceed() else {
                view?.let { clearBarcode(it, true) }
                hit("$id.generate")
                null
            }
        }
        installed["$id.bind"] = FeatureHooks.hookAfter(module, owner?.let { voidMethod(it, "setupHeadView") },
            "reader.watermark.$id", "qdhelper.watermark.$id.bind") { chain, _ ->
            (chain.thisObject as? View)?.let { view ->
                headers.add(view)
                clearBarcode(view, enabled())
            }
        }
        if (id == "scroll") installed["scroll.progress"] = FeatureHooks.hookAfter(module,
            owner?.let { voidMethod(it, "updateReadPer", Float::class.javaPrimitiveType!!) },
            "reader.watermark.scroll", "qdhelper.watermark.scroll.progress") { chain, _ ->
            (chain.thisObject as? View)?.let { view ->
                headers.add(view)
                clearBarcode(view, enabled())
            }
        }
    }

    private fun clearBarcode(header: View, enabled: Boolean) {
        if (barcodeId == 0) return
        runCatching {
            val image = header.findViewById<View>(barcodeId) as? ImageView ?: return
            barcodeViews.add(image)
            val previous = visibilityWrite.get() == true
            visibilityWrite.set(true)
            try {
                if (enabled) {
                    synchronized(hiddenBarcodes) { hiddenBarcodes.putIfAbsent(image, image.visibility) }
                    image.setImageDrawable(null)
                    image.visibility = View.INVISIBLE
                    image.invalidate()
                } else hiddenBarcodes.remove(image)?.let { image.visibility = it }
            } finally { visibilityWrite.set(previous) }
        }.onFailure { cleanupFailure(it) }
    }

    /** One pass when enabling/reloading, confined to already-open reader windows. */
    private fun collectExistingViews() {
        val owner = readerClass ?: return
        FeatureRuntimeServices.activityLifecycle?.currentActivities().orEmpty()
            .filter { owner.isInstance(it) && !it.isDestroyed }.forEach { activity ->
                overlayField?.let { field ->
                    (FeatureHooks.read(field, activity) as? View)?.let { view ->
                        synchronized(overlays) { overlays.putIfAbsent(view, view.visibility) }
                    }
                }
                val pending = ArrayDeque<View>()
                pending.add(activity.window.decorView)
                while (pending.isNotEmpty()) {
                    val view = pending.removeLast()
                    if (headerClasses.any { it.isInstance(view) }) headers.add(view)
                    if (overlayClass?.isInstance(view) == true) {
                        synchronized(overlays) { overlays.putIfAbsent(view, view.visibility) }
                    }
                    if (view is ViewGroup) for (index in 0 until view.childCount) pending.add(view.getChildAt(index))
                }
            }
    }

    private fun applyOverlay(view: View, enabled: Boolean) {
        val requested = overlays[view] ?: return
        val previous = visibilityWrite.get() == true
        visibilityWrite.set(true)
        try {
            view.visibility = if (enabled && requested == View.VISIBLE) View.INVISIBLE else requested
            view.invalidate()
        } finally { visibilityWrite.set(previous) }
    }

    private fun enabled() = SettingsRuntime.isReaderWatermarkBlockEnabled(HostSessionStore.contextOrNull())
    private fun hit(id: String) {
        if (hits.add(id)) FeatureHooks.i("Reader watermark blocked: $id")
    }
    private fun cleanupFailure(error: Throwable) {
        val reason = error.javaClass.simpleName
        if (RuntimeCapabilities.record("reader.watermark.cleanup", "阅读水印清理", false, "已有标记清理失败：$reason")) {
            FeatureHooks.w("Reader watermark cleanup failed: $reason")
        }
    }
    private fun voidMethod(owner: Class<*>, name: String, vararg parameters: Class<*>): Method? =
        FeatureHooks.findDeclaredMethodOrNull(owner, name, *parameters)?.takeIf { it.returnType == Void.TYPE }
    private fun around(module: HookModule, method: Executable?, id: String,
        callback: (com.xiyunmn.qdhelper.feature.api.runtime.HookChain) -> Any?): Boolean =
        FeatureHooks.hookAround(module, method, "reader.watermark.$id",
            if (id == "entry") "qdhelper.watermark.reader" else "qdhelper.watermark.$id", callback)

    private fun clear() {
        settings?.close()
        settings = null
        main.removeCallbacks(refresh)
        val oldOverlays = synchronized(overlays) { overlays.entries.map { java.lang.ref.WeakReference(it.key) to it.value }.also { overlays.clear() } }
        val oldBarcodes = synchronized(hiddenBarcodes) { hiddenBarcodes.entries.map { java.lang.ref.WeakReference(it.key) to it.value }.also { hiddenBarcodes.clear() } }
        headers.clear()
        barcodeViews.clear()
        hits.clear()
        barcodeId = 0
        readerClass = null
        overlayClass = null
        overlayField = null
        headerClasses = emptyList()
        cleanupRegistered = false
        RuntimeCapabilities.removePrefix("reader.watermark")
        main.post {
            visibilityWrite.set(true)
            try {
                (oldOverlays + oldBarcodes).forEach { (reference, visibility) -> reference.get()?.let {
                    it.visibility = visibility
                    it.invalidate()
                } }
            } finally { visibilityWrite.remove() }
        }
    }
}
