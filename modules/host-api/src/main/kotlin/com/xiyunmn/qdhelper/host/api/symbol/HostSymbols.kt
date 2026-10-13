package com.xiyunmn.qdhelper.host.api.symbol

object HostSymbols {
    const val PACKAGE = "com.qidian.QDReader"

    object Classes {
        const val BASE_ACTIVITY = "com.qidian.QDReader.ui.activity.BaseActivity"
        const val SPLASH_ACTIVITY = "com.qidian.QDReader.ui.activity.SplashActivity"
        const val SPLASH_MANAGER = "com.qidian.QDReader.bll.splash.SplashManager"
        const val SPLASH_DOWNLOAD_UTIL = "com.qidian.QDReader.util.SplashDownloadUtil"
        const val MAIN_GROUP_ACTIVITY = "com.qidian.QDReader.ui.activity.MainGroupActivity"
        const val NEW_BOOK_INVEST_DETAIL_ACTIVITY =
            "com.qidian.QDReader.ui.activity.NewBookInvestDetailActivity"
        const val NEW_BOOK_INVEST_DETAIL_ENTRY =
            "com.qidian.QDReader.repository.entity.newbook.NewBookInvestDetailEntry"
        const val NEW_BOOK_INVEST_VERIFY_RISK_ENTRY =
            "com.qidian.QDReader.repository.entity.newbook.VerifyRiskEntry"
        const val UNIVERSAL_RISK_HELPER =
            "com.qidian.QDReader.component.universalverify.UniversalRiskHelper"

        const val USER_ACCOUNT_REBORN_FRAGMENT =
            "com.qidian.QDReader.ui.fragment.main_group.QDUserAccountRebornFragment"
        const val SUPER_REFRESH_LAYOUT = "com.qidian.QDReader.ui.widget.QDSuperRefreshLayout"
        const val QD_READER_ACTIVITY = "com.qidian.QDReader.ui.activity.QDReaderActivity"
        const val QD_BOOK_MARK_ITEM = "com.qidian.QDReader.repository.entity.QDBookMarkItem"
        const val QD_POPUP_WINDOW = "com.qidian.QDReader.framework.widget.popupwindow.QDPopupWindow"
        const val QD_DIRECTORY_ACTIVITY = "com.qidian.QDReader.ui.activity.QDDirectoryActivity"
        const val BOOK_ITEM = "com.qidian.QDReader.repository.entity.BookItem"
        const val QD_USER_MANAGER = "com.qidian.QDReader.component.user.QDUserManager"
        const val USER_TOKEN_ITEM = "com.qidian.QDReader.repository.entity.UserTokenItem"
        const val CHECK_IN_MANAGER = "com.qidian.QDReader.component.manager.n"
        const val RETROFIT_SCHEDULER = "com.qidian.QDReader.component.retrofit.p"
        const val QD_CONFIG = "com.qidian.common.lib.QDConfig"
        const val APPLICATION_CONTEXT = "com.qidian.common.lib.ApplicationContext"
        const val FOCK_UTIL = "com.qidian.QDReader.component.util.FockUtil"
        /** Stable host-native security diagnostics bridge in the active audit interval. */
        const val NIB_NATIVE_WRAPPER = "com.yuewen.nib.NativeWrapper"
        const val NIB_SEARCH = "com.yuewen.nib.search"
        const val NIB_STRING_ARMOR = "com.yuewen.nib.StringArmor"
        const val NIB_SUBSCRIPT = "com.yuewen.nib.Subscript"
        /** Stable vendored Tencent TuringFD class names across the active audit interval. */
        const val TURING_SDK = "com.tencent.turingfd.sdk.ams.ga.TuringSDK"
        const val TURING_CONFIG = "com.tencent.turingfd.sdk.ams.ga.Pear"
        const val TURING_RUNTIME = "com.tencent.turingfd.sdk.ams.ga.Sugarcane"
        const val TURING_NATIVE_CACHE = "com.tencent.turingfd.sdk.ams.ga.Rambutan"
        const val TURING_DETECTOR_STATE = "com.tencent.turingfd.sdk.ams.ga.Raspberry"
        const val TURING_RISK_CACHE = "com.tencent.turingfd.sdk.ams.ga.Pomelo"
        const val TURING_CERT_EXTENSION_PARSER = "com.tencent.turingfd.sdk.ams.ga.Cwhile"
        const val ATTRIBUTION_PARAMS_BUILDER = "com.qidian.QDReader.component.api.AttributionParamsBuilder"
        const val CONFIG_LOADER = "com.qidian.QDReader.component.config.judian"
        const val APP_CONFIG_RUNTIME_LOADER = "com.qidian.QDReader.component.config.AppConfig\$init\$1\$1"

