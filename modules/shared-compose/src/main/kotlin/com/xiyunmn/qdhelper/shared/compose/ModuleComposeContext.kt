package com.xiyunmn.qdhelper.shared.compose

import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.view.LayoutInflater

/** UI resources are module-owned; window services and attribution remain host-owned.
 * Never pass this context to business controllers or storage APIs.
 */
class ModuleComposeContext private constructor(
    host: Context,
    private val moduleResources: Resources,
) : ContextWrapper(host) {
    private val uiTheme = moduleResources.newTheme().apply {
        applyStyle(android.R.style.Theme_Material_Light_NoActionBar, true)
    }
    override fun getResources(): Resources = moduleResources
    override fun getAssets(): AssetManager = resources.assets
    override fun getTheme(): Resources.Theme = uiTheme
    override fun setTheme(resid: Int) { uiTheme.applyStyle(resid, true) }
    override fun getClassLoader(): ClassLoader = ModuleComposeContext::class.java.classLoader!!
    override fun getSystemService(name: String): Any? = if (name == LAYOUT_INFLATER_SERVICE) {
        LayoutInflater.from(baseContext).cloneInContext(this)
    } else super.getSystemService(name)

    override fun createConfigurationContext(overrideConfiguration: Configuration): Context =
        ModuleComposeContext(baseContext, Resources(moduleResources.assets, moduleResources.displayMetrics, overrideConfiguration))

    companion object {
        fun create(activity: Context): ModuleComposeContext {
            // Package-name lookup is filtered by the host's package visibility. LSPosed already
            // supplies the verified module ApplicationInfo, including its current APK/split paths.
            val info = checkNotNull(ModuleComposeEnvironment.applicationInfo) { "Module resource environment is not configured" }
            val source = activity.packageManager.getResourcesForApplication(info)
            val resources = Resources(source.assets, activity.resources.displayMetrics, Configuration(activity.resources.configuration))
            return ModuleComposeContext(activity, resources).also {
                check(it.getString(R.string.qdhelper_compose_resource_identity) == "QDHelper.Compose.Resources") {
                    "Module Compose resources are unavailable"
                }
            }
        }
    }
}
