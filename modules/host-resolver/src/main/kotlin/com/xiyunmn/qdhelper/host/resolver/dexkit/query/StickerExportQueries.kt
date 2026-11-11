package com.xiyunmn.qdhelper.host.resolver.dexkit.query

import android.app.Activity
import com.xiyunmn.qdhelper.host.api.target.MediaExportTargetKeys
import java.lang.reflect.Modifier

internal object StickerExportQueries {
    private const val ACTIVITY = "com.qidian.QDReader.ui.activity.QDStickersDetailActivity"
    private const val BEAN = "com.qidian.QDReader.repository.entity.dressup.StickersBean"

    val detailBind = DexKitMethodQuery(
        id = MediaExportTargetKeys.stickerDetailBind.id.value,
        purpose = "sticker detail binding after the package and face list have loaded",
        query = {
            searchPackages("com.qidian.QDReader.ui.activity")
            matcher { declaredClass(ACTIVITY); paramTypes(BEAN); returnType(Void.TYPE) }
        },
        verify = { method ->
            method.declaringClass.name == ACTIVITY && Activity::class.java.isAssignableFrom(method.declaringClass) &&
                !Modifier.isStatic(method.modifiers) && !Modifier.isAbstract(method.modifiers) &&
                method.returnType == Void.TYPE && method.parameterTypes.singleOrNull()?.let { bean ->
                    bean.name == BEAN && bean.methods.any { it.name == "getName" && it.parameterCount == 0 && it.returnType == String::class.java } &&
                        bean.methods.any { it.name == "getFaceList" && it.parameterCount == 0 && List::class.java.isAssignableFrom(it.returnType) }
                } == true
        },
    )
}