        /**
         * Vendored OStar/Beacon SDK fingerprint builder. Unlike host business classes this
         * third-party package is NOT obfuscated: the FQCN is byte-identical across
         * the active audit interval, so it needs no DexKit rule. Diagnostics-only.
         */
        const val OSTAR_FINGERPRINT_BUILDER = "com.ola.star.aj.f"

        /**
         * Reading-time statistics SDK entity. Like [OSTAR_FINGERPRINT_BUILDER] this vendored
         * package is NOT obfuscated, so it is a stable structural anchor: the DAO class that
         * persists sessions drifts every release while its `boolean(ReadTimeEntity)` signature stays put.
         */
        const val READ_TIME_ENTITY = "com.yuewen.readtimestatisticssdk.entity.ReadTimeEntity"
        const val READ_TIME_REPORT_ENTITY = "com.yuewen.readtimestatisticssdk.entity.ReportEntity"

        /** SQLite table backing the reading-time SDK; used as a string anchor for the DAO. */
        const val READ_TIME_TABLE = "user_book_read_time"
        const val READ_TIME_REPORT_FLAG = "Is_report"

        /** Cloud reading-time page model; `ReadInfo` carries the displayed today/week totals. */
        const val READ_TIME_READ_INFO =
            "com.qidian.QDReader.repository.entity.readtime.ReadTimeMainPageEntity\$ReadInfoBean"
        const val READ_TIME_MAIN_PAGE_ACTIVITY = "com.qidian.QDReader.ui.activity.ReadTimeMainPageActivity"

