package com.xiyunmn.qdhelper.feature.glassbars

import com.xiyunmn.qdhelper.feature.api.runtime.HookModule
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class GlassApiCompatibilityTest {
    @Test
    fun android9DoesNotInstallHooksOrInitializeTheGlassRenderer() {
        val loader = javaClass.classLoader!!
        val module = Proxy.newProxyInstance(loader, arrayOf(HookModule::class.java)) { _, method, _ ->
            throw AssertionError("Glass must not touch the host on Android 9: ${method.name}")
        } as HookModule
        GlassBarsFeature.install(module, RuntimeEnvironment.getApplication().classLoader, HostSymbols.PACKAGE, "test")
    }
}
