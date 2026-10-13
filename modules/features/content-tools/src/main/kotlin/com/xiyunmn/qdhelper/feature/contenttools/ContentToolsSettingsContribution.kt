package com.xiyunmn.qdhelper.feature.contenttools

import com.xiyunmn.qdhelper.foundation.identity.OwnerId
import com.xiyunmn.qdhelper.config.SettingIds
import com.xiyunmn.qdhelper.settings.api.SettingsContribution
import com.xiyunmn.qdhelper.settings.api.SettingsContributionId
import com.xiyunmn.qdhelper.settings.api.SettingsPageIds
import com.xiyunmn.qdhelper.settings.api.SettingsRow
import com.xiyunmn.qdhelper.settings.api.SettingsSection

object ContentToolsSettingsContribution : SettingsContribution {
    override val id = SettingsContributionId("content-tools.settings")
    override val owner = OwnerId("content-tools")
    override val sections = listOf(
        SettingsSection(
            id = "content-tools.core-watermark",
            page = SettingsPageIds.CORE,
            title = "内容工具",
            order = 300,
            groupId = "core.main",
            rows = listOf(
                SettingsRow.Toggle("content-tools.upload-watermark", "发帖图片去水印", "圈子发帖、胶囊及衍生内容上传图片时，跳过右下角图标与 @昵称水印，改用原图继续上传。", SettingIds.KEY_DISABLE_UPLOAD_WATERMARK),
            ),
        ),
        SettingsSection(
            id = "content-tools.profile",
            page = SettingsPageIds.PROFILE,
            title = "个人主页",
            order = 300,
            rows = listOf(
                SettingsRow.Toggle("content-tools.profile-avatar-original", "头像原图", "长按个人主页头像查看原图，支持缩放、拖动和保存到相册。", SettingIds.KEY_PROFILE_AVATAR_ORIGINAL_ENABLED, keywords = setOf("个人主页", "读者头像", "作家头像", "长按头像", "保存头像")),
                SettingsRow.Toggle("content-tools.profile-user-id", "显示 userId", "在个人主页称号与简介之间显示 userId，长按可直接复制。", SettingIds.KEY_PROFILE_USER_ID_ENABLED, keywords = setOf("个人主页", "用户ID", "用户 ID", "userid", "复制 userId")),
                SettingsRow.Toggle("content-tools.profile-privacy-second-page", "恢复隐私主页二级入口", "他人主页开启社区信息隐私时，补回服务端仍提供数据且宿主原生可访问的书单、阅历、本章说与帖子入口。", SettingIds.KEY_PROFILE_PRIVACY_SECOND_PAGE_RESTORE_ENABLED, keywords = setOf("个人主页", "社区隐私", "书单", "阅历", "本章说", "帖子", "书评")),
            ),
        ),
        SettingsSection(
            id = "content-tools.book-detail",
            page = SettingsPageIds.PAGE_CUSTOMIZATION,
            title = "书籍详情页辅助",
            order = 500,
            groupId = "page-customization.book-detail-card",
            rows = listOf(
                SettingsRow.Toggle("content-tools.book-intro-copy", "简介自由复制", "长按书籍详情页简介，打开完整内容浮窗并自由选择复制范围。", SettingIds.KEY_BOOK_DETAIL_INTRO_FREE_COPY_ENABLED, keywords = setOf("书籍简介", "详情页复制", "长按简介")),
                SettingsRow.Toggle("content-tools.book-cover-original", "封面原图", "长按书籍封面查看高清原图，支持缩放、拖动和保存到相册。", SettingIds.KEY_BOOK_DETAIL_COVER_ORIGINAL_ENABLED, keywords = setOf("书籍封面", "长按封面", "缩放封面", "保存封面")),
                SettingsRow.Toggle("content-tools.role-avatar-original", "角色头像原图", "长按角色卡片或更多角色列表头像查看原图，支持缩放、拖动和保存到相册。", SettingIds.KEY_BOOK_DETAIL_ROLE_AVATAR_ORIGINAL_ENABLED, keywords = setOf("角色卡片", "更多角色", "角色头像", "长按头像", "保存头像")),
                SettingsRow.Toggle("content-tools.fans-rank", "显示完整粉丝排名", "书友榜排名超过 99 时，仍显示真实粉丝排名。", SettingIds.KEY_BOOK_DETAIL_FANS_RANK_ENABLED, keywords = setOf("书友榜", "粉丝排名", "真实排名")),
            ),
        ),
        SettingsSection(
            id = "content-tools.card-plaza",
            page = SettingsPageIds.PAGE_CUSTOMIZATION,
            title = "卡牌广场",
            order = 550,
            rows = listOf(
                SettingsRow.Toggle(
                    "content-tools.card-plaza-local-unlock",
                    "本地解锁全部卡牌",
                    "移除广场及预览页中未拥有卡牌的灰色遮罩与提示，可像已拥有卡牌一样查看大图。",
                    SettingIds.KEY_CARD_PLAZA_LOCAL_UNLOCK_ENABLED,
                    keywords = setOf("卡牌广场", "卡牌解锁", "未拥有卡牌", "卡牌大图", "角色卡", "专题卡"),
                ),
                SettingsRow.Toggle(
                    "content-tools.card-preview-long-press-save",
                    "预览图长按保存",
                    "在角色卡与专题卡预览页长按卡面，将高清图保存到系统相册。",
                    SettingIds.KEY_CARD_PREVIEW_LONG_PRESS_SAVE_ENABLED,
                    keywords = setOf("卡牌预览", "长按保存", "保存卡牌", "系统相册", "角色卡", "专题卡"),
                ),
            ),
        ),
        SettingsSection(
            id = "content-tools.circle",
            page = SettingsPageIds.PAGE_CUSTOMIZATION,
            title = "书友圈",
            order = 600,
            rows = listOf(
                SettingsRow.Toggle("content-tools.circle-copy", "自由复制", "长按书友圈帖子、评论或帖子详情正文，打开内容浮窗并自由选择复制范围。", SettingIds.KEY_CIRCLE_FREE_COPY_ENABLED, keywords = setOf("书友圈", "帖子复制", "评论复制")),
            ),
        ),
    )
}
