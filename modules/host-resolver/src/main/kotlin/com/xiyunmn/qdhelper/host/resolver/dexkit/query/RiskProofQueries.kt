package com.xiyunmn.qdhelper.host.resolver.dexkit.query

import com.xiyunmn.qdhelper.host.api.target.AccountTargetKeys
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Verify the value object's constructor semantics before mapping obfuscated getters. */
internal object RiskProofQueries {
    private val labels = listOf("session", "ticket", "rand", "challenge", "validate", "seccode")
    private val markers = labels.map { "qdhelper_contract_$it" }
    private val prototype = arrayOf(Boolean::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
        *Array(6) { String::class.java })
    val all = AccountTargetKeys.verification.map { (label, key) ->
        DexKitMethodQuery(key.id.value, "verified risk result $label getter", {
            searchPackages("com.qidian.QDReader.component.universalverify")
            matcher { paramCount(0); returnType(if (label == "ban") "int" else "java.lang.String") }
        }, { method -> matches(method, label) })
    }
    private fun matches(method: Method, label: String): Boolean = runCatching {
        if (!Modifier.isPublic(method.modifiers) || Modifier.isStatic(method.modifiers) ||
            method.parameterTypes.isNotEmpty() || method.name in setOf("toString", "hashCode")) return@runCatching false
        val probe = method.declaringClass.getConstructor(*prototype).newInstance(true, 19731, *markers.toTypedArray())
        val expected = "VerifyResult(hitRisk=true, banId=19731, sessionKey=${markers[0]}, ticket=${markers[1]}, " +
            "randStr=${markers[2]}, challenge=${markers[3]}, validate=${markers[4]}, secode=${markers[5]})"
        if (probe.toString() != expected) return@runCatching false
        val value = if (label == "ban") 19731 else markers[labels.indexOf(label)]
        method.invoke(probe) == value
    }.getOrDefault(false)
}
