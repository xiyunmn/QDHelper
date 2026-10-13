package com.xiyunmn.qdhelper.host.resolver.dexkit.query

import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Modifier

internal object UiTargetQueries {
    val themeNightMode = DexKitMethodQuery(
        id = "ui.theme.night_mode",
        purpose = "host QDThemeManager night-mode getter",
        query = {
            searchPackages("com.qidian.QDReader.component.app.theme")
            matcher {
                declaredClass(HostSymbols.Classes.QD_THEME_MANAGER)
                modifiers(java.lang.reflect.Modifier.PUBLIC or java.lang.reflect.Modifier.STATIC)
                paramCount(0)
                returnType(Boolean::class.javaPrimitiveType!!)
            }
        },
        verify = { method ->
            method.declaringClass.name == HostSymbols.Classes.QD_THEME_MANAGER &&
                java.lang.reflect.Modifier.isPublic(method.modifiers) &&
                java.lang.reflect.Modifier.isStatic(method.modifiers) &&
                method.parameterTypes.isEmpty() &&
                method.returnType == Boolean::class.javaPrimitiveType!!
        },
    )

    val bookshelfAppConfigCurrent = DexKitMethodQuery(
        id = "bookshelf.config.app_config_current",
        purpose = "bookshelf current AppConfigBean getter",
        query = {
            searchPackages("com.qidian.QDReader.component.config")
            matcher {
                declaredClass(HostSymbols.Classes.APP_CONFIG)
                paramCount(0)
                returnType("com.qidian.QDReader.repository.entity.config.AppConfigBean")
            }
        },
        verify = { method ->
            method.declaringClass.name == HostSymbols.Classes.APP_CONFIG &&
                method.parameterTypes.isEmpty() &&
                method.returnType.name == "com.qidian.QDReader.repository.entity.config.AppConfigBean"
        },
    )

    // These four getters share their signatures with several sibling fields on maintab.b. Until a
    // stable field/caller anchor is available, keep the audited letter but constrain both query and
    // verifier to the exact owner; dropping the name would turn Cardinality.One into ambiguity.
    val bottomTabRedPoint = namedMethod(
        id = "ui.bottom_tab.param.red_point",
        purpose = "bottom tab param red_point",
        searchPackage = "com.qidian.QDReader.ui.widget.maintab",
        methodName = "i",
        paramCount = 0,
        returnType = Int::class.javaPrimitiveType,
        ownerName = HostSymbols.Classes.MAINTAB_PARAM,
    )

    val bottomTabTagText = namedMethod(
        id = "ui.bottom_tab.param.tag_text",
        purpose = "bottom tab param tag_text",
        searchPackage = "com.qidian.QDReader.ui.widget.maintab",
        methodName = "n",
        paramCount = 0,
        returnType = String::class.java,
        ownerName = HostSymbols.Classes.MAINTAB_PARAM,
    )

    val bottomTabOperationRedDot = DexKitMethodQuery(
        id = "ui.bottom_tab.param.operation_red_dot",
        purpose = "bottom tab param operation red-dot getter by RedDot return contract",
        query = {
            searchPackages("com.qidian.QDReader.ui.widget.maintab")
            matcher {
                declaredClass(HostSymbols.Classes.MAINTAB_PARAM)
                paramCount(0)
                returnType("com.qidian.QDReader.repository.entity.RedDot")
            }
        },
        verify = { method ->
            method.declaringClass.name == HostSymbols.Classes.MAINTAB_PARAM &&
                !Modifier.isStatic(method.modifiers) &&
                method.parameterTypes.isEmpty() &&
                method.returnType.name == "com.qidian.QDReader.repository.entity.RedDot"
        },
    )

    val bottomTabTrackerPosition = namedMethod(
        id = "ui.bottom_tab.param.tracker_position",
        purpose = "bottom tab param tracker_position",
        searchPackage = "com.qidian.QDReader.ui.widget.maintab",
        methodName = "p",
        paramCount = 0,
        returnType = Int::class.javaPrimitiveType,
        ownerName = HostSymbols.Classes.MAINTAB_PARAM,
    )

    val bottomTabOperationMarker = namedMethod(
        id = "ui.bottom_tab.param.operation_marker",
        purpose = "bottom tab param operation_marker",
        searchPackage = "com.qidian.QDReader.ui.widget.maintab",
        methodName = "l",
        paramCount = 0,
        returnType = Int::class.javaPrimitiveType,
        ownerName = HostSymbols.Classes.MAINTAB_PARAM,
    )

