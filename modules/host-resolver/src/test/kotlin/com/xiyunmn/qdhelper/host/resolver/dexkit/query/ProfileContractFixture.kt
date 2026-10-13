package com.xiyunmn.qdhelper.host.resolver.dexkit.query

import android.content.Context
import org.json.JSONObject

internal object ProfileContractFixture {
    abstract class CallbackBase {
        abstract fun a(response: Any?, message: String?)

        abstract fun b(json: JSONObject?, message: String?, code: Int)
    }

    class Response<T>

    class HomeData

    class HomeItem

    @JvmStatic
    fun x(context: Context?, userId: Long, page: Int, callback: CallbackBase) = Unit

    @JvmStatic
    fun y(context: Context?, userId: Long, page: Int, callback: CallbackBase) = Unit

    @JvmStatic
    fun z(response: Response<HomeData>, force: Boolean): Response<HomeItem>? = null

    @JvmStatic
    fun invalid(context: Context?, userId: Long, callback: CallbackBase) = Unit

    @JvmStatic
    fun callback(): CallbackBase = object : CallbackBase() {
        override fun a(response: Any?, message: String?) = Unit

        override fun b(json: JSONObject?, message: String?, code: Int) = Unit
    }
}

internal object UnrelatedProfileCallbackFixture {
    abstract class CallbackBase {
        abstract fun b(json: JSONObject?, message: String?, code: Int)
    }

    @JvmStatic
    fun callback(): CallbackBase = object : CallbackBase() {
        override fun b(json: JSONObject?, message: String?, code: Int) = Unit
    }
}
