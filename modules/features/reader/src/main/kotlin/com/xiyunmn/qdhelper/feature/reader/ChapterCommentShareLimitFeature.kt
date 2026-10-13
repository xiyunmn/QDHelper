package com.xiyunmn.qdhelper.feature.reader

import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.feature.api.runtime.InstallableFeature
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols as QDReaderHost
import com.xiyunmn.qdhelper.foundation.runtime.HostSessionStore
import com.xiyunmn.qdhelper.config.SettingsRuntime
import com.xiyunmn.qdhelper.feature.api.runtime.HookModule
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

object ChapterCommentShareLimitFeature : InstallableFeature {
    override val name: String = "ChapterCommentShareLimitFeature"

    override fun install(
        module: HookModule,
        classLoader: ClassLoader,
        processName: String,
        phase: String,
    ) {
        if (!QDReaderHost.isMainProcess(processName)) return
        val policyClass = FeatureHooks.findClassOrNull(QDReaderHost.Classes.SHARE_POLICY_BEAN, classLoader)
            ?: return
        val shareToastField = policyClass.semanticField("ShareToast", String::class.java)
        val canShareField = policyClass.semanticField("CanShare", Int::class.javaPrimitiveType)
        val shareWordCountField = policyClass.semanticField("ShareWordCount", Int::class.javaPrimitiveType)
        if (shareToastField == null || canShareField == null || shareWordCountField == null) {
            FeatureHooks.w("Chapter-comment share limit skipped: semantic fields missing, phase=$phase")
            return
        }
        val canShareGetter = policyClass.semanticGetter("getCanShare", canShareField)
        val shareToastGetter = policyClass.semanticGetter("getShareToast", shareToastField)
        val shareWordCountGetter = policyClass.semanticGetter("getShareWordCount", shareWordCountField)

        policyClass.declaredConstructors.forEachIndexed { index, constructor ->
            FeatureHooks.hookAfter(
                module = module,
                executable = constructor.apply { isAccessible = true },
                feature = "reader.commentShareLimit:SharePolicyBean constructor/${constructor.parameterCount}",
                id = "qdhelper.reader.comment_share_limit.constructor.$index",
            ) { chain, _ ->
                normalizePolicy(chain.thisObject, canShareField, shareToastField, shareWordCountField)
            }
        }
        FeatureHooks.hookAround(
            module = module,
            executable = canShareGetter,
            feature = "reader.commentShareLimit:SharePolicyBean.getCanShare",
            id = "qdhelper.reader.comment_share_limit.can_share",
        ) { chain ->
            val original = chain.proceed()
            if (original == 0 && shouldRemoveLimit(chain.thisObject, shareToastField)) 1 else original
        }
        FeatureHooks.hookAround(
            module = module,
            executable = shareWordCountGetter,
            feature = "reader.commentShareLimit:SharePolicyBean.getShareWordCount",
            id = "qdhelper.reader.comment_share_limit.share_word_count",
        ) { chain ->
            val original = chain.proceed() as Int
            if (shouldRemoveLimit(chain.thisObject, shareToastField)) {
                normalizedDailyShareWordCount(original)
            } else {
                original
            }
        }
        FeatureHooks.hookAround(
            module = module,
            executable = shareToastGetter,
            feature = "reader.commentShareLimit:SharePolicyBean.getShareToast",
            id = "qdhelper.reader.comment_share_limit.share_toast",
        ) { chain ->
            val original = chain.proceed()
            if (isLimitRemovalEnabled() && isDailyShareLimitMessage(original as? String)) "" else original
        }
    }

    private fun shouldRemoveLimit(policy: Any?, shareToastField: Field): Boolean {
        if (!isLimitRemovalEnabled() || policy == null) return false
        val message = runCatching { shareToastField.get(policy) as? String }.getOrNull()
        return isDailyShareLimitMessage(message)
    }

    private fun normalizePolicy(
        policy: Any?,
        canShareField: Field,
        shareToastField: Field,
        shareWordCountField: Field,
    ) {
        if (!shouldRemoveLimit(policy, shareToastField) || policy == null) return
        runCatching {
            canShareField.setInt(policy, 1)
            shareWordCountField.setInt(
                policy,
                normalizedDailyShareWordCount(shareWordCountField.getInt(policy)),
            )
            shareToastField.set(policy, "")
            FeatureHooks.i("Reading daily share limit removed")
        }.onFailure { throwable ->
            FeatureHooks.w("Reading share policy normalization failed: ${throwable.javaClass.name}")
        }
    }

    private fun isLimitRemovalEnabled(): Boolean {
        return SettingsRuntime.isChapterCommentShareLimitRemoved(HostSessionStore.contextOrNull())
    }

    private fun Field.serializedNameValue(): String? {
        return declaredAnnotations.firstNotNullOfOrNull { annotation ->
            if (annotation.annotationClass.java.name != "com.google.gson.annotations.SerializedName") {
                return@firstNotNullOfOrNull null
            }
            runCatching {
                annotation.annotationClass.java.getMethod("value").invoke(annotation) as? String
            }.getOrNull()
        }
    }

    private fun Class<*>.semanticField(serializedName: String, type: Class<*>?): Field? {
        return declaredFields.singleOrNull { field ->
            !Modifier.isStatic(field.modifiers) && field.type == type &&
                field.serializedNameValue() == serializedName
        }?.apply { isAccessible = true }
    }

    private fun Class<*>.semanticGetter(stableName: String, field: Field): Method? {
        return declaredMethods.singleOrNull { method ->
            method.name == stableName &&
                !Modifier.isStatic(method.modifiers) &&
                method.parameterCount == 0 &&
                method.returnType == field.type
        }?.apply { isAccessible = true }
    }
}

internal fun isDailyShareLimitMessage(message: String?): Boolean {
    val text = message?.trim().orEmpty()
    if (text.isEmpty()) return false
    val mentionsDay = listOf("每日", "每天", "今日", "当天", "一天", "明日", "次日", "24小时").any(text::contains)
    val mentionsCount = listOf("次数", "次", "上限", "已达", "用完", "仅可", "只能").any(text::contains)
    return mentionsDay && mentionsCount
}

internal fun normalizedDailyShareWordCount(original: Int): Int {
    return if (original > 0) original else Int.MAX_VALUE
}