        const val IMAGE_UPLOAD_HELPER_COMPANION = "com.qidian.QDReader.util.ImageUploadHelper\$Companion"
        const val CIRCLE_POST_EDIT_ACTIVITY = "com.qidian.QDReader.ui.activity.CirclePostEditActivity"
        const val CAPSULE_EDIT_ACTIVITY = "com.qidian.QDReader.ui.activity.capsule.CapsuleEditActivity"
        const val ABOUT_ACTIVITY = "com.qidian.QDReader.ui.activity.AboutActivity"
        const val DAILY_READING_ACTIVITY = "com.qidian.QDReader.ui.activity.DailyReadingActivity"
        const val QD_BROWSER_ACTIVITY = "com.qidian.QDReader.ui.activity.QDBrowserActivity"
        const val TAB_BROWSER_ACTIVITY = "com.qidian.QDReader.ui.activity.TabBrowserActivity"
        const val QD_BROWSER_FRAGMENT = "com.qidian.QDReader.ui.fragment.QDBrowserFragment"
        const val QD_UI_COMMON_TIP_DIALOG = "com.qd.ui.component.widget.dialog.QDUICommonTipDialog"
        const val NOTIFICATION_PERMISSION_UTIL = "com.qidian.QDReader.util.NotificationPermissionUtil"
        const val QD_DIALOG_MANAGER = "com.qidian.QDReader.ui.activity.QDDialogManager"
        const val COMMON_LIB_DIALOG_CALLBACK = "com.qidian.common.lib.g"
        const val PERSONAL_RECOMMEND_PUSH_VIEW = "com.qidian.QDReader.ui.view.PersonalRecommendPushView"
        const val RX_APP_COMPAT_ACTIVITY = "com.trello.rxlifecycle3.components.support.RxAppCompatActivity"
        const val QD_TEENAGER_MANAGER = "com.qidian.QDReader.bll.manager.QDTeenagerManager"
        const val BOOK_SHELF_CHECK_IN_VIEW = "com.qidian.QDReader.ui.modules.bookshelf.view.BookShelfCheckInView"
        const val BOOK_SHELF_VIEW_MODEL = "com.qidian.QDReader.ui.modules.bookshelf.BookShelfViewModel"
        const val BOOKSHELF_CONFIG = "com.qidian.QDReader.repository.entity.config.BookshelfConfig"
        const val BOOKSHELF_HOVER_AD_HELPER = "com.qidian.QDReader.bll.helper.BookShelfHoverAdHelper"
        const val BOOKSHELF_OPERATION_MANAGER = "com.qidian.QDReader.ui.modules.bookshelf.BookShelfOperationManager"
        const val YW_IMAGE_LOADER = "com.yuewen.component.imageloader.YWImageLoader"
        const val APP_CONFIG = "com.qidian.QDReader.component.config.AppConfig"
        const val QD_APP_CONFIG_HELPER = "com.qidian.QDReader.component.config.QDAppConfigHelper"
        const val QD_APP_CONFIG_HELPER_COMPANION = "com.qidian.QDReader.component.config.QDAppConfigHelper\$Companion"
        const val BOOT_WALL_PAPERS = "com.qidian.QDReader.repository.entity.config.BootWallPapers"
        const val CONFIG_PAPER = "com.qidian.QDReader.repository.entity.config.Paper"
        const val TX_HTTP_DNS_HELPER = "com.qidian.QDReader.component.retrofit.dns.TXHttpDnsHelper"
        const val AUDIO_TX_HTTP_DNS_HELPER = "com.qidian.QDReader.audiobook.download.AudioTXHttpDnsHelper"
        const val TENCENT_DNS_CONFIG_BUILDER = "com.tencent.msdk.dns.DnsConfig\$Builder"
        const val YW_PUSH_SDK = "com.yuewen.push.YWPushSDK"
        const val DEVICE_INFO = "com.qidian.QDReader.repository.entity.DeviceInfo"
        const val COMMON_LIB_DEVICE = "com.qidian.common.lib.d"
        const val COMMON_LIB_PREFS = "com.qidian.common.lib.util.g0"
        const val COMMON_LIB_FAKE_IDENTITY = "com.qidian.common.lib.util.h0"
        const val APP_CONFIG_BEAN = "com.qidian.QDReader.repository.entity.config.AppConfigBean"
        const val FRESHMAN_BEAN = "com.qidian.QDReader.repository.entity.config.FreshmanBean"
        const val OAID_UTIL = "com.qidian.QDReader.util.OaidUtil"
        const val OAID_SUPPLIER_IMPL = "com.bun.miitmdid.pojo.IdSupplierImpl"
        const val OSTAR_IMPL = "com.qidian.QDReader.start.entity.OStarImpl"
        const val QD_NET_UTIL = "com.qidian.QDReader.component.network.traceroute.QDNetUtil"
        const val MAINTAB_PARAM = "com.qidian.QDReader.ui.widget.maintab.b"
        const val ACCOUNT_TAB_RED_DOT_HANDLER =
            "com.qidian.QDReader.ui.fragment.main_group.AccountTabRedDotHandler"
        const val PAGER_SLIDING_TAB_STRIP = "com.qidian.QDReader.ui.widget.maintab.PagerSlidingTabStrip"
        const val BOOKSHELF_REBORN_FRAGMENT =
            "com.qidian.QDReader.ui.modules.bookshelf.QDBookShelfRebornFragment"
        const val ACCOUNT_FUNCTION_HOLDER =
            "com.qidian.QDReader.ui.viewholder.user_account.AccountFunctionHolder"
        const val MORPHING_ADAPTER = "com.qidian.morphing.MorphingAdapter"
        const val MORPHING_CARD = "com.qidian.morphing.MorphingCard"
        const val MORPHING_ITEM = "com.qidian.morphing.MorphingItem"
        const val MORPHING_FRAGMENT = "com.qidian.QDReader.ui.modules.bookstore.MorphingFragment"
        const val MORPHING_ACTIVITY = "com.qidian.QDReader.ui.modules.bookstore.MorphingActivity"
        const val BOOK_LIST_ACTIVITY = "com.qidian.QDReader.ui.activity.BookListActivity"
        const val BOOK_CATEGORY_PAGE_ACTIVITY = "com.qidian.QDReader.ui.activity.QDBookCategoryPageActivity"
        const val BOOK_FRIENDS_ACTIVITY = "com.qidian.QDReader.ui.activity.QDBookFriendsActivity"
        const val LAST_PAGE_ADAPTER = "com.qidian.QDReader.ui.adapter.lastpage.LastPageAdapter"
        const val LAST_PAGE_V4_ADAPTER = "com.qidian.QDReader.ui.adapter.lastpage.LastPageV4Adapter"
        const val BOOKSTORE_REBORN_ADAPTER =
            "com.qidian.QDReader.ui.modules.bookstore.BookStoreRebornAdapter"
        const val BOOK_ITEM_ADAPTER = "com.qidian.QDReader.ui.adapter.BookItemAdapter"
        const val QD_BOOKSTORE_ITEM_ADAPTER = "com.qidian.QDReader.ui.adapter.QDBookStoreItemAdapter"
        const val BOOKSTORE_GROUP_ADAPTER = "com.qidian.QDReader.ui.adapter.BookStoreGroupAdapter"
        const val BOOKSTORE_LIMIT_ADAPTER = "com.qidian.QDReader.ui.adapter.BookStoreLimitAdapter"
        const val QD_BOOK_RECOMMEND_ADAPTER = "com.qidian.QDReader.ui.adapter.QDBookRecommendAdapter"
        const val SAN_JIANG_ADAPTER = "com.qidian.QDReader.ui.adapter.SanJiangAdapter"
        const val SAN_JIANG_STORE_ITEM = "com.qidian.QDReader.repository.entity.SanJiangStoreItem"
        const val RANKING_RIGHT_ADAPTER = "com.qidian.QDReader.ui.adapter.RankingRightAdapter"
        const val COMIC_SQUARE_ADAPTER = "com.qidian.QDReader.ui.adapter.ComicSquareAdapter"
        const val DAILY_READING_FLUTTER_ACTIVITY =
            "com.qidian.QDReader.flutter.DailyReadingMainPageActivity"
        const val BOOKSHELF_LIST_ONLINE_VIEW_HOLDER =
            "com.qidian.QDReader.ui.viewholder.bookshelf.BookShelfListOnLineViewHolder"
        const val BOOKSHELF_NEW_LIST_BOOK_VIEW_HOLDER =
            "com.qidian.QDReader.ui.modules.bookshelf.viewholder.list.BookViewHolder"
        const val ITEM_BOOKSHELF_LIST_BINDING =
            "com.qidian.QDReader.databinding.ItemBookshelfListBinding"
        const val BOOK_SHELF_ITEM = "com.qidian.QDReader.repository.entity.BookShelfItem"
        const val BOOKSHELF_READING_TIME_VIEW =
            "com.qidian.QDReader.ui.modules.bookshelf.view.BookShelfReadingTimeView"
        const val SPLASH_CONFIG_SHARED_VIEW_MODEL =
            "com.qidian.QDReader.ui.activity.splash_config.QDSplashConfigSharedViewModel"
        const val SPLASH_CONFIG_FRAGMENT_KT = "com.qidian.QDReader.ui.activity.splash_config.QDSplashConfigFragmentKt"
        const val QDUI_BUTTON = "com.qd.ui.component.widget.QDUIButton"
        const val BOOT_PAPER = "com.qidian.QDReader.repository.entity.dressup.BootPaper"
        const val QD_CHAPTER_END_DIVIDE_PROCESSING =
            "com.qidian.QDReader.readerengine.specialline.QDChapterEndDivideProcessing"
        const val READ_MENU_DATA = "com.qidian.QDReader.repository.entity.ReadMenuData"
        const val BOOK_LAST_PAGE = "com.qidian.QDReader.repository.entity.BookLastPage"
        const val BOOK_LAST_PAGE_V4 = "com.qidian.QDReader.repository.entity.BookLastPageV4"
        const val SIMILAR_RECOMMEND = "com.qidian.QDReader.repository.entity.SimilarRecommend"
        const val CHAPTER_COMMENT_DATA_LIST =
            "com.qidian.QDReader.repository.entity.chaptercomment.NewParagraphCommentListBean\$DataListBean"
        const val CHAPTER_COMMENT_BOOK_INFO =
            "com.qidian.QDReader.repository.entity.chaptercomment.NewParagraphCommentListBean\$BookInfoBean"
        const val CHAPTER_COMMENT_MAIN_HOLDER =
            "com.qidian.QDReader.ui.viewholder.chaptercomment.list.NewParagraphCommentListMainViewHolder"
        const val CHAPTER_COMMENT_REPLY_HOLDER =
            "com.qidian.QDReader.ui.viewholder.chaptercomment.list.NewParagraphCommentListReplyViewHolder"
        const val CHAPTER_COMMENT_HOT_IMAGE_HOLDER =
            "com.qidian.QDReader.ui.viewholder.chaptercomment.list.NewParagraphCommentListHotImageViewHolder"
        const val MESSAGE_TEXT_VIEW = "com.qd.ui.component.widget.textview.MessageTextView"
        const val SPOILER_ACTION = "com.qidian.QDReader.paging.SpoilerAction"
        const val CHAPTER_COMMENT_POP_VIEW = "com.qidian.QDReader.ui.view.NewParagraphPopView"
        const val SHARE_POLICY_BEAN = "com.qidian.QDReader.repository.entity.share.SharePolicyBean"
        const val UGC_BASE_ITEM = "com.qidian.QDReader.repository.entity.richtext.base.UGCBaseItem"
        const val UGC_ITEM_VIEW_HOLDER =
            "com.qidian.QDReader.ui.viewholder.QDUGCUiComponent\$QDUGCItemViewHolder"
        const val RICH_TEXT_ADAPTER = "com.qidian.QDReader.ui.adapter.RichTextAdapter"
        const val CIRCLE_POST_DETAIL_ADAPTER =
            "com.qidian.QDReader.ui.adapter.circle.CirclePostDetailAdapter"
        const val RICH_TEXT_TITLE_VIEW_HOLDER =
            "com.qidian.QDReader.ui.viewholder.richtext.RichTextTitleViewHolder"
        const val RICH_TEXT_HTML_VIEW_HOLDER =
            "com.qidian.QDReader.ui.viewholder.richtext.RichTextHtmlViewHolder"
        const val RICH_CONTENT_TEXT_VIEW = "com.qidian.richtext.RichContentTextView"
        const val BOOK_DETAIL_ACTIVITY = "com.qidian.QDReader.ui.activity.QDBookDetailActivity"
        const val BOOK_DETAIL = "com.qidian.QDReader.repository.entity.BookDetail"
        const val BASE_BOOK_INFO = "com.qidian.QDReader.repository.entity.BaseBookInfo"
        const val BOOK_CIRCLE_MODULE_VIEW = "com.qidian.QDReader.ui.view.BookCircleModuleView"
        const val BOOK_AUTHOR_MODULE_VIEW = "com.qidian.QDReader.ui.view.BookAuthorModuleView"
        const val BOOK_LOOK_FOR_MODULE_VIEW = "com.qidian.QDReader.ui.view.BookLookForModuleView"
        const val BOOK_RECOMMEND_MODULE_VIEW = "com.qidian.QDReader.ui.view.BookRecommendModuleView"
        const val BOOK_FANS_MODULE_VIEW = "com.qidian.QDReader.ui.view.BookFansModuleView"
        const val AD_VIEW = "com.qidian.QDReader.ui.view.AdView"
        const val MORPHING_WIDGET_DATA = "com.qidian.morphing.MorphingWidgetData"
        const val BOOK_ROLE_ADAPTER =
            "com.qidian.QDReader.ui.view.BookRoleModuleView\$RoleAdapter"
        const val BOOK_ROLE_LIST_PAGE_ADAPTER =
            "com.qidian.QDReader.ui.activity.BookRoleListActivity\$search"
        const val BOOK_ROLE_LIST_ADAPTER = "com.qidian.QDReader.ui.adapter.RoleListAdapter"
        const val ROLE_ITEM = "com.qidian.QDReader.repository.entity.role.RoleItem"
        const val HOME_PAGE_INFO_ACTIVITY = "com.qidian.QDReader.ui.activity.QDHomePageInfoActivity"
        const val HOME_PAGE_ADAPTER = "com.qidian.QDReader.ui.adapter.QDHomePageAdapter"
        const val HOME_PAGE_DATA = "com.qidian.QDReader.repository.entity.homepage.HomePageData"
        const val HOME_PAGE_ITEM = "com.qidian.QDReader.repository.entity.homepage.HomePageItem"
        const val CAPSULE_LIST_BEAN = "com.qidian.QDReader.repository.entity.homepage.CapsuleListBean"
        const val ANCHOR_SECOND_PAGE_BOOK_INFO_BEAN =
            "com.qidian.QDReader.repository.entity.homepage.AnchorSecondPageBookInfoBean"
        const val MY_COLLECTION_CAPSULE_ENTITY =
            "com.qidian.QDReader.repository.entity.MyCollectionCapsuleEntity"
        const val MY_COLLECTION_CAPSULE_ITEM =
            "com.qidian.QDReader.repository.entity.MyCollectionCapsuleItem"
        const val HOME_PAGE_AUTHOR_INFO = "com.qidian.QDReader.repository.entity.homepage.AuthorInfoBean"
        const val HOME_PAGE_USER_INFO = "com.qidian.QDReader.repository.entity.homepage.UserInfoBean"
        const val HOME_PAGE_PERSONAL_USER_INFO_HOLDER =
            "com.qidian.QDReader.ui.viewholder.author.QDHomePagePersonalUserInfoHolder"
        const val ACTION_URL_PROCESS = "com.qidian.QDReader.other.ActionUrlProcess"
        const val SERVER_RESPONSE = "com.qidian.QDReader.repository.entity.ServerResponse"
        const val REWARD_RESULT_BEAN = "com.qidian.QDReader.repository.entity.RewardResultBean"
        const val VERIFY_RESULT = "com.qidian.QDReader.repository.entity.VerifyResult"
        const val TENCENT_WEBVIEW = "com.tencent.smtt.sdk.WebView"
        const val TENCENT_VALUE_CALLBACK = "com.tencent.smtt.sdk.ValueCallback"
        const val RETROFIT_RESPONSE = "retrofit2.Response"
        const val HOME_PAGE_CHAPTER_REVIEWS_ACTIVITY =
            "com.qidian.QDReader.ui.activity.QDHomePageChatperReviewsActivity"
        const val HOME_PAGE_BOOK_REVIEWS_ACTIVITY =
            "com.qidian.QDReader.ui.activity.QDHomePageBookReviewsActivity"
        const val HOME_PAGE_COLUMNS_ACTIVITY =
            "com.qidian.QDReader.ui.activity.QDHomePageColumnsActivity"
        const val RECOM_BOOK_LIST_MORE_ACTIVITY =
            "com.qidian.QDReader.ui.activity.RecomBookListMoreDataActivity"
        const val RECOM_BOOK_LIST_SIMPLE_ITEM =
            "com.qidian.QDReader.repository.entity.RecomBookListSimpleItem"
        const val AUDIO_INFO_BEAN =
            "com.qidian.QDReader.repository.entity.homepage.AudioInfoBean"
        const val QD_USER_FOLLOW_BEAN =
            "com.qidian.QDReader.repository.entity.follow.QDUserFollowBean"
        const val FOLLOW_FEED_ITEM = "com.qidian.QDReader.repository.entity.FollowFeedItem"
        const val FOLLOW_CONTENT_MODULE = "com.qidian.QDReader.repository.entity.FollowContentModule"
        const val ANDROIDX_RECYCLER_VIEW_HOLDER = "androidx.recyclerview.widget.RecyclerView\$ViewHolder"
        const val RECYCLER_HOLDER = "com.qd.ui.component.widget.recycler.base.RecyclerHolder"
        const val QDUI_COLLAPSED_TEXT_VIEW = "com.qd.ui.component.widget.textview.QDUICollapsedTextView"
        const val QD_FANS_USER_VALUE = "com.qidian.QDReader.repository.entity.QDFansUserValue"
        const val FANS_LIST_BOTTOM_VIEW = "com.qidian.QDReader.ui.view.FansListBottomView"
        const val BOOK_FANS_BOTTOM_VIEW = "com.qidian.QDReader.ui.view.BookFansBottomView"
        const val WELFARE_FLUTTER_ACTIVITY = "com.qidian.QDReader.flutter.WelfareFlutterActivity"
        const val GDT_FOR_QD = "com.qidian.QDReader.extras.GDTForQD"
        const val TANGRAM_REWARD_VIDEO_ACTIVITY = "com.qq.e.tg.RewardvideoPortraitADActivity"
        const val TANGRAM_REWARD_AD = "com.qq.e.tg.rewardAD.TangramRewardAD"
        const val TANGRAM_REWARD_LISTENER = "com.qq.e.tg.rewardAD.TangramRewardADListener"
        const val TANGRAM_REWARD_RESULT = "com.qq.e.tg.rewardAD.RewardResult"
        const val QQMINI_AD_REWARD_VIDEO_VIEW = "com.tencent.qqmini.ad.proxyimpl.AdProxyDefault\$RewardVideoView"
        const val WELFARE_AD_EXTRA = "com.qidian.QDReader.extras.p0"
        const val QD_RETROFIT_CLIENT =
            "com.qidian.QDReader.framework.network.retrofit.QDRetrofitClient"
        const val COVER_URL_HELPER = "com.qd.ui.component.util.cihai"
        const val RX_OBSERVABLE = "io.reactivex.r"
        const val RX_OBSERVABLE_SOURCE = "io.reactivex.w"
        const val X5_WEB_SETTINGS = "com.tencent.smtt.sdk.WebSettings"
        const val X5_COOKIE_MANAGER = "com.tencent.smtt.sdk.CookieManager"
        const val READER_FONT_MANAGER = "com.qidian.QDReader.component.fonts.netfont.ReaderFontManager"
        const val READER_ENGINE_MANAGER = "com.qidian.QDReader.readerengine.manager.c"
        const val FLUTTER_CHANNEL_DELEGATE = "com.qidian.QDReader.flutter.bridges.ChannelDelegate"
        const val FLUTTER_NATIVE_FONT_API_IMPL = "com.qidian.QDReader.flutter.bridges.NativeFontApiImp"
        const val FLUTTER_ENGINE = "io.flutter.embedding.engine.FlutterEngine"
        // Vendored Flutter API and unobfuscated UI types, checked on 7.9.469/470/472/474/476.
        const val FLUTTER_VIEW = "io.flutter.embedding.android.FlutterView"
        const val FLUTTER_RENDERER = "io.flutter.embedding.engine.renderer.FlutterRenderer"
        const val FLUTTER_VIEWPORT_METRICS = "$FLUTTER_RENDERER\$ViewportMetrics"
        const val ANDROIDX_RECYCLER_VIEW = "androidx.recyclerview.widget.RecyclerView"
        const val ANDROIDX_NESTED_SCROLL_VIEW = "androidx.core.widget.NestedScrollView"
        const val QD_NESTED_SCROLL_VIEW = "com.qidian.QDReader.ui.widget.material.NestedScrollView"
        const val QD_FONT_TEXT_VIEW = "com.qd.component.skin.font.QDFontTextView"
        const val QD_FONT_EDIT_TEXT = "com.qd.component.skin.font.QDFontEditText"
        const val QD_FONT_RADIO_BUTTON = "com.qd.component.skin.font.QDFontRadioButton"
        const val QD_FONT_BUTTON = "com.qd.component.skin.font.QDFontAppCompatButton"
        const val READER_FONT_PANEL = "com.qidian.QDReader.readerengine.view.menu.ReadMenuFontPanel"
        const val READER_FONT_PANEL_ADAPTER = "$READER_FONT_PANEL\$FontAdapter"
        const val READER_FONT_MATCHER = "com.qidian.QDReader.readerengine.font.search"
        const val READER_FONT_ITEM = "com.qidian.QDReader.component.fonts.netfont.FontItem"
        const val READER_THEME = "com.qidian.QDReader.readerengine.theme.e"
        const val QD_THEME_MANAGER = "com.qidian.QDReader.component.app.theme.QDThemeManager"
        const val READER_THEME_ENTITY = "com.qidian.QDReader.repository.dal.store.ReaderThemeEntity"
        const val QD_READER_THEME_LIST_ADAPTER =
            "com.qidian.QDReader.ui.activity.QDReaderThemeListActivity\$ThemeListAdapter"
        const val QD_READER_THEME_MORE_ADAPTER =
            "com.qidian.QDReader.ui.activity.QDReaderThemeMoreActivity\$ThemeListAdapter"
        const val QD_READER_THEME_DETAIL_ACTIVITY = "com.qidian.QDReader.ui.activity.QDReaderThemeDetailActivity"
        const val OTTO_BUS = "com.squareup.otto.judian"
        const val QD_SUPER_ENGINE_VIEW = "com.qidian.QDReader.readerengine.view.QDSuperEngineView"
        const val UNIVERSAL_VERIFY_HELPER = "com.qidian.QDReader.component.universalverify.e"
        const val WEBVIEW_PLUGIN_J1 = "com.qidian.QDReader.webview.plugins.j1"
        const val WEBVIEW_PLUGIN_V1 = "com.qidian.QDReader.webview.plugins.v1"
        const val REWARD_VIDEO_CONFIG = "com.qidian.QDReader.repository.entity.config.RewardVideoConfig"
        const val WEBVIEW_PLUGIN = "com.qidian.QDReader.framework.webview.m"
    }

