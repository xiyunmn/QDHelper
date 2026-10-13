package com.xiyunmn.qdhelper.host.resolver.dexkit.query

import android.content.Context
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols
import org.luckypray.dexkit.query.enums.StringMatchType
import com.xiyunmn.qdhelper.host.resolver.reflection.ReflectionAccess
import java.lang.reflect.Modifier

internal object ReaderStartupQueries {
    val splashManagerSkipAd = DexKitMethodQuery(
        id = "startup.splash.skip_ad",
        purpose = "splash manager skip-ad entry via (Activity, splash-callback) shape",
        query = {
            searchPackages("com.qidian.QDReader.bll.splash")
            matcher {
                paramCount(2)
                returnType(Void.TYPE)
            }
        },
        verify = { method -> hasSplashSkipEntryShape(method) },
    )

    val readPageChapterEndAppend = DexKitMethodQuery(
        id = "reader.clean.chapter_end.append",
        purpose = "chapter-end rich page append via chapter-end data builder",
        query = {
            searchPackages("com.qidian.QDReader.readerengine.specialline")
            matcher {
                declaredClass(HostSymbols.Classes.QD_CHAPTER_END_DIVIDE_PROCESSING)
                paramCount(2)
                returnType(java.util.Vector::class.java)
                invokeMethods {
                    add {
                        declaredClass(HostSymbols.Classes.QD_CHAPTER_END_DIVIDE_PROCESSING)
                        name("buildChapterEndData")
                        paramCount(1)
                        returnType(List::class.java)
                    }
                }
            }
        },
        verify = { method ->
            method.declaringClass.name == HostSymbols.Classes.QD_CHAPTER_END_DIVIDE_PROCESSING &&
                !Modifier.isStatic(method.modifiers) &&
                method.parameterTypes.size == 2 &&
                method.parameterTypes[1] == Boolean::class.javaPrimitiveType &&
                java.util.Vector::class.java.isAssignableFrom(method.returnType)
        },
    )

    val chapterCommentCopyBindMethods = DexKitMethodQuery(
        id = "reader.free_copy.chapter_comment_bind",
        purpose = "chapter-comment holder bind methods with MessageTextView content",
        query = {
            searchPackages("com.qidian.QDReader.ui.viewholder.chaptercomment")
            matcher {
                paramTypes(
                    HostSymbols.Classes.CHAPTER_COMMENT_DATA_LIST,
                    HostSymbols.Classes.CHAPTER_COMMENT_BOOK_INFO,
                )
                returnType(Void.TYPE)
            }
        },
        verify = { method ->
            !Modifier.isAbstract(method.modifiers) &&
                !Modifier.isStatic(method.modifiers) &&
                method.returnType == Void.TYPE &&
                method.declaringClass.name.startsWith("com.qidian.QDReader.ui.viewholder.chaptercomment.") &&
                method.parameterTypes.map(Class<*>::getName) == listOf(
                    HostSymbols.Classes.CHAPTER_COMMENT_DATA_LIST,
                    HostSymbols.Classes.CHAPTER_COMMENT_BOOK_INFO,
                ) &&
                ReflectionAccess.fieldsDeclaredBy(method.declaringClass).any { field ->
                    field.type.name == HostSymbols.Classes.MESSAGE_TEXT_VIEW ||
                        field.type.hasSuperclass(HostSymbols.Classes.MESSAGE_TEXT_VIEW)
                }
        },
    )

