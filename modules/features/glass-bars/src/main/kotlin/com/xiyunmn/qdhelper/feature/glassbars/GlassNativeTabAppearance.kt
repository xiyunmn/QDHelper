package com.xiyunmn.qdhelper.feature.glassbars

import android.graphics.drawable.Drawable
import android.widget.ImageView
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.feature.api.runtime.HookModule
import com.xiyunmn.qdhelper.foundation.runtime.HostSessionStore
import com.xiyunmn.qdhelper.host.api.target.HostTargetResolver
import com.xiyunmn.qdhelper.host.api.target.NavigationTargetKeys
import com.xiyunmn.qdhelper.shared.ui.UiColors
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.WeakHashMap

/** Read native local artwork without changing a live icon, its PAG sibling, or the selected page. */
internal object GlassNativeTabAppearance {
    private class Style(strip: Any, model: Any, val selected: Boolean) {
        val strip = WeakReference(strip)
        val model = WeakReference(model)
        var theme: Pair<Boolean, Int>? = null
        var drawable: Drawable? = null
    }
    private val styles = WeakHashMap<ImageView, Style>()
    private var renderer: Method? = null
    private var probing = false

    fun install(module: HookModule, classLoader: ClassLoader) {
        if (renderer != null) return
        val method = HostTargetResolver.resolveMethod(HostSessionStore.contextOrNull(), classLoader,
            HostSessionStore.processName, NavigationTargetKeys.renderIcon) ?: return
        if (module.hookAfter(method, "GlassBarsFeature", "glass-bars.native-icon") { chain, _ ->
            if (!probing) {
                val icon = chain.getArg(0) as? ImageView
                val strip = chain.thisObject
                val model = chain.getArg(3)
                if (icon != null && strip != null && model != null) {
                    styles[icon] = Style(strip, model, chain.getArg(2) == true)
                }
            }
        }) renderer = method
    }

    fun selectedArtwork(icon: ImageView): Drawable? {
        val style = styles[icon] ?: return null
        // Keep the actual selected icon, including scroll-to-top and ongoing host animation.
        if (style.selected) return null
        val theme = UiColors.IsNight to UiColors.Brand
        if (style.theme == theme) return style.drawable
        style.theme = theme
        style.drawable = null
        val strip = style.strip.get() ?: return null
        val model = style.model.get() ?: return null
        val method = renderer ?: return null
        probing = true
        try {
            style.drawable = loadLocalSelectedArtwork(icon, model) { probe, local ->
                method.invoke(strip, probe, false, true, local, null)
            }
        } catch (error: Exception) {
            FeatureHooks.w("Glass selected artwork unavailable: ${error.javaClass.simpleName}")
        } finally { probing = false }
        return style.drawable
    }

    /** The audited maintab model is a plain Object with int resource IDs and reference fields.
     * Clear references so this detached render cannot load URLs, run callbacks or start PAG.
     * No obfuscated getter names or hard-coded resource IDs are used.
     */
    internal fun loadLocalSelectedArtwork(icon: ImageView, model: Any, render: (ImageView, Any) -> Unit): Drawable? {
        val type = model.javaClass
        if (type.superclass != Any::class.java) return null
        val constructor = FeatureHooks.findConstructorOrNull(type) ?: return null
        val local = FeatureHooks.newInstance(constructor) ?: return null
        for (field in FeatureHooks.allDeclaredFields(type).filterNot { Modifier.isStatic(it.modifiers) }) {
            when {
                field.type == Int::class.javaPrimitiveType -> {
                    val value = FeatureHooks.read(field, model) as? Int ?: return null
                    if (!FeatureHooks.write(field, local, value)) return null
                }
                !field.type.isPrimitive -> if (!FeatureHooks.write(field, local, null)) return null
                else -> return null // Unknown model shape: keep native rendering.
            }
        }
        val probe = ImageView(icon.context)
        return try {
            render(probe, local)
            probe.drawable?.constantState?.newDrawable(icon.resources)?.mutate()
        } finally { probe.setImageDrawable(null) }
    }

    fun clear() { styles.clear(); renderer = null; probing = false }
}
