package com.xiyunmn.qdhelper.feature.api.runtime

import android.content.Context
import com.qidian.QDReader.component.user.QDUserManager
import com.xiyunmn.qdhelper.host.api.reflection.HostAccess
import com.xiyunmn.qdhelper.host.api.reflection.HostReflection
import com.xiyunmn.qdhelper.host.api.reflection.HostReflectionGateway
import com.xiyunmn.qdhelper.host.api.target.AccountTargetKeys
import com.xiyunmn.qdhelper.host.api.target.HostTargetResolver
import com.xiyunmn.qdhelper.host.api.target.MethodTargetResolver
import com.xiyunmn.qdhelper.host.api.target.TargetKey
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class HostAccountTest {
    private val manager = QDUserManager.getInstance()
    private val loader = javaClass.classLoader!!
    private var available = true
    private val resolver = object : MethodTargetResolver {
        override fun resolveMethod(context: Context?, classLoader: ClassLoader, processName: String, key: TargetKey.MethodTarget, forceRescan: Boolean): Method? {
            if (!available) return null
            val name = when (key) {
                AccountTargetKeys.userManagerIsLoggedIn -> "sessionActive"
                AccountTargetKeys.userManagerUserId -> "accountId"
                else -> return null
            }
            return QDUserManager::class.java.getDeclaredMethod(name)
        }
        override fun resolveEarlyMethod(context: Context?, classLoader: ClassLoader, processName: String, key: TargetKey.MethodTarget): Method? = null
        override fun resolveMethods(context: Context?, classLoader: ClassLoader, processName: String, key: TargetKey.MethodTarget, forceRescan: Boolean): List<Method> = emptyList()
    }

    @Before fun setUp() {
        manager.loggedIn = true
        manager.id = 11L
        manager.failReadingId = false
        HostTargetResolver.configure(resolver)
        HostReflection.configure(Proxy.newProxyInstance(loader, arrayOf(HostReflectionGateway::class.java)) { _, method, args ->
            when (method.name) {
                "findClass" -> Class.forName(args[1] as String, false, args[0] as ClassLoader)
                "declaredMethod" -> (args[0] as Class<*>).getDeclaredMethod(args[1] as String)
                "invoke" -> HostAccess.Value((args[0] as Method).invoke(args[1]))
                else -> error("Unexpected host access: ${method.name}")
            }
        } as HostReflectionGateway)
    }

    @After fun tearDown() { available = false }

    @Test fun accountChangesAreReadFromTheHostEveryTime() {
        assertEquals(11L, currentHostUserId(null, loader))
        manager.id = 22L
        assertEquals(22L, currentHostUserId(null, loader))
        manager.id = 11L
        assertEquals(11L, currentHostUserId(null, loader))
    }

    @Test fun guestIsDistinctFromMissingOrInvalidAccountData() {
        manager.loggedIn = false
        assertEquals(0L, currentHostUserId(null, loader))
        manager.loggedIn = true
        manager.id = 0L
        assertNull(currentHostUserId(null, loader))
        manager.id = 11L
        manager.failReadingId = true
        assertNull(currentHostUserId(null, loader))
        manager.failReadingId = false
        available = false
        assertNull(currentHostUserId(null, loader))
    }
}
