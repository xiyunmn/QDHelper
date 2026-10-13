package com.xiyunmn.qdhelper.feature.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RxConsumerSubscriptionMethodPolicyTest {
    @Test
    fun `finds two-consumer subscribe inherited by runtime observable class`() {
        val selected = RxConsumerSubscriptionMethodPolicy.select(
            methods = RuntimeObservable::class.java.methods.toList(),
            hasSingleArgumentAccept = ::hasSingleArgumentAccept,
        )

        assertEquals(BaseObservable::class.java, selected?.declaringClass)
        assertEquals(listOf(Consumer::class.java, Consumer::class.java), selected?.parameterTypes?.toList())
    }

    @Test
    fun `declared-only lookup characterizes the migration regression`() {
        val selected = RxConsumerSubscriptionMethodPolicy.select(
            methods = RuntimeObservable::class.java.declaredMethods.toList(),
            hasSingleArgumentAccept = ::hasSingleArgumentAccept,
        )

        assertNull(selected)
    }

    private fun hasSingleArgumentAccept(type: Class<*>): Boolean =
        type.methods.any { method -> method.name == "accept" && method.parameterCount == 1 }

    private fun interface Consumer {
        fun accept(value: Any?)
    }

    private open class BaseObservable {
        @Suppress("UNUSED_PARAMETER")
        fun subscribe(onNext: Consumer, onError: Consumer): Disposable = Disposable()
    }

    private class RuntimeObservable : BaseObservable()

    private class Disposable
}
