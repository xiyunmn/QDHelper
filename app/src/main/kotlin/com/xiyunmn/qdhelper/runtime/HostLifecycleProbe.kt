package com.xiyunmn.qdhelper.runtime

import android.app.Application
import android.content.Context
import com.xiyunmn.qdhelper.hook.framework.FrameworkReflection
import com.xiyunmn.qdhelper.hook.runtime.HookRuntime
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols as QDReaderHost
import io.github.libxposed.api.XposedModule
import java.util.concurrent.atomic.AtomicBoolean

object HostLifecycleProbe {
    private val installed = AtomicBoolean(false)

    fun install(
        module: XposedModule,
        classLoader: ClassLoader,
        processName: String,
        onApplicationReady: (Application, String) -> Unit,
    ) {
        if (!installed.compareAndSet(false, true)) {
            return
        }

        installApplicationAttachProbe(module, processName)
        installApplicationOnCreateProbe(module, classLoader, processName, onApplicationReady)
    }

    private fun installApplicationAttachProbe(module: XposedModule, processName: String) {
        val attach = FrameworkReflection.declaredMethod(
            Application::class.java,
            "attach",
            Context::class.java,
        )
        HookRuntime.hookAround(
            module = module,
            executable = attach,
            feature = "lifecycle.Application.attach",
        ) { chain ->
            val applicationName = chain.thisObject.javaClass.name
            val baseContext = chain.getArg(0) as? Context
            if (baseContext != null) {
                HostApplicationInitializer.initialize(baseContext, processName, "Application.attach:${QDReaderHost.classLabel(applicationName)}")
            }
            HookRuntime.i("Application.attach begin: ${QDReaderHost.classLabel(applicationName)}")
            val result = chain.proceed()
            HookRuntime.i("Application.attach end: ${QDReaderHost.classLabel(applicationName)}")
            result
        }
    }

    private fun installApplicationOnCreateProbe(
        module: XposedModule,
        classLoader: ClassLoader,
        processName: String,
        onApplicationReady: (Application, String) -> Unit,
    ) {
        val onCreate = FrameworkReflection.declaredMethod(Application::class.java, "onCreate")
        HookRuntime.hookAround(
            module = module,
            executable = onCreate,
            feature = "lifecycle.Application.onCreate",
        ) { chain ->
            val application = chain.thisObject as? Application
            val applicationName = chain.thisObject.javaClass.name
            HookRuntime.i("Application.onCreate begin: ${QDReaderHost.classLabel(applicationName)}")
            val result = chain.proceed()
            if (application != null) {
                HostApplicationInitializer.initialize(application, processName, "Application.onCreate:${QDReaderHost.classLabel(applicationName)}")
                HostApplicationInitializer.markApplication(application, "Application.onCreate")
                onApplicationReady(application, application.javaClass.name)
            } else {
                HookRuntime.w("Application.onCreate receiver is not an Application: $applicationName")
            }
            HookRuntime.i(
                "Application.onCreate end: ${QDReaderHost.classLabel(applicationName)}, " +
                    "classLoader=$classLoader",
            )
            result
        }
    }

}
