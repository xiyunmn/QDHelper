package com.xiyunmn.qdhelper.feature.glassbars

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.widget.ImageView
import com.xiyunmn.qdhelper.host.api.reflection.HostAccess
import com.xiyunmn.qdhelper.host.api.reflection.HostReflection
import com.xiyunmn.qdhelper.host.api.reflection.HostReflectionGateway
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class GlassNativeTabAppearanceTest {
    private var previousGateway: HostReflectionGateway? = null
    @Before fun reflectionGateway() {
        previousGateway = ReflectionHelpers.getStaticField(HostReflection::class.java, "gateway")
        HostReflection.configure(Proxy.newProxyInstance(javaClass.classLoader,
            arrayOf(HostReflectionGateway::class.java)) { _, method, args ->
            when (method.name) {
                "constructor" -> (args[0] as Class<*>).getDeclaredConstructor().apply { isAccessible = true }
                "newInstance" -> HostAccess.Value((args[0] as Constructor<*>).newInstance())
                "fieldsDeclaredBy" -> (args[0] as Class<*>).declaredFields.toList()
                "read" -> HostAccess.Value((args[0] as Field).apply { isAccessible = true }.get(args[1]))
                "write" -> { (args[0] as Field).apply { isAccessible = true }.set(args[1], args[2]); true }
                else -> error("Unexpected reflection call: ${method.name}")
            }
        } as HostReflectionGateway)
    }
    @After fun restoreGateway() {
        ReflectionHelpers.setStaticField(HostReflection::class.java, "gateway", previousGateway)
    }
    class Model {
        var normalResource = 12
        var selectedResource = 34
        var url: String? = "host-asset"
        var callback: (() -> Unit)? = { error("Must not run host callbacks") }
    }

    @Test fun selectedPreviewUsesDetachedLocalModelAndDoesNotMutateTheLiveIcon() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val icon = ImageView(activity.get())
            val original = ColorDrawable(Color.BLACK)
            icon.setImageDrawable(original)
            val model = Model()
            val drawable = GlassNativeTabAppearance.loadLocalSelectedArtwork(icon, model) { probe, copy ->
                assertNotSame(icon, probe)
                assertNull(probe.parent)
                assertNotSame(model, copy)
                val local = copy as Model
                assertEquals(12, local.normalResource)
                assertEquals(34, local.selectedResource)
                assertNull(local.url)
                assertNull(local.callback)
                probe.setImageDrawable(ColorDrawable(Color.RED))
            }
            assertEquals(Color.RED, (drawable as ColorDrawable).color)
            assertNull(drawable.callback)
            assertSame(original, icon.drawable)
            assertEquals("host-asset", model.url)
            assertNotNull(model.callback)
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun remoteOnlyArtworkFallsBackWithoutChangingTheNativeIcon() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val icon = ImageView(activity.get())
            val original = ColorDrawable(Color.BLUE)
            icon.setImageDrawable(original)
            val result = GlassNativeTabAppearance.loadLocalSelectedArtwork(icon,
                Model().apply { normalResource = 0; selectedResource = 0 }) { probe, copy ->
                val local = copy as Model
                assertNull(local.url)
                assertNull(local.callback)
                if (local.selectedResource > 0) probe.setImageDrawable(ColorDrawable(Color.RED))
            }
            assertNull(result)
            assertSame(original, icon.drawable)
        } finally { activity.pause().stop().destroy() }
    }
}
