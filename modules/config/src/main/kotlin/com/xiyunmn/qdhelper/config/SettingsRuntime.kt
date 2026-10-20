package com.xiyunmn.qdhelper.config

import android.content.Context
import android.content.SharedPreferences
import com.xiyunmn.qdhelper.config.schema.SettingSchemaCatalog

object SettingsRuntime {
    @Volatile
    private var appContext: Context? = null

    fun initialize(context: Context) {
        val application = context.applicationContext ?: context
        appContext = application
        ConfigRuntime.initialize(application)
        RuntimeStateMigrationV1.migrate()
        migrateLegacyBookShieldLocations(application)
        BookShieldRuleRepository.initialize(application)
    }

    fun clearRuntimeContext() {
        appContext = null
        BookShieldRuleRepository.clearRuntime()
        ConfigRuntime.clear()
    }

    fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(SettingIds.PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isModuleEnabled(context: Context? = null): Boolean {
        context?.let(ConfigRuntime::initialize)
        return ConfigRuntime.readBoolean(SettingIds.KEY_MODULE_ENABLED)
    }

    fun isReaderWatermarkBlockEnabled(context: Context? = null): Boolean {
        return featureEnabled(context, SettingIds.KEY_DISABLE_READER_WATERMARK)
    }

    fun isUploadWatermarkBlockEnabled(context: Context? = null): Boolean {
        return featureEnabled(context, SettingIds.KEY_DISABLE_UPLOAD_WATERMARK)
    }

    fun isSplashAdBlockEnabled(context: Context? = null): Boolean {
        return featureEnabled(context, SettingIds.KEY_BLOCK_SPLASH_AD)
    }

    fun isSplashImageSkipEnabled(context: Context? = null): Boolean {
        return featureEnabled(context, SettingIds.KEY_SKIP_SPLASH_IMAGE)
    }

    fun isLocalSplashImageEnabled(context: Context? = null): Boolean {
        return featureEnabled(context, SettingIds.KEY_LOCAL_SPLASH_IMAGE_ENABLED)
    }

    fun localSplashImagePath(context: Context? = null): String {
        return prefsOrNull(context)?.getString(SettingIds.KEY_LOCAL_SPLASH_IMAGE_PATH, "") ?: ""
    }

    fun isCustomSplashImageEnabled(context: Context? = null): Boolean {
        return featureEnabled(context, SettingIds.KEY_CUSTOM_SPLASH_IMAGE_ENABLED)
    }

    fun customSplashImageUrl(context: Context? = null): String {
        return prefsOrNull(context)?.getString(SettingIds.KEY_CUSTOM_SPLASH_IMAGE_URL, "") ?: ""
    }

    fun isCustomSplashImageDarkLogoEnabled(context: Context? = null): Boolean {
        return featureEnabled(context, SettingIds.KEY_CUSTOM_SPLASH_IMAGE_DARK_LOGO)
    }

    fun isOfficialSplashImageEnabled(context: Context? = null): Boolean {
        return featureEnabled(context, SettingIds.KEY_OFFICIAL_SPLASH_IMAGE_ENABLED)
    }

    fun officialSplashImageUrl(context: Context? = null): String {
        return prefsOrNull(context)?.getString(SettingIds.KEY_OFFICIAL_SPLASH_IMAGE_URL, "") ?: ""
    }

    fun officialSplashImagePaperId(context: Context? = null): Long? {
        return prefsOrNull(context)
            ?.getString(SettingIds.KEY_OFFICIAL_SPLASH_IMAGE_PAPER_ID, "")
            ?.trim()
            ?.toLongOrNull()
            ?.takeIf { it > 0L }
    }

    fun isOfficialSplashImageLocalUnlockEnabled(context: Context? = null): Boolean {
        return featureEnabled(context, SettingIds.KEY_OFFICIAL_SPLASH_IMAGE_LOCAL_UNLOCK_ENABLED)
    }

    fun localOfficialSplashImageUrl(context: Context? = null): String {
        context?.let(ConfigRuntime::initialize)
        return ConfigRuntime.readString(SettingIds.KEY_OFFICIAL_SPLASH_IMAGE_LOCAL_URL)
    }

    fun localOfficialSplashImagePaperId(context: Context? = null): Long? {
        context?.let(ConfigRuntime::initialize)
        return ConfigRuntime.readLong(SettingIds.KEY_OFFICIAL_SPLASH_IMAGE_LOCAL_PAPER_ID)
            .takeIf { it > 0L }
    }

    fun isLocalOfficialSplashImageDarkLogoEnabled(context: Context? = null): Boolean {
        return featureEnabled(context, SettingIds.KEY_OFFICIAL_SPLASH_IMAGE_LOCAL_DARK_LOGO)
    }

    fun isStartupReportBlockEnabled(context: Context? = null): Boolean {
        return featureEnabled(context, SettingIds.KEY_DISABLE_STARTUP_REPORT)
    }

    fun isTrackDeviceInfoBlockEnabled(context: Context? = null): Boolean {
        return featureEnabled(context, SettingIds.KEY_DISABLE_TRACK_DEVICE_INFO)
    }

    fun isFockReportBlockEnabled(context: Context? = null): Boolean {
        return featureEnabled(context, SettingIds.KEY_DISABLE_FOCK_REPORT)
    }

    fun isDownloadGameBlockEnabled(context: Context? = null): Boolean {
        return featureEnabled(context, SettingIds.KEY_DISABLE_DOWNLOAD_GAME)
    }

    fun isPrivacyVersionDialogSuppressed(context: Context? = null): Boolean {
        return featureEnabled(context, SettingIds.KEY_SUPPRESS_PRIVACY_VERSION_DIALOG)
    }

    fun isUpdateCheckSuppressed(context: Context? = null): Boolean {
        return featureEnabled(context, SettingIds.KEY_SUPPRESS_UPDATE_CHECK)
    }

    fun isNotificationPermissionSuppressed(context: Context? = null): Boolean {
        return featureEnabled(context, SettingIds.KEY_SUPPRESS_NOTIFICATION_PERMISSION)
    }

    fun isTeenagerEnterDialogSuppressed(context: Context? = null): Boolean {
        return featureEnabled(context, SettingIds.KEY_SUPPRESS_TEENAGER_ENTER_DIALOG)
    }

    fun isChapterEndCleanEnabled(context: Context? = null): Boolean {
        return featureEnabled(context, SettingIds.KEY_CLEAN_CHAPTER_END)
    }

    fun isLastPageCleanEnabled(context: Context? = null): Boolean {
        return featureEnabled(context, SettingIds.KEY_CLEAN_LAST_PAGE)
    }

    fun isReaderToolbarAdHidden(context: Context? = null): Boolean {
        return featureEnabled(context, SettingIds.KEY_HIDE_READER_TOOLBAR_AD)
    }

    fun isFreeCopyEnabled(context: Context? = null): Boolean {
        return featureEnabled(context, SettingIds.KEY_FREE_COPY_ENABLED)
    }

    fun freeCopyMode(context: Context? = null): String {
        val stored = prefsOrNull(context)?.getString(SettingIds.KEY_FREE_COPY_MODE, SettingIds.FREE_COPY_MODE_LONG_PRESS)
        return stored.takeIf { it == SettingIds.FREE_COPY_MODE_LONG_PRESS || it == SettingIds.FREE_COPY_MODE_MENU_BUTTON }
            ?: SettingIds.FREE_COPY_MODE_LONG_PRESS
    }

    fun isFreeCopyMenuButtonMode(context: Context? = null): Boolean {
        return isFreeCopyEnabled(context) && freeCopyMode(context) == SettingIds.FREE_COPY_MODE_MENU_BUTTON
    }

    fun isReaderBodyCopyEnabled(context: Context? = null): Boolean {
        return featureEnabled(context, SettingIds.KEY_READER_BODY_COPY_ENABLED)
    }

    fun paragraphActionOrder(context: Context? = null): List<String> {
        return orderedKeys(context, SettingIds.KEY_PARAGRAPH_ACTION_ORDER, SettingCollections.PARAGRAPH_ACTION_ITEMS)
    }

    fun isChapterCommentShareLimitRemoved(context: Context? = null): Boolean {
        return featureEnabled(context, SettingIds.KEY_REMOVE_CHAPTER_COMMENT_SHARE_LIMIT)
    }

    fun isCircleFreeCopyEnabled(context: Context? = null): Boolean {
        return featureEnabled(context, SettingIds.KEY_CIRCLE_FREE_COPY_ENABLED)
    }

    fun isBookDetailIntroFreeCopyEnabled(context: Context? = null): Boolean {
        return featureEnabled(context, SettingIds.KEY_BOOK_DETAIL_INTRO_FREE_COPY_ENABLED)
    }

    fun isBookDetailFansRankEnabled(context: Context? = null): Boolean {
        return featureEnabled(context, SettingIds.KEY_BOOK_DETAIL_FANS_RANK_ENABLED)
    }

    fun isBookDetailCoverOriginalEnabled(context: Context? = null): Boolean {
        return featureEnabled(context, SettingIds.KEY_BOOK_DETAIL_COVER_ORIGINAL_ENABLED)
    }

    fun isBookDetailRoleAvatarOriginalEnabled(context: Context? = null): Boolean {
        return featureEnabled(context, SettingIds.KEY_BOOK_DETAIL_ROLE_AVATAR_ORIGINAL_ENABLED)
    }

    fun isCardPlazaLocalUnlockEnabled(context: Context? = null): Boolean {
        return featureEnabled(context, SettingIds.KEY_CARD_PLAZA_LOCAL_UNLOCK_ENABLED)
    }

    fun isCardPreviewLongPressSaveEnabled(context: Context? = null): Boolean {
        return featureEnabled(context, SettingIds.KEY_CARD_PREVIEW_LONG_PRESS_SAVE_ENABLED)
    }

    fun isProfileAvatarOriginalEnabled(context: Context? = null): Boolean {
        return featureEnabled(context, SettingIds.KEY_PROFILE_AVATAR_ORIGINAL_ENABLED)
    }

    fun isProfileUserIdEnabled(context: Context? = null): Boolean {
        return featureEnabled(context, SettingIds.KEY_PROFILE_USER_ID_ENABLED)
    }

    fun isProfilePrivacySecondPageRestoreEnabled(context: Context? = null): Boolean = featureEnabled(context, SettingIds.KEY_PROFILE_PRIVACY_SECOND_PAGE_RESTORE_ENABLED)

    fun isCustomReaderFontEnabled(context: Context? = null): Boolean {
        return featureEnabled(context, SettingIds.KEY_CUSTOM_READER_FONT_ENABLED)
    }

    fun isReaderBackgroundLocalUnlockEnabled(context: Context? = null): Boolean {
        return featureEnabled(context, SettingIds.KEY_READER_BACKGROUND_LOCAL_UNLOCK_ENABLED)
    }

    fun isReaderBackgroundLongPressSaveEnabled(context: Context? = null): Boolean {
        return featureEnabled(context, SettingIds.KEY_READER_BACKGROUND_LONG_PRESS_SAVE_ENABLED)
    }

    fun customReaderFontPath(context: Context? = null): String {
        return prefsOrNull(context)?.getString(SettingIds.KEY_CUSTOM_READER_FONT_PATH, "")?.trim().orEmpty()
    }

    fun isCustomGlobalFontEnabled(context: Context? = null): Boolean {
        return featureEnabled(context, SettingIds.KEY_CUSTOM_GLOBAL_FONT_ENABLED)
    }

    fun isBookshelfNightModeSameAsDayEnabled(context: Context? = null): Boolean {
        return featureEnabled(context, SettingIds.KEY_BOOKSHELF_NIGHT_MODE_SAME_AS_DAY)
    }

    fun isBookshelfHoverAdHidden(context: Context? = null): Boolean {
        return featureEnabled(context, SettingIds.KEY_HIDE_BOOKSHELF_HOVER_AD)
    }

    fun isBottomTabRedDotHidden(context: Context? = null): Boolean {
        return featureEnabled(context, SettingIds.KEY_HIDE_BOTTOM_TAB_RED_DOT)
    }

    fun isAccountTabRedDotHidden(context: Context? = null): Boolean {
        return featureEnabled(context, SettingIds.KEY_HIDE_ACCOUNT_TAB_RED_DOT)
    }

    fun isDailyReadingGameAdHidden(context: Context? = null): Boolean {
        return featureEnabled(context, SettingIds.KEY_HIDE_DAILY_READING_GAME_AD)
    }

    fun isBookshelfBookActivityTagHidden(context: Context? = null): Boolean {
        return featureEnabled(context, SettingIds.KEY_HIDE_BOOKSHELF_BOOK_ACTIVITY_TAG)
    }

    fun isBookshelfUpdateNoticeHidden(context: Context? = null): Boolean {
        return featureEnabled(context, SettingIds.KEY_HIDE_BOOKSHELF_UPDATE_NOTICE)
    }

    fun isBookshelfReadingTimeCardHidden(context: Context? = null): Boolean {
        return featureEnabled(context, SettingIds.KEY_HIDE_BOOKSHELF_READING_TIME_CARD)
    }

    fun isBookshelfTopActivityAdHidden(context: Context? = null): Boolean {
        return featureEnabled(context, SettingIds.KEY_HIDE_BOOKSHELF_TOP_ACTIVITY_AD)
    }

    fun isBookshelfLeftTopActivityCardHidden(context: Context? = null): Boolean {
        return featureEnabled(context, SettingIds.KEY_HIDE_BOOKSHELF_LEFT_TOP_ACTIVITY_CARD)
    }

    fun isBottomTabCustomEnabled(context: Context? = null): Boolean {
        return featureEnabled(context, SettingIds.KEY_BOTTOM_TAB_CUSTOM_ENABLED)
    }

    fun isBottomTabHideActivityEnabled(context: Context? = null): Boolean {
        return featureEnabled(context, SettingIds.KEY_BOTTOM_TAB_HIDE_ACTIVITY)
    }

    fun bottomTabOrder(context: Context? = null): List<String> {
        return orderedKeys(context, SettingIds.KEY_BOTTOM_TAB_ORDER, SettingCollections.BOTTOM_TAB_ITEMS)
    }

    fun bottomTabHidden(context: Context? = null): Set<String> {
        return hiddenKeys(context, SettingIds.KEY_BOTTOM_TAB_HIDDEN, SettingCollections.BOTTOM_TAB_ITEMS)
    }

    fun isHomeTopActionCustomEnabled(context: Context? = null): Boolean {
        return featureEnabled(context, SettingIds.KEY_HOME_TOP_ACTION_CUSTOM_ENABLED)
    }

    fun homeTopActionOrder(context: Context? = null): List<String> {
        return orderedKeys(context, SettingIds.KEY_HOME_TOP_ACTION_ORDER, SettingCollections.HOME_TOP_ACTION_ITEMS)
    }

    fun homeTopActionHidden(context: Context? = null): Set<String> {
        return hiddenKeys(context, SettingIds.KEY_HOME_TOP_ACTION_HIDDEN, SettingCollections.HOME_TOP_ACTION_ITEMS)
    }

    fun isMinePageCustomEnabled(context: Context? = null): Boolean {
        return featureEnabled(context, SettingIds.KEY_MINE_PAGE_CUSTOM_ENABLED)
    }

    fun isMinePageAutoRefreshDisabled(context: Context? = null): Boolean {
        return featureEnabled(context, SettingIds.KEY_DISABLE_MINE_PAGE_AUTO_REFRESH)
    }

    fun isSelectionPageCustomEnabled(context: Context? = null): Boolean {
        return featureEnabled(context, SettingIds.KEY_SELECTION_PAGE_CUSTOM_ENABLED)
    }

    fun isSelectionGuessWaterfallHidden(context: Context? = null): Boolean {
        return isSelectionPageCustomEnabled(context) &&
            featureEnabled(context, SettingIds.KEY_SELECTION_PAGE_CUSTOM_HIDE_GUESS)
    }

    fun isBookDetailPageCustomEnabled(context: Context? = null): Boolean {
        return featureEnabled(context, SettingIds.KEY_BOOK_DETAIL_PAGE_CUSTOM_ENABLED)
    }

    fun isAutoSignInEnabled(context: Context? = null): Boolean {
        return featureEnabled(context, SettingIds.KEY_AUTO_SIGN_IN_ENABLED)
    }

    fun isAutoInvestEnabled(context: Context? = null): Boolean {
        return featureEnabled(context, SettingIds.KEY_AUTO_INVEST_ENABLED)
    }

    fun isReadTimeMultiplierEnabled(context: Context? = null): Boolean {
        return featureEnabled(context, SettingIds.KEY_READ_TIME_MULTIPLIER_ENABLED)
    }

    fun isReadTimeLocalDisplayRealEnabled(context: Context? = null): Boolean {
        return featureEnabled(context, SettingIds.KEY_READ_TIME_LOCAL_DISPLAY_REAL)
    }

    /** Raw multiplier text; parsing and range validation belong to the read-time feature. */
    fun readTimeMultiplierRaw(context: Context? = null): String {
        return prefsOrNull(context)
            ?.getString(SettingIds.KEY_READ_TIME_MULTIPLIER_VALUE, "")
            .orEmpty()
    }

    fun readTimeAuditState(context: Context? = null): String {
        context?.let(ConfigRuntime::initialize)
        return ConfigRuntime.readString(SettingIds.RUNTIME_READ_TIME_AUDIT_STATE)
    }

    fun putReadTimeAuditState(context: Context, value: String): Boolean {
        ConfigRuntime.initialize(context)
        return ConfigRuntime.putString(SettingIds.RUNTIME_READ_TIME_AUDIT_STATE, value)
    }

    fun readTimeCloudAuditState(context: Context? = null): String {
        context?.let(ConfigRuntime::initialize)
        return ConfigRuntime.readString(SettingIds.RUNTIME_READ_TIME_CLOUD_AUDIT_STATE)
    }

    fun putReadTimeCloudAuditState(context: Context, value: String): Boolean {
        ConfigRuntime.initialize(context)
        return ConfigRuntime.putString(SettingIds.RUNTIME_READ_TIME_CLOUD_AUDIT_STATE, value)
    }

    fun readTimeEffectiveMultiplierRaw(context: Context? = null): String {
        context?.let(ConfigRuntime::initialize)
        return ConfigRuntime.readString(SettingIds.RUNTIME_READ_TIME_EFFECTIVE_MULTIPLIER)
    }

    fun putReadTimeEffectiveMultiplier(context: Context, value: String): Boolean {
        ConfigRuntime.initialize(context)
        return ConfigRuntime.putString(SettingIds.RUNTIME_READ_TIME_EFFECTIVE_MULTIPLIER, value)
    }

    fun clearReadTimeEffectiveMultiplier(context: Context): Boolean =
        putReadTimeEffectiveMultiplier(context, "")

    fun putReadTimeMultiplier(context: Context, value: String): Boolean =
        putString(context, SettingIds.KEY_READ_TIME_MULTIPLIER_VALUE, value)

    fun isWelfareRewardAdSkipEnabled(context: Context? = null): Boolean {
        return featureEnabled(context, SettingIds.KEY_WELFARE_REWARD_AD_SKIP_ENABLED)
    }

    fun isWelfareComboRewardSkipEnabled(context: Context? = null): Boolean {
        return featureEnabled(context, SettingIds.KEY_WELFARE_COMBO_REWARD_SKIP_ENABLED)
    }

    fun isWelfareRewardPopupSuppressed(context: Context? = null): Boolean {
        return featureEnabled(context, SettingIds.KEY_WELFARE_REWARD_POPUP_SUPPRESS_ENABLED)
    }

    fun isCustomDnsEnabled(context: Context? = null): Boolean {
        return featureEnabled(context, SettingIds.KEY_CUSTOM_DNS_ENABLED)
    }

    fun customDnsRules(context: Context? = null): String {
        return prefsOrNull(context)?.getString(SettingIds.KEY_CUSTOM_DNS_RULES, "") ?: ""
    }

    fun customDnsNetStack(context: Context? = null): Int {
        return prefsOrNull(context)
            ?.getString(SettingIds.KEY_CUSTOM_DNS_NET_STACK, "3")
            ?.trim()
            ?.toIntOrNull()
            ?.takeIf { it in 1..3 }
            ?: 3
    }

    fun isBookShieldEnabled(context: Context? = null): Boolean {
        return featureEnabled(context, SettingIds.KEY_BOOK_SHIELD_ENABLED)
    }

    fun isBookShieldQuickAddEnabled(context: Context? = null): Boolean {
        return featureEnabled(context, SettingIds.KEY_BOOK_SHIELD_QUICK_ADD)
    }

    fun bookShieldLocations(context: Context? = null): Set<String> {
        return prefsOrNull(context)
            ?.getString(SettingIds.KEY_BOOK_SHIELD_LOCATIONS, null)
            ?.let(::normalizeBookShieldLocations)
            ?: SettingCollections.DEFAULT_BOOK_SHIELD_LOCATIONS
    }

    fun isBookShieldLocationEnabled(context: Context? = null, location: String): Boolean {
        return isBookShieldEnabled(context) && bookShieldLocations(context).contains(location)
    }

    fun isBookShieldTypeContainsEnabled(context: Context? = null): Boolean {
        return featureEnabled(context, SettingIds.KEY_BOOK_SHIELD_TYPE_CONTAINS)
    }

    fun isBookShieldEmptyResultAllowed(context: Context? = null): Boolean {
        return featureEnabled(context, SettingIds.KEY_BOOK_SHIELD_ALLOW_EMPTY_RESULT)
    }

    fun bookShieldLocationLabel(location: String): String {
        return SettingCollections.BOOK_SHIELD_LOCATION_ITEMS.firstOrNull { it.key == location }?.label ?: location
    }

    fun isDiscoveryProbeEnabled(context: Context? = null): Boolean {
        return isDetailedLogEnabled(context) && featureEnabled(context, SettingIds.KEY_DEBUG_DISCOVERY_ENABLED)
    }

    fun isDexKitEnabled(context: Context? = null): Boolean {
        return featureEnabled(context, SettingIds.KEY_DEBUG_DEXKIT_ENABLED)
    }

    fun isDexKitCandidateLogEnabled(context: Context? = null): Boolean {
        return isDetailedLogEnabled(context) && featureEnabled(context, SettingIds.KEY_DEBUG_DEXKIT_LOG_CANDIDATES)
    }

    fun isCallProbeEnabled(context: Context? = null): Boolean {
        return isDetailedLogEnabled(context) && featureEnabled(context, SettingIds.KEY_DEBUG_CALL_PROBE_ENABLED)
    }

    fun isShieldTraceEnabled(context: Context? = null): Boolean {
        return isDetailedLogEnabled(context) && featureEnabled(context, SettingIds.KEY_DEBUG_SHIELD_TRACE_ENABLED)
    }

    fun isHostCollectJsonEnabled(context: Context? = null): Boolean {
        return isDetailedLogEnabled(context) && featureEnabled(context, SettingIds.KEY_DEBUG_HOST_COLLECT_JSON_ENABLED)
    }

    fun isDetailedLogEnabled(context: Context? = null): Boolean =
        featureEnabled(context, SettingIds.KEY_DETAILED_LOG_ENABLED)

    fun isFileLogEnabled(context: Context? = null): Boolean = isDetailedLogEnabled(context)

    fun isWelfareRewardProbeEnabled(context: Context? = null): Boolean {
        return isDetailedLogEnabled(context) && featureEnabled(context, SettingIds.KEY_DEBUG_WELFARE_REWARD_PROBE_ENABLED)
    }

    fun isDeviceIdentityProbeEnabled(context: Context? = null): Boolean {
        return isDetailedLogEnabled(context) && featureEnabled(context, SettingIds.KEY_DEBUG_DEVICE_IDENTITY_PROBE_ENABLED)
    }

    fun isAnyWelfareActionEnabled(context: Context? = null): Boolean {
        return isWelfareRewardAdSkipEnabled(context) ||
            isWelfareComboRewardSkipEnabled(context) ||
            isWelfareRewardPopupSuppressed(context) ||
            isWelfareRewardSkipOnceEnabled(context) ||
            isWelfareFlutterRewardSkipOnceEnabled(context) ||
            isWelfareComboRewardSkipOnceEnabled(context)
    }

    fun isWelfareRewardSkipOnceEnabled(context: Context? = null): Boolean {
        return featureEnabled(context, SettingIds.KEY_DEBUG_WELFARE_REWARD_SKIP_ONCE_ENABLED)
    }

    fun consumeWelfareRewardSkipOnce(context: Context? = null): Boolean {
        return consumeBooleanSetting(context, SettingIds.KEY_DEBUG_WELFARE_REWARD_SKIP_ONCE_ENABLED)
    }

    fun isWelfareFlutterRewardSkipOnceEnabled(context: Context? = null): Boolean {
        return featureEnabled(context, SettingIds.KEY_DEBUG_WELFARE_REWARD_FLUTTER_SKIP_ONCE_ENABLED) ||
            isWelfareRewardSkipOnceEnabled(context)
    }

    fun consumeWelfareFlutterRewardSkipOnce(context: Context? = null): Boolean {
        return consumeBooleanSetting(context, SettingIds.KEY_DEBUG_WELFARE_REWARD_FLUTTER_SKIP_ONCE_ENABLED) ||
            consumeWelfareRewardSkipOnce(context)
    }

    fun isWelfareComboRewardSkipOnceEnabled(context: Context? = null): Boolean {
        return featureEnabled(context, SettingIds.KEY_DEBUG_WELFARE_REWARD_COMBO_SKIP_ONCE_ENABLED) ||
            isWelfareRewardSkipOnceEnabled(context)
    }

    fun consumeWelfareComboRewardSkipOnce(context: Context? = null): Boolean {
        return consumeBooleanSetting(context, SettingIds.KEY_DEBUG_WELFARE_REWARD_COMBO_SKIP_ONCE_ENABLED) ||
            consumeWelfareRewardSkipOnce(context)
    }

    private fun consumeBooleanSetting(context: Context?, key: String): Boolean {
        val prefs = prefsOrNull(context) ?: return false
        if (!isModuleEnabled(context) || !prefs.getBoolean(key, false)) {
            return false
        }
        return prefs.edit().putBoolean(key, false).commit()
    }

    fun putBoolean(context: Context, key: String, value: Boolean): Boolean {
        ConfigRuntime.initialize(context)
        return ConfigRuntime.putBoolean(key, value)
    }

    fun putString(context: Context, key: String, value: String): Boolean {
        ConfigRuntime.initialize(context)
        return ConfigRuntime.putString(key, value)
    }

    fun runtimeString(context: Context, key: String): String {
        val definition = SettingSchemaCatalog.requireKey(key)
        require(definition.scope == SettingScope.RUNTIME && definition.valueType == String::class)
        ConfigRuntime.initialize(context)
        return ConfigRuntime.readString(key)
    }

    fun putExclusiveSplashToggle(context: Context, key: String, value: Boolean): Boolean {
        if (key !in SettingCollections.SPLASH_SOURCE_TOGGLE_KEYS) {
            return putBoolean(context, key, value)
        }
        ConfigRuntime.initialize(context)
        val editor = prefs(context).edit().putBoolean(key, value)
        if (value) {
            SettingCollections.SPLASH_SOURCE_TOGGLE_KEYS.forEach { other ->
                if (other != key) {
                    editor.putBoolean(other, false)
                }
            }
            clearLocalOfficialSplashSelection(editor)
        }
        return editor.commit()
    }

    fun selectLocalOfficialSplashImage(
        context: Context,
        paperId: Long,
        imageUrl: String,
        darkLogo: Boolean,
    ): Boolean {
        val normalizedUrl = imageUrl.trim()
        if (paperId <= 0L || normalizedUrl.isBlank()) return false
        ConfigRuntime.initialize(context)
        val editor = prefs(context).edit()
            .putLong(SettingIds.KEY_OFFICIAL_SPLASH_IMAGE_LOCAL_PAPER_ID, paperId)
            .putString(SettingIds.KEY_OFFICIAL_SPLASH_IMAGE_LOCAL_URL, normalizedUrl)
            .putBoolean(SettingIds.KEY_OFFICIAL_SPLASH_IMAGE_LOCAL_DARK_LOGO, darkLogo)
        SettingCollections.SPLASH_SOURCE_TOGGLE_KEYS.forEach { key -> editor.putBoolean(key, false) }
        return editor.commit()
    }

    fun clearLocalOfficialSplashImage(context: Context): Boolean {
        ConfigRuntime.initialize(context)
        return clearLocalOfficialSplashSelection(prefs(context).edit()).commit()
    }

    fun markAutoSignInAttempt(context: Context, userId: Long, date: String): Boolean {
        ConfigRuntime.initialize(context)
        return ConfigRuntime.putRuntimeValues(
            mapOf(SettingIds.KEY_AUTO_SIGN_IN_LAST_USER to userId, SettingIds.KEY_AUTO_SIGN_IN_LAST_DATE to date),
        )
    }

    fun hasAutoSignInAttempted(context: Context, userId: Long, date: String): Boolean {
        ConfigRuntime.initialize(context)
        return ConfigRuntime.readLong(SettingIds.KEY_AUTO_SIGN_IN_LAST_USER) == userId &&
            ConfigRuntime.readString(SettingIds.KEY_AUTO_SIGN_IN_LAST_DATE) == date
    }

    fun resetP0Defaults(context: Context): Boolean = SettingsResetter.resetP0Defaults(context)

    fun resetDefaults(context: Context, keys: Collection<String>): Boolean =
        SettingsResetter.resetDefaults(context, keys)

    private fun featureEnabled(context: Context?, key: String): Boolean {
        context?.let(ConfigRuntime::initialize)
        return ConfigRuntime.readBoolean(SettingIds.KEY_MODULE_ENABLED) && ConfigRuntime.readBoolean(key)
    }

    private fun prefsOrNull(context: Context?): SharedPreferences? {
        val application = context?.let { it.applicationContext ?: it } ?: appContext ?: return null
        ConfigRuntime.initialize(application)
        return prefs(application)
    }

    private fun clearLocalOfficialSplashSelection(editor: SharedPreferences.Editor): SharedPreferences.Editor {
        return editor
            .putLong(SettingIds.KEY_OFFICIAL_SPLASH_IMAGE_LOCAL_PAPER_ID, 0L)
            .putString(SettingIds.KEY_OFFICIAL_SPLASH_IMAGE_LOCAL_URL, "")
            .putBoolean(SettingIds.KEY_OFFICIAL_SPLASH_IMAGE_LOCAL_DARK_LOGO, false)
    }

    private fun migrateLegacyBookShieldLocations(context: Context) {
        val preferences = prefs(context)
        val raw = preferences.getString(SettingIds.KEY_BOOK_SHIELD_LOCATIONS, null) ?: return
        val normalized = normalizeBookShieldLocations(raw).joinToString(",")
        if (normalized != raw) {
            preferences.edit().putString(SettingIds.KEY_BOOK_SHIELD_LOCATIONS, normalized).apply()
        }
    }

    private fun orderedKeys(context: Context?, key: String, choices: List<ChoiceItem>): List<String> {
        val valid = choices.map { it.key }
        val stored = prefsOrNull(context)
            ?.getString(key, null)
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { valid.contains(it) }
            .orEmpty()
        return (stored + valid).distinct().filter { valid.contains(it) }
    }

    private fun hiddenKeys(context: Context?, key: String, choices: List<ChoiceItem>): Set<String> {
        val valid = choices.map { it.key }.toSet()
        val hidden = prefsOrNull(context)
            ?.getString(key, null)
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { valid.contains(it) }
            ?.toSet()
            .orEmpty()
        return if (hidden.size >= valid.size) emptySet() else hidden
    }

}

internal fun normalizeBookShieldLocations(raw: String): Set<String> {
    return buildSet {
        raw.split(',').forEach { value ->
            when (val token = value.trim()) {
                LEGACY_BOOK_SHIELD_CATEGORY -> add("bookstore_reborn")
                in RETIRED_BOOK_SHIELD_LOCATIONS -> Unit
                in VALID_BOOK_SHIELD_LOCATIONS -> add(token)
            }
        }
    }
}

private const val LEGACY_BOOK_SHIELD_CATEGORY = "category"
private val VALID_BOOK_SHIELD_LOCATIONS = SettingCollections.BOOK_SHIELD_LOCATION_ITEMS.mapTo(hashSetOf()) { it.key }
private val RETIRED_BOOK_SHIELD_LOCATIONS = setOf(
    "new_recommend",
    "new_book",
    "comic_other",
    "old_daily_recommend",
)