    val bottomTabSetRedPoint = DexKitMethodQuery(
        id = "ui.bottom_tab.param.set_red_point",
        purpose = "bottom tab param red-point setter by pageRedPointVisible log contract",
        query = {
            searchPackages("com.qidian.QDReader.ui.widget.maintab")
            matcher {
                declaredClass(HostSymbols.Classes.MAINTAB_PARAM)
                paramTypes("int")
                returnType(Void.TYPE)
                addUsingString("pageRedPointVisible:", StringMatchType.Contains)
            }
        },
        verify = { method ->
            method.declaringClass.name == HostSymbols.Classes.MAINTAB_PARAM &&
                !Modifier.isStatic(method.modifiers) &&
                method.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType)) &&
                method.returnType == Void.TYPE
        },
    )

    val bottomTabSetTagText = DexKitMethodQuery(
        id = "ui.bottom_tab.param.set_tag_text",
        purpose = "bottom tab param tag-text setter by setTagText log contract",
        query = {
            searchPackages("com.qidian.QDReader.ui.widget.maintab")
            matcher {
                declaredClass(HostSymbols.Classes.MAINTAB_PARAM)
                paramTypes(String::class.java.name)
                returnType(Void.TYPE)
                addUsingString("setTagText:", StringMatchType.Contains)
            }
        },
        verify = { method ->
            method.declaringClass.name == HostSymbols.Classes.MAINTAB_PARAM &&
                !Modifier.isStatic(method.modifiers) &&
                method.parameterTypes.contentEquals(arrayOf(String::class.java)) &&
                method.returnType == Void.TYPE
        },
    )

    val bottomTabSetOperationRedDot = DexKitMethodQuery(
        id = "ui.bottom_tab.param.set_operation_red_dot",
        purpose = "bottom tab param operation red-dot setter by RedDot parameter contract",
        query = {
            searchPackages("com.qidian.QDReader.ui.widget.maintab")
            matcher {
                declaredClass(HostSymbols.Classes.MAINTAB_PARAM)
                paramTypes("com.qidian.QDReader.repository.entity.RedDot")
                returnType(Void.TYPE)
            }
        },
        verify = { method ->
            method.declaringClass.name == HostSymbols.Classes.MAINTAB_PARAM &&
                !Modifier.isStatic(method.modifiers) &&
                method.parameterTypes.size == 1 &&
                method.parameterTypes[0].name == "com.qidian.QDReader.repository.entity.RedDot" &&
                method.returnType == Void.TYPE
        },
    )

    val pagerSlidingTabStripRebuild = DexKitMethodQuery(
        id = "ui.bottom_tab.pager.rebuild",
        purpose = "bottom tab pager strip rebuild by owner/no-arg void contract",
        query = {
            searchPackages("com.qidian.QDReader.ui.widget.maintab")
            matcher {
                declaredClass(HostSymbols.Classes.PAGER_SLIDING_TAB_STRIP)
                paramCount(0)
                returnType(Void.TYPE)
                addUsingString("notifyDataSetChanged index:", StringMatchType.Contains)
            }
        },
        verify = { method ->
            method.declaringClass.name == HostSymbols.Classes.PAGER_SLIDING_TAB_STRIP &&
                !Modifier.isStatic(method.modifiers) &&
                method.parameterTypes.isEmpty() &&
                method.returnType == Void.TYPE
        },
    )

    val bottomTabPagerAdapterItems = DexKitMethodQuery(
        id = "ui.bottom_tab.adapter.items",
        purpose = "bottom tab adapter tab-param list by adapter interface/list contract",
        query = {
            searchPackages("com.qidian.QDReader.ui.activity")
            matcher {
                paramCount(0)
                returnType(List::class.java)
            }
        },
        verify = { method ->
            method.parameterTypes.isEmpty() &&
                List::class.java.isAssignableFrom(method.returnType) &&
                method.declaringClass.hasSuperclass("com.qidian.QDReader.ui.adapter.TypeFragmentPagerAdapter") &&
                method.declaringClass.interfaces.any { it.name == "com.qidian.QDReader.ui.widget.maintab.PagerSlidingTabStrip\$b" }
        },
    )

    val bottomTabRenderIcon = DexKitMethodQuery(
        id = "ui.bottom_tab.pager.render_icon",
        purpose = "native tab icon renderer by image/scroll-top/selected/model/PAG contract",
        query = {
            searchPackages("com.qidian.QDReader.ui.widget.maintab")
            matcher {
                declaredClass(HostSymbols.Classes.PAGER_SLIDING_TAB_STRIP)
                paramTypes("android.widget.ImageView", "boolean", "boolean", HostSymbols.Classes.MAINTAB_PARAM, "com.dev.component.pag.PAGWrapperView")
                returnType(Void.TYPE)
            }
        },
        verify = { method ->
            method.declaringClass.name == HostSymbols.Classes.PAGER_SLIDING_TAB_STRIP &&
                !Modifier.isStatic(method.modifiers) && method.returnType == Void.TYPE &&
                method.parameterTypes.map { it.name } == listOf(
                    "android.widget.ImageView", "boolean", "boolean", HostSymbols.Classes.MAINTAB_PARAM, "com.dev.component.pag.PAGWrapperView",
                )
        },
    )

    // 7.9.469 exposes this Kotlin method as C(...), while 7.9.470 keeps onBindBook(...).
    // The coroutine-scope parameter is verified by its getCoroutineContext contract rather than
    // the obfuscated kotlinx.coroutines.z class name.
    val bookshelfNewListBookBind = DexKitMethodQuery(
        id = "ui.bookshelf.new_list_book.bind",
        purpose = "new bookshelf list book bind method (name-independent exact contract)",
        query = {
            searchPackages("com.qidian.QDReader.ui.modules.bookshelf.viewholder.list")
            matcher {
                declaredClass(HostSymbols.Classes.BOOKSHELF_NEW_LIST_BOOK_VIEW_HOLDER)
                modifiers(Modifier.PUBLIC or Modifier.FINAL)
                paramTypes(
                    HostSymbols.Classes.ITEM_BOOKSHELF_LIST_BINDING,
                    "int",
                    HostSymbols.Classes.BOOK_SHELF_ITEM,
                    null,
                    "int",
                )
                returnType(Void.TYPE)
            }
        },
        verify = HostMethodContracts::isBookshelfNewListBookBind,
    )

    val bookshelfDefaultTopBackgroundLoad = DexKitMethodQuery(
        id = "ui.bookshelf.top_background.default_load",
        purpose = "bookshelf host default top-background loader carrying the selected drawable resource ID",
        query = {
            searchPackages("com.qidian.QDReader.ui.modules.bookshelf")
            matcher {
                declaredClass(HostSymbols.Classes.BOOKSHELF_REBORN_FRAGMENT)
                modifiers(Modifier.PRIVATE or Modifier.FINAL)
                paramTypes(Int::class.javaPrimitiveType)
                returnType(Void.TYPE)
                addUsingString("loadDefaultTopBg ")
            }
        },
        verify = { method ->
            method.declaringClass.name == HostSymbols.Classes.BOOKSHELF_REBORN_FRAGMENT &&
                Modifier.isPrivate(method.modifiers) &&
                Modifier.isFinal(method.modifiers) &&
                method.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType)) &&
                method.returnType == Void.TYPE
        },
    )

    val bookShieldBookstoreReborn = singleListSetterQuery(
        id = "shield.list_setter.bookstore_reborn",
        purpose = "book shield list setter for bookstore_reborn",
        className = HostSymbols.Classes.BOOKSTORE_REBORN_ADAPTER,
    )
    val bookShieldBookItem = singleListSetterQuery(
        id = "shield.list_setter.book_item",
        purpose = "book shield list setter for book_item",
        className = HostSymbols.Classes.BOOK_ITEM_ADAPTER,
    )
    val bookShieldBookstoreItem = singleListSetterQuery(
        id = "shield.list_setter.bookstore_item",
        purpose = "book shield list setter for bookstore_item",
        className = HostSymbols.Classes.QD_BOOKSTORE_ITEM_ADAPTER,
    )
    val bookShieldBookstoreGroup = singleListSetterQuery(
        id = "shield.list_setter.bookstore_group",
        purpose = "book shield list setter for bookstore_group",
        className = HostSymbols.Classes.BOOKSTORE_GROUP_ADAPTER,
    )
    val bookShieldBookstoreLimit = singleListSetterQuery(
        id = "shield.list_setter.bookstore_limit",
        purpose = "book shield list setter for bookstore_limit",
        className = HostSymbols.Classes.BOOKSTORE_LIMIT_ADAPTER,
    )
    val bookShieldBookRecommend = singleListSetterQuery(
        id = "shield.list_setter.book_recommend",
        purpose = "book shield list setter for book_recommend",
        className = HostSymbols.Classes.QD_BOOK_RECOMMEND_ADAPTER,
    )
    val bookShieldSanJiang = DexKitMethodQuery(
        id = "shield.list_setter.san_jiang",
        purpose = "book shield SanJiang/new-book-force list setter",
        query = {
            searchPackages("com.qidian.QDReader.ui.adapter")
            matcher {
                declaredClass(HostSymbols.Classes.SAN_JIANG_ADAPTER)
                paramTypes(List::class.java)
                returnType(Void.TYPE)
            }
        },
        verify = HostMethodContracts::isSanJiangBookListSetter,
    )
    val bookShieldComicSquare = singleListSetterQuery(
        id = "shield.list_setter.comic_square",
        purpose = "book shield list setter for comic_square",
        className = HostSymbols.Classes.COMIC_SQUARE_ADAPTER,
    )

    val bookShieldRankingRightData = DexKitMethodQuery(
        id = "shield.list_setter.ranking_right",
        purpose = "book shield ranking right data setter",
        query = {
            searchPackages("com.qidian.QDReader.ui.adapter")
            matcher {
                declaredClass("com.qidian.QDReader.ui.adapter.RankingRightAdapter")
                addAnyOf { paramCount(7) }
                addAnyOf { paramCount(5) }
                returnType(Void.TYPE)
            }
        },
        verify = HostMethodContracts::isRankingRightDataSetter,
    )

    val pageCustomMineHolderBindData = DexKitMethodQuery(
        id = "ui.page_custom.mine.holder_bind_data",
        purpose = "mine page function holder bind data",
        query = {
            searchPackages("com.qidian.QDReader.ui.viewholder.user_account")
            matcher {
                declaredClass("com.qidian.QDReader.ui.viewholder.user_account.AccountFunctionHolder")
                paramTypes("com.qidian.QDReader.repository.entity.user_account.UserAccountCombineBean")
                returnType(Void.TYPE)
            }
        },
        verify = { method ->
            method.declaringClass.name == "com.qidian.QDReader.ui.viewholder.user_account.AccountFunctionHolder" &&
                method.parameterTypes.size == 1 &&
                method.parameterTypes[0].name == "com.qidian.QDReader.repository.entity.user_account.UserAccountCombineBean" &&
                method.returnType == Void.TYPE
        },
    )

    val pageCustomMineProcessAccountItem = DexKitMethodQuery(
        id = "ui.page_custom.mine.process_account_item",
        purpose = "mine page account item processor",
        query = {
            searchPackages("com.qidian.QDReader.ui.fragment.main_group")
            matcher {
                declaredClass("com.qidian.QDReader.ui.fragment.main_group.QDUserAccountRebornFragment")
                paramTypes("com.qidian.QDReader.repository.entity.user_account.UserAccountItemBean")
                returnType(List::class.java)
            }
        },
        verify = { method ->
            method.declaringClass.name == "com.qidian.QDReader.ui.fragment.main_group.QDUserAccountRebornFragment" &&
                method.parameterTypes.size == 1 &&
                method.parameterTypes[0].name == "com.qidian.QDReader.repository.entity.user_account.UserAccountItemBean" &&
                List::class.java.isAssignableFrom(method.returnType)
        },
    )

    val minePageVisibilityChanged = DexKitMethodQuery(
        id = "ui.mine_page.visibility_changed",
        purpose = "mine page visibility callback that invokes the reload entry",
        query = {
            searchPackages("com.qidian.QDReader.ui.fragment.main_group")
            matcher {
                declaredClass(HostSymbols.Classes.USER_ACCOUNT_REBORN_FRAGMENT)
                modifiers(Modifier.PROTECTED)
                paramTypes(Boolean::class.javaPrimitiveType)
                returnType(Void.TYPE)
                invokeMethods {
                    add {
                        declaredClass(HostSymbols.Classes.USER_ACCOUNT_REBORN_FRAGMENT)
                        modifiers(Modifier.PUBLIC or Modifier.FINAL)
                        paramTypes()
                        returnType(Void.TYPE)
                    }
                }
            }
        },
        verify = { method ->
            method.declaringClass.name == HostSymbols.Classes.USER_ACCOUNT_REBORN_FRAGMENT &&
                Modifier.isProtected(method.modifiers) &&
                method.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType)) &&
                method.returnType == Void.TYPE
        },
    )

    val minePageReload = DexKitMethodQuery(
        id = "ui.mine_page.reload",
        purpose = "mine page public final reload entry called from the visibility callback",
        query = {
            searchPackages("com.qidian.QDReader.ui.fragment.main_group")
            matcher {
                declaredClass(HostSymbols.Classes.USER_ACCOUNT_REBORN_FRAGMENT)
                modifiers(Modifier.PUBLIC or Modifier.FINAL)
                paramTypes()
                returnType(Void.TYPE)
                callerMethods {
                    add {
                        declaredClass(HostSymbols.Classes.USER_ACCOUNT_REBORN_FRAGMENT)
                        modifiers(Modifier.PROTECTED)
                        paramTypes(Boolean::class.javaPrimitiveType)
                        returnType(Void.TYPE)
                    }
                }
            }
        },
        verify = { method ->
            method.declaringClass.name == HostSymbols.Classes.USER_ACCOUNT_REBORN_FRAGMENT &&
                Modifier.isPublic(method.modifiers) &&
                Modifier.isFinal(method.modifiers) &&
                method.parameterTypes.isEmpty() &&
                method.returnType == Void.TYPE
        },
    )

    val bookshelfOperationParseLambda = DexKitMethodQuery(
        id = "ui.bookshelf.operation.parse_lambda",
        purpose = "bookshelf top operation response callback by JSONObject/void and result-log contract",
        query = {
            searchPackages("com.qidian.QDReader.ui.modules.bookshelf")
            matcher {
                declaredClass(HostSymbols.Classes.BOOKSHELF_OPERATION_MANAGER)
                modifiers(Modifier.PRIVATE or Modifier.STATIC)
                paramTypes("org.json.JSONObject")
                returnType(Void.TYPE)
                addUsingString("getBookShelfOperationRes result=", StringMatchType.Contains)
            }
        },
        verify = { method ->
            method.declaringClass.name == HostSymbols.Classes.BOOKSHELF_OPERATION_MANAGER &&
                Modifier.isStatic(method.modifiers) &&
                method.parameterTypes.contentEquals(arrayOf(org.json.JSONObject::class.java)) &&
                method.returnType == Void.TYPE
        },
    )

    val bookshelfOperationNotifyLambda13 = DexKitMethodQuery(
        id = "ui.bookshelf.operation.notify.lambda_13_12",
        purpose = "bookshelf top operation clear-event callback reached from JSONObject response callback",
        query = {
            searchPackages("com.qidian.QDReader.ui.modules.bookshelf")
            matcher {
                declaredClass(HostSymbols.Classes.BOOKSHELF_OPERATION_MANAGER)
                modifiers(Modifier.PRIVATE or Modifier.STATIC)
                name(
                    "getBookShelfOperationRes\$lambda-13\$lambda-12",
                    StringMatchType.Contains,
                )
                paramTypes()
                returnType(Void.TYPE)
                addUsingString("EVENT_CLEAR_MAIN_TOP_OPERATION", StringMatchType.Equals)
            }
        },
        verify = { method ->
            method.declaringClass.name == HostSymbols.Classes.BOOKSHELF_OPERATION_MANAGER &&
                Modifier.isStatic(method.modifiers) &&
                method.parameterTypes.isEmpty() &&
                method.returnType == Void.TYPE &&
                method.name.contains("getBookShelfOperationRes\$lambda-13\$lambda-12")
        },
    )

    val bookshelfOperationNotifyLambda0 = DexKitMethodQuery(
        id = "ui.bookshelf.operation.notify.lambda_0",
        purpose = "bookshelf top operation clear-event callback reached from no-arg loader",
        query = {
            searchPackages("com.qidian.QDReader.ui.modules.bookshelf")
            matcher {
                declaredClass(HostSymbols.Classes.BOOKSHELF_OPERATION_MANAGER)
                modifiers(Modifier.PRIVATE or Modifier.STATIC)
                name("getBookShelfOperationRes\$lambda-0", StringMatchType.Contains)
                paramTypes()
                returnType(Void.TYPE)
                addUsingString("EVENT_CLEAR_MAIN_TOP_OPERATION", StringMatchType.Equals)
            }
        },
        verify = { method ->
            method.declaringClass.name == HostSymbols.Classes.BOOKSHELF_OPERATION_MANAGER &&
                Modifier.isStatic(method.modifiers) &&
                method.parameterTypes.isEmpty() &&
                method.returnType == Void.TYPE &&
                method.name.contains("getBookShelfOperationRes\$lambda-0")
        },
    )

    /**
     * SplashManager skip-ad entry `a(Activity, judian)`. The short name `a` drifts and collides with the
     * private worker `m(Activity, judian)` (identical param shape), so anchor structurally instead of by
     * name: (Activity, callback)->void where the callback is an inner interface whose single method is
     * `(int, ?, String)->void`. The public non-synthetic method is the entry `a`; the synthetic bridge to
     * `m` is filtered out. This survives obfuscation renaming of both the method and the interface.
     */
}
