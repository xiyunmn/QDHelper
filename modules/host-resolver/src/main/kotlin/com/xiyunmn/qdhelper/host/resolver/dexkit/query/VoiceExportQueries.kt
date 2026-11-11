package com.xiyunmn.qdhelper.host.resolver.dexkit.query

import java.lang.reflect.Modifier

internal object VoiceExportQueries {
    private const val VOICE_VIEW = "com.qidian.QDReader.ui.view.chapter_review.VoicePlayerView"
    private const val LIST_PACKAGE = "com.qidian.QDReader.ui.viewholder.chaptercomment.list"
    private const val DETAIL_PACKAGE = "com.qidian.QDReader.ui.viewholder.chaptercomment.detail"
    private const val LIST_ITEM = "com.qidian.QDReader.repository.entity.chaptercomment.NewParagraphCommentListBean\$DataListBean"
    private const val BOOK_INFO = "com.qidian.QDReader.repository.entity.chaptercomment.NewParagraphCommentListBean\$BookInfoBean"
    private const val DETAIL_ITEM = "com.qidian.QDReader.repository.entity.chaptercomment.NewParagraphCommentDetailBean\$DataListBean"

    val voiceListBind = DexKitMethodQuery(
        id = "content_tools.media_export.voice_list_bind",
        purpose = "chapter-comment list holders binding the host voice player",
        query = {
            searchPackages(LIST_PACKAGE)
            matcher {
                paramTypes(LIST_ITEM, BOOK_INFO)
                returnType(Void.TYPE)
                invokeMethods {
                    add {
                        declaredClass(VOICE_VIEW)
                        paramTypes("long", String::class.java.name, String::class.java.name, String::class.java.name, "int")
                        returnType(Void.TYPE)
                    }
                }
            }
        },
        verify = { method ->
            !Modifier.isStatic(method.modifiers) && method.returnType == Void.TYPE &&
                method.declaringClass.name.startsWith("$LIST_PACKAGE.") &&
                method.parameterTypes.map { it.name } == listOf(LIST_ITEM, BOOK_INFO) &&
                hasVoiceField(method.declaringClass) && hasAudioUrl(method.parameterTypes.first())
        },
    )

    val voiceDetailBind = DexKitMethodQuery(
        id = "content_tools.media_export.voice_detail_bind",
        purpose = "chapter-comment detail holders binding the host voice player",
        query = {
            searchPackages(DETAIL_PACKAGE)
            matcher {
                paramTypes(DETAIL_ITEM)
                returnType(Void.TYPE)
                invokeMethods {
                    add {
                        declaredClass(VOICE_VIEW)
                        paramTypes("long", String::class.java.name, String::class.java.name, String::class.java.name, "int")
                        returnType(Void.TYPE)
                    }
                }
            }
        },
        verify = { method ->
            !Modifier.isStatic(method.modifiers) && method.returnType == Void.TYPE &&
                method.declaringClass.name.startsWith("$DETAIL_PACKAGE.") &&
                method.parameterTypes.map { it.name } == listOf(DETAIL_ITEM) &&
                hasVoiceField(method.declaringClass) && hasAudioUrl(method.parameterTypes.first())
        },
    )

    val all = listOf(voiceListBind, voiceDetailBind)

    private fun hasAudioUrl(model: Class<*>): Boolean = model.methods.count {
        it.name == "getAudioUrl" && it.parameterCount == 0 && it.returnType == String::class.java &&
            !Modifier.isStatic(it.modifiers)
    } == 1

    private fun hasVoiceField(owner: Class<*>): Boolean =
        generateSequence(owner) { it.superclass }.flatMap { it.declaredFields.asSequence() }
            .count { !Modifier.isStatic(it.modifiers) && it.type.name == VOICE_VIEW } == 1
}