    val circleFreeCopyListBind = DexKitMethodQuery(
        id = "circle.free_copy.list_bind",
        purpose = "book-circle UGC holder bind via model/int/long signature",
        query = {
            searchPackages("com.qidian.QDReader.ui.viewholder")
            matcher {
                paramTypes(
                    HostSymbols.Classes.UGC_BASE_ITEM,
                    "int",
                    "long",
                )
                returnType(Void.TYPE)
            }
        },
        verify = { method ->
            !Modifier.isAbstract(method.modifiers) &&
                !Modifier.isStatic(method.modifiers) &&
                method.returnType == Void.TYPE &&
                method.declaringClass.name == HostSymbols.Classes.UGC_ITEM_VIEW_HOLDER &&
                method.parameterTypes.map(Class<*>::getName) == listOf(
                    HostSymbols.Classes.UGC_BASE_ITEM,
                    "int",
                    "long",
                )
        },
    )

    val circleFreeCopyDetailBindMethods = DexKitMethodQuery(
        id = "circle.free_copy.detail_bind",
        purpose = "book-circle rich-text title/body bind overrides",
        query = {
            searchPackages("com.qidian.QDReader.ui.viewholder.richtext")
            matcher {
                modifiers(Modifier.PUBLIC)
                paramCount(0)
                returnType(Void.TYPE)
            }
        },
        verify = { method ->
            method.declaringClass.name in setOf(
                HostSymbols.Classes.RICH_TEXT_TITLE_VIEW_HOLDER,
                HostSymbols.Classes.RICH_TEXT_HTML_VIEW_HOLDER,
            ) && isPublicAbstractOverride(method)
        },
    )

    val circleFreeCopyDetailData = DexKitMethodQuery(
        id = "circle.free_copy.detail_data",
        purpose = "book-circle rich-text adapter complete item-list setter",
        query = {
            searchPackages("com.qidian.QDReader.ui.adapter")
            matcher {
                paramTypes(ArrayList::class.java)
                returnType(Void.TYPE)
            }
        },
        verify = { method ->
            Modifier.isPublic(method.modifiers) &&
                !Modifier.isStatic(method.modifiers) &&
                !method.isBridge &&
                !method.isSynthetic &&
                method.returnType == Void.TYPE &&
                method.declaringClass.name == HostSymbols.Classes.RICH_TEXT_ADAPTER &&
                method.parameterTypes.contentEquals(arrayOf(ArrayList::class.java)) &&
                ReflectionAccess.fieldsDeclaredBy(method.declaringClass).any { field ->
                    field.type == ArrayList::class.java
                }
        },
    )

    val splashDownloadUtilCihai = DexKitMethodQuery(
        id = "startup.splash.download_util.cihai",
        purpose = "SplashDownloadUtil cache dir resolver",
        query = {
            searchPackages("com.qidian.QDReader.util")
            matcher {
                paramCount(0)
                returnType(String::class.java)
                addUsingString("/splash/", StringMatchType.Equals)
            }
        },
        verify = { method ->
            method.parameterTypes.isEmpty() &&
                method.returnType == String::class.java &&
                method.declaringClass.simpleName == "SplashDownloadUtil"
        },
    )

    /**
     * SplashActivity.initCustomSplashScreen() —— `boolean` 无参，命中即已把自定义启动图交给宿主渲染。
     * `BootWallPapers`/`Paper` 只是类型引用（不入常量池），字符串锚点无法命中；方法体里有极独特的魔数
     * `paperId != 685`（685 为宿主保留的占位 Paper ID），用它作数字锚点，配合 SplashActivity 归属 +
     * ImageView 字段（mIvSplash）结构校验。方法名在 7.9.469 未混淆，但仍以数字+结构锚定以抗未来重命名。
     */
    val splashActivityInitCustom = DexKitMethodQuery(
        id = "startup.splash.activity.init_custom",
        purpose = "SplashActivity custom splash init via 685 placeholder-paper magic number",
        query = {
            searchPackages("com.qidian.QDReader.ui.activity")
            matcher {
                paramCount(0)
                returnType(Boolean::class.javaPrimitiveType!!)
                addUsingNumber(685)
            }
        },
        verify = { method ->
            method.parameterTypes.isEmpty() &&
                method.returnType == Boolean::class.javaPrimitiveType &&
                method.declaringClass.simpleName == "SplashActivity" &&
                ReflectionAccess.fieldsDeclaredBy(method.declaringClass).any { it.type.simpleName == "ImageView" }
        },
    )