    fun isTargetPackage(packageName: String): Boolean {
        return packageName == PACKAGE
    }

    fun isMainProcess(processName: String?): Boolean {
        return processName.isNullOrBlank() || processName == PACKAGE
    }

    fun isMiniProcess(processName: String?): Boolean {
        return !processName.isNullOrBlank() && processName.startsWith("$PACKAGE:mini")
    }

    fun processLabel(processName: String?): String {
        return if (processName.isNullOrBlank()) PACKAGE else processName
    }

    fun classLabel(className: String): String {
        return when (className) {
            Classes.BASE_ACTIVITY -> "BaseActivity"
            Classes.SPLASH_ACTIVITY -> "SplashActivity"
            Classes.SPLASH_MANAGER -> "SplashManager"
            Classes.SPLASH_DOWNLOAD_UTIL -> "SplashDownloadUtil"
            Classes.MAIN_GROUP_ACTIVITY -> "MainGroupActivity"
            Classes.QD_READER_ACTIVITY -> "QDReaderActivity"
            Classes.QD_DIRECTORY_ACTIVITY -> "QDDirectoryActivity"
            Classes.BOOK_ITEM -> "BookItem"
            Classes.QD_USER_MANAGER -> "QDUserManager"
            Classes.QD_CONFIG -> "QDConfig"
            Classes.FOCK_UTIL -> "FockUtil"
            Classes.ABOUT_ACTIVITY -> "AboutActivity"
            Classes.DAILY_READING_ACTIVITY -> "DailyReadingActivity"
            Classes.QD_BROWSER_ACTIVITY -> "QDBrowserActivity"
            Classes.NOTIFICATION_PERMISSION_UTIL -> "NotificationPermissionUtil"
            Classes.PERSONAL_RECOMMEND_PUSH_VIEW -> "PersonalRecommendPushView"
            Classes.RX_APP_COMPAT_ACTIVITY -> "RxAppCompatActivity"
            Classes.QD_TEENAGER_MANAGER -> "QDTeenagerManager"
            Classes.BOOK_SHELF_CHECK_IN_VIEW -> "BookShelfCheckInView"
            Classes.BOOK_SHELF_VIEW_MODEL -> "BookShelfViewModel"
            Classes.BOOKSHELF_CONFIG -> "BookshelfConfig"
            Classes.BOOKSHELF_HOVER_AD_HELPER -> "BookShelfHoverAdHelper"
            Classes.BOOKSHELF_OPERATION_MANAGER -> "BookShelfOperationManager"
            Classes.YW_IMAGE_LOADER -> "YWImageLoader"
            Classes.APP_CONFIG -> "AppConfig"
            Classes.QD_APP_CONFIG_HELPER -> "QDAppConfigHelper"
            Classes.QD_APP_CONFIG_HELPER_COMPANION -> "QDAppConfigHelper.Companion"
            Classes.BOOT_WALL_PAPERS -> "BootWallPapers"
            Classes.CONFIG_PAPER -> "ConfigPaper"
            Classes.TX_HTTP_DNS_HELPER -> "TXHttpDnsHelper"
            Classes.AUDIO_TX_HTTP_DNS_HELPER -> "AudioTXHttpDnsHelper"
            Classes.TENCENT_DNS_CONFIG_BUILDER -> "DnsConfig.Builder"
            Classes.YW_PUSH_SDK -> "YWPushSDK"
            Classes.DEVICE_INFO -> "DeviceInfo"
            Classes.COMMON_LIB_DEVICE -> "CommonDevice"
            Classes.COMMON_LIB_PREFS -> "CommonPrefs"
            Classes.COMMON_LIB_FAKE_IDENTITY -> "FakeIdentityHelper"
            Classes.MAINTAB_PARAM -> "MainTabParam"
            Classes.ACCOUNT_TAB_RED_DOT_HANDLER -> "AccountTabRedDotHandler"
            Classes.PAGER_SLIDING_TAB_STRIP -> "PagerSlidingTabStrip"
            Classes.BOOKSHELF_REBORN_FRAGMENT -> "QDBookShelfRebornFragment"
            Classes.BOOKSHELF_LIST_ONLINE_VIEW_HOLDER -> "BookShelfListOnLineViewHolder"
            Classes.BOOKSHELF_NEW_LIST_BOOK_VIEW_HOLDER -> "BookViewHolder"
            Classes.BOOKSHELF_READING_TIME_VIEW -> "BookShelfReadingTimeView"
            Classes.SPLASH_CONFIG_SHARED_VIEW_MODEL -> "QDSplashConfigSharedViewModel"
            Classes.SPLASH_CONFIG_FRAGMENT_KT -> "QDSplashConfigFragmentKt"
            Classes.QDUI_BUTTON -> "QDUIButton"
            Classes.BOOT_PAPER -> "BootPaper"
            Classes.QD_CHAPTER_END_DIVIDE_PROCESSING -> "QDChapterEndDivideProcessing"
            Classes.READ_MENU_DATA -> "ReadMenuData"
            Classes.BOOK_LAST_PAGE -> "BookLastPage"
            Classes.BOOK_LAST_PAGE_V4 -> "BookLastPageV4"
            Classes.WELFARE_FLUTTER_ACTIVITY -> "WelfareFlutterActivity"
            Classes.GDT_FOR_QD -> "GDTForQD"
            Classes.TANGRAM_REWARD_VIDEO_ACTIVITY -> "TangramRewardVideoActivity"
            Classes.QQMINI_AD_REWARD_VIDEO_VIEW -> "QqMiniRewardVideoView"
            Classes.WELFARE_AD_EXTRA -> "WelfareAdExtra"
            Classes.WEBVIEW_PLUGIN -> "WebViewPlugin"
            else -> className.substringAfterLast('.')
        }
    }

    fun trackedActivityLabel(className: String): String? {
        return when (className) {
            Classes.SPLASH_ACTIVITY -> "SplashActivity"
            Classes.MAIN_GROUP_ACTIVITY -> "MainGroupActivity"
            Classes.QD_READER_ACTIVITY -> "QDReaderActivity"
            Classes.QD_DIRECTORY_ACTIVITY -> "QDDirectoryActivity"
            Classes.QD_BROWSER_ACTIVITY -> "QDBrowserActivity"
            Classes.CIRCLE_POST_EDIT_ACTIVITY -> "CirclePostEditActivity"
            Classes.CAPSULE_EDIT_ACTIVITY -> "CapsuleEditActivity"
            else -> null
        }
    }
}
