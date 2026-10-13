package com.xiyunmn.qdhelper.host.resolver.dexkit.query

import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols
import com.xiyunmn.qdhelper.host.resolver.reflection.ReflectionAccess
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Ordered contracts audited against JADX and smali for 7.9.469 through 7.9.480. */
internal object WelfareRecoveryContracts {
    private const val STRING = "java.lang.String"
    private const val JSON = "org.json.JSONObject"
    private const val REF = "java.util.concurrent.atomic.AtomicReference"
    private const val ATOMIC_INT = "java.util.concurrent.atomic.AtomicInteger"
    private const val CONFIG = HostSymbols.Classes.REWARD_VIDEO_CONFIG
    private const val ACTIVITY = HostSymbols.Classes.BASE_ACTIVITY
    private const val VERIFY = HostSymbols.Classes.UNIVERSAL_VERIFY_HELPER

    val nativeBridgeParameters = listOf(STRING, STRING, STRING, CONFIG, STRING, "int", "int", ACTIVITY, JSON, "int")
    val groupedBridgeParameters = listOf(STRING, STRING, CONFIG, ACTIVITY, JSON, "int")
    val nativeEventParameters = listOf(REF, STRING, "int", REF, CONFIG, "int", "java.lang.Integer")
    private val groupedErrorTails = listOf(
        listOf(ATOMIC_INT, "int", ATOMIC_INT, REF, VERIFY, "java.lang.Throwable"),
        listOf(ATOMIC_INT, "int", ATOMIC_INT, "int", VERIFY, REF, CONFIG, "java.lang.Throwable"),
    )

    fun isPluginBridge(method: Method, parameters: List<String>): Boolean =
        !Modifier.isStatic(method.modifiers) && method.returnType == Void.TYPE &&
            method.parameterTypes.map { it.name } == parameters &&
            method.declaringClass.name.startsWith("com.qidian.QDReader.webview.plugins.") &&
            generateSequence(method.declaringClass.superclass) { it.superclass }
                .any { it.name == HostSymbols.Classes.WEBVIEW_PLUGIN }

    fun isNativeEvent(method: Method): Boolean =
        Modifier.isStatic(method.modifiers) && method.returnType == Void.TYPE &&
            method.parameterTypes.map { it.name } == nativeEventParameters &&
            method.declaringClass.name.startsWith("com.qidian.QDReader.extras.")

    fun isGroupedError(method: Method): Boolean =
        Modifier.isStatic(method.modifiers) && method.returnType == Void.TYPE &&
            method.parameterTypes.firstOrNull()?.let { HostMethodContracts.isWelfareDialog(it) } == true &&
            method.parameterTypes.drop(1).map { it.name } in groupedErrorTails &&
            method.declaringClass.name.startsWith("com.qidian.QDReader.extras.")

    fun isSubscribe(method: Method): Boolean {
        val parameters = method.parameterTypes
        if (method.name != "subscribe" || Modifier.isStatic(method.modifiers) ||
            method.declaringClass.name != HostSymbols.Classes.RX_OBSERVABLE ||
            parameters.size != 2 || parameters[0] != parameters[1] || !parameters[0].isInterface
        ) return false
        val accept = ReflectionAccess.methodsDeclaredBy(parameters[0]).singleOrNull() ?: return false
        return accept.name == "accept" && accept.returnType == Void.TYPE &&
            accept.parameterTypes.contentEquals(arrayOf(Any::class.java)) &&
            method.returnType.isInterface
    }
}