    /** SplashActivity default drawable branch, anchored by its YWImageLoader three-argument call. */
    val splashActivityInitDefault = DexKitMethodQuery(
        id = "startup.splash.activity.init_default",
        purpose = "SplashActivity default splash init via YWImageLoader context/object/callback call",
        query = {
            searchPackages("com.qidian.QDReader.ui.activity")
            matcher {
                declaredClass(HostSymbols.Classes.SPLASH_ACTIVITY)
                paramTypes()
                returnType(Void.TYPE)
                invokeMethods {
                    add {
                        declaredClass(HostSymbols.Classes.YW_IMAGE_LOADER)
                        modifiers(Modifier.PUBLIC or Modifier.STATIC)
                        paramTypes(Context::class.java.name, Object::class.java.name, null)
                        returnType(Void.TYPE)
                    }
                }
            }
        },
        verify = { method ->
            !Modifier.isStatic(method.modifiers) &&
                method.parameterTypes.isEmpty() &&
                method.returnType == Void.TYPE &&
                method.declaringClass.name == HostSymbols.Classes.SPLASH_ACTIVITY
        },
    )

    /**
     * Kotlin top-level official-paper button binder. Its four host parameter types stay stable even
     * when the short method name changes, so neither query nor verifier depends on `judian`.
     */
    val splashConfigButtonBind = DexKitMethodQuery(
        id = "startup.splash.config.button_bind",
        purpose = "official splash paper action-button binder by QDUIButton/BootPaper/ViewModel/int signature",
        query = {
            searchPackages("com.qidian.QDReader.ui.activity.splash_config")
            matcher {
                declaredClass(HostSymbols.Classes.SPLASH_CONFIG_FRAGMENT_KT)
                paramTypes(
                    HostSymbols.Classes.QDUI_BUTTON,
                    HostSymbols.Classes.BOOT_PAPER,
                    HostSymbols.Classes.SPLASH_CONFIG_SHARED_VIEW_MODEL,
                    "int",
                )
                returnType(Void.TYPE)
            }
        },
        verify = { method ->
            Modifier.isStatic(method.modifiers) &&
                !method.isSynthetic &&
                !method.isBridge &&
                method.returnType == Void.TYPE &&
                method.declaringClass.name == HostSymbols.Classes.SPLASH_CONFIG_FRAGMENT_KT &&
                method.parameterTypes.map(Class<*>::getName) == listOf(
                    HostSymbols.Classes.QDUI_BUTTON,
                    HostSymbols.Classes.BOOT_PAPER,
                    HostSymbols.Classes.SPLASH_CONFIG_SHARED_VIEW_MODEL,
                    "int",
                )
        },
    )

    val dailyReadingNativeNetBridgeRegister = DexKitMethodQuery(
        id = "ui.daily_reading.native_net_bridge.register",
        purpose = "daily reading NativeNetApi bridge registration",
        query = {
            searchPackages("com.qidian.QDReader.flutter.bridges")
            matcher {
                modifiers(Modifier.PUBLIC or Modifier.STATIC)
                paramCount(2)
                returnType(Void.TYPE)
                addUsingString("dev.flutter.pigeon.NativeNetApi.getNativeNetBridge", StringMatchType.Contains)
            }
        },
        verify = { method ->
            Modifier.isStatic(method.modifiers) &&
                method.returnType == Void.TYPE &&
                method.parameterTypes.size == 2 &&
                method.parameterTypes[0].name == "io.flutter.embedding.engine.FlutterEngine" &&
                method.parameterTypes[1].name.startsWith("com.qidian.QDReader.flutter.bridges.") &&
                method.parameterTypes[1].name.endsWith("\$search")
        },
    )

}
