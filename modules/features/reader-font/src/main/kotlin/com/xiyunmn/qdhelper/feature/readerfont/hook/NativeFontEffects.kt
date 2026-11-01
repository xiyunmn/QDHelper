package com.xiyunmn.qdhelper.feature.readerfont.hook

import android.graphics.Paint
import android.graphics.Typeface
import com.xiyunmn.qdhelper.config.FontEffectScope
import com.xiyunmn.qdhelper.config.FontEffectSettings
import com.xiyunmn.qdhelper.config.SettingsRuntime
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureResources
import com.xiyunmn.qdhelper.feature.api.runtime.HookModule
import com.xiyunmn.qdhelper.foundation.runtime.HostSessionStore
import java.util.WeakHashMap

/** Only role-isolated Typefaces supplied by this feature participate; ordinary host/UI paints do not. */
internal object NativeFontEffects {
    private data class Effect(val scope: FontEffectScope, val values: FontEffectSettings)
    private data class Original(var size: Float, var width: Float, var spacing: Float)
    private val lock = Any()
    private val faces = WeakHashMap<Typeface, Effect>()
    private val paints = WeakHashMap<Paint, Original>()
    private val internalWrite = ThreadLocal.withInitial { false }
    private var installed = false
    private var ready = false

    fun register(face: Typeface, scope: FontEffectScope, values: FontEffectSettings) = synchronized(lock) {
        if (ready) faces[face] = Effect(scope, values)
    }

    fun install(module: HookModule): Int = synchronized(lock) {
        if (installed) return 0
        val context = HostSessionStore.contextOrNull() ?: return 0
        if (FontEffectScope.entries.none { SettingsRuntime.fontEffects(it, context).changesMetrics }) return 0
        installed = true
        var count = 0
        fun after(name: String, vararg types: Class<*>, action: (Paint, List<Any?>) -> Unit) {
            if (FeatureHooks.hookAfter(module, Paint::class.java.getDeclaredMethod(name, *types),
                    "NativeFontEffects", "fontEffects.$name") { chain, _ ->
                    if (internalWrite.get() != true) synchronized(lock) {
                        val paint = chain.thisObject as? Paint ?: return@synchronized
                        guarded { action(paint, chain.args) }
                    }
                }) count++
        }
        after("setTypeface", Typeface::class.java) { paint, _ -> update(paint) }
        after("set", Paint::class.java) { paint, args -> copied(paint, args[0] as Paint) }
        if (FeatureHooks.hookAfter(module, Paint::class.java.getConstructor(Paint::class.java),
                "NativeFontEffects", "fontEffects.copy") { chain, _ ->
                if (internalWrite.get() != true) synchronized(lock) {
                    guarded { copied(chain.thisObject as Paint, chain.args[0] as Paint) }
                }
            }) count++
        if (FeatureHooks.hookAround(module, Paint::class.java.getDeclaredMethod("reset"),
                "NativeFontEffects", "fontEffects.reset") { chain ->
                synchronized(lock) {
                    paints.remove(chain.thisObject as? Paint)
                    guarded { chain.proceed() }
                }
            }) count++
        listOf("TextSize", "TextScaleX", "LetterSpacing").forEachIndexed { index, name ->
            if (FeatureHooks.hookAround(module, Paint::class.java.getDeclaredMethod("get$name"),
                    "NativeFontEffects", "fontEffects.get$name") { chain ->
                    if (internalWrite.get() == true) chain.proceed() else synchronized(lock) {
                        val paint = chain.thisObject as Paint
                        guarded {
                            val effect = effect(paint)
                            if (effect == null) {
                                restore(paint)
                                chain.proceed()
                            } else {
                                val original = original(paint)
                                // Return logical values so relative spans and temporary scaling cannot compound effects.
                                when (index) { 0 -> original.size; 1 -> original.width; else -> original.spacing }
                            }
                        }
                    }
                }) count++
            if (FeatureHooks.hookAround(module, Paint::class.java.getDeclaredMethod("set$name", Float::class.javaPrimitiveType),
                    "NativeFontEffects", "fontEffects.set$name") { chain ->
                    if (internalWrite.get() == true) chain.proceed() else synchronized(lock) {
                        guarded {
                            val paint = chain.thisObject as Paint
                            val effect = effect(paint)
                            val value = chain.args[0] as Float
                            if (effect == null || !value.isFinite()) {
                                restore(paint)
                                chain.proceed()
                            } else {
                                val original = original(paint)
                                val adjusted = when (index) {
                                    0 -> { original.size = value; value * effect.values.sizeScale }
                                    1 -> { original.width = value; value * effect.values.widthScale }
                                    else -> { original.spacing = value; value + effect.values.letterSpacing }
                                }
                                chain.proceed(arrayOf(adjusted))
                            }
                        }
                    }
                }) count++
        }
        // Android creates style variants for spans; carry the same role without touching other font families.
        listOf(arrayOf(Typeface::class.java, Int::class.javaPrimitiveType!!),
            arrayOf(Typeface::class.java, Int::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!))
            .forEach { types ->
                if (FeatureHooks.hookAfter(module, Typeface::class.java.getDeclaredMethod("create", *types),
                        "NativeFontEffects", "fontEffects.variant.${types.size}") { chain, result ->
                        synchronized(lock) {
                            val effect = faces[chain.args[0] as? Typeface]
                            if (effect != null && result is Typeface) faces[result] = effect
                        }
                    }) count++
            }
        ready = count == 12
        if (!ready) FeatureHooks.w("Native font metrics unavailable: installed=$count/12")
        FeatureResources.register("NativeFontEffects") {
            synchronized(lock) {
                guarded { paints.keys.toList().forEach(::restore) }
                faces.clear()
                installed = false
                ready = false
            }
        }
        count
    }

    private fun effect(paint: Paint): Effect? = faces[paint.typeface]?.takeIf {
        if (it.scope == FontEffectScope.Reader) SettingsRuntime.isCustomReaderFontEnabled()
        else SettingsRuntime.isCustomGlobalFontEnabled()
    }

    private fun original(paint: Paint): Original = paints.getOrPut(paint) {
        Original(paint.textSize, paint.textScaleX, paint.letterSpacing)
    }

    private fun update(paint: Paint) {
        val values = effect(paint)?.values ?: run { restore(paint); return }
        val original = original(paint)
        paint.textSize = original.size * values.sizeScale
        paint.textScaleX = original.width * values.widthScale
        paint.letterSpacing = original.spacing + values.letterSpacing
    }

    private fun copied(target: Paint, source: Paint) {
        if (target === source) return
        paints.remove(target)
        paints[source]?.let { paints[target] = it.copy() }
        // Native copies already contain adjusted metrics; retain their logical originals, never multiply again.
        if (effect(target) == null) restore(target)
    }

    private fun restore(paint: Paint) {
        val original = paints.remove(paint) ?: return
        paint.textSize = original.size
        paint.textScaleX = original.width
        paint.letterSpacing = original.spacing
    }

    private inline fun <T> guarded(block: () -> T): T {
        val previous = internalWrite.get() == true
        internalWrite.set(true)
        return try { block() } finally { internalWrite.set(previous) }
    }
}
