# QDHelper

[![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?style=flat-square&logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![libxposed API 102](https://img.shields.io/badge/libxposed-API%20102-3DDC84?style=flat-square)](https://github.com/libxposed/api)
[![AGPL-3.0-only](https://img.shields.io/badge/License-AGPL--3.0--only-blue?style=flat-square)](LICENSE)
[![Downloads](https://img.shields.io/endpoint?url=https%3A%2F%2Fraw.githubusercontent.com%2Fxiyunmn%2FQDHelper%2Fbadges%2Fdownloads.json&style=flat-square)](https://github.com/xiyunmn/QDHelper/releases)
[![Release](https://img.shields.io/github/v/release/xiyunmn/QDHelper?style=flat-square&label=Release)](https://github.com/xiyunmn/QDHelper/releases/latest)

QDHelper 是面向起点读书的 LSPosed 功能增强模块，提供广告与弹窗清理、界面定制、阅读优化、书籍屏蔽等功能。

当前版本：`1.0.0`

源码仓库：[xiyunmn/QDHelper](https://github.com/xiyunmn/QDHelper)


## 支持范围

- **系统**：Android 9 及以上，仅支持 `arm64-v8a` 设备。
- **液态玻璃**：需要 Android 13 及以上；Android 9～12 保留原生底栏。
- **框架**：支持 libxposed API 102 的 LSPosed。
- **应用**：起点读书，主要适配 `7.9.469～7.9.480`，建议使用 `7.9.480`。不同版本的功能可用性可能有所差异。

## 安装与入口

1. 安装模块，在 LSPosed 中启用 QDHelper，作用域勾选「起点读书」。
2. 若使用 Zygisk Next，需在其设置中禁用「匿名内存」和「ZN链接器」。
3. 为起点读书开启「还原内联钩子」，然后强制停止并重新打开起点。
4. 长按「书架」页右上角的「更多」，或「我的」页左上角的「设置」，进入模块设置。
5. 按需开启功能；设置标注需重启或重新进入页面时，按提示操作。

模块没有独立的桌面设置入口。总开关与 DexKit 解析默认开启，具体功能按需启用。

## 功能概览

- **广告与弹窗**：清理启动、书架、阅读等页面的部分广告、活动入口、红点及提示弹窗。
- **界面定制**：调整底栏 Tab、首页控件顺序与显示内容，定制启动图，启用液态玻璃底栏。
- **阅读体验**：导入和管理字体，调整正文复制、段落操作与章评菜单，清理部分阅读干扰元素。
- **内容工具**：查看头像、封面等原图，复制书籍简介和书友圈内容。
- **书籍屏蔽**：按作者、书名、分类、字数等规则过滤推荐，支持快速添加规则。
- **账号与福利**：自动签到，以及部分福利任务的免广告与弹窗处理。
- **配置管理**：备份与恢复设置，导入、导出屏蔽规则，查看解析状态和诊断信息。

建议逐项开启。账号、福利及阅读时长等功能可能受服务端策略影响，可用性无法确定，请自行评估账号风险。

## 问题反馈

遇到功能失效，先确认功能已开启、相关页面已重新进入或起点已重启，并检查「工具与诊断」中的 DexKit 解析状态。

[反馈问题](https://github.com/xiyunmn/QDHelper/issues)时，请提供系统、起点和模块版本，说明失效功能与复现步骤，必要时附截图或相关日志。请先遮盖账号、Cookie、设备标识等隐私信息。

## 构建

准备 JDK 21 和 Android SDK 37，在项目根目录执行：

```powershell
.\gradlew.bat :app:assembleRelease
```

本地未配置签名时生成未签名 Release APK；Debug、Beta、Release 均启用 R8 和资源裁剪。正式签名通过环境变量 `RELEASE_KEYSTORE_FILE`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD` 配置。

推送触发的 CI 只上传一个已签名、压缩的 APK，名称为 `QDHelper-v1.0.0-<短哈希>.apk`；需配置对应的四项 GitHub Actions 签名 secrets（密钥文件使用 `KEYSTORE_BASE64`）。手动 `Build Release` 使用基础版本，产物为 `QDHelper-v1.0.0-release.apk`，不会自动发布 Release。

CI 版本名为 `1.0.0.r<提交次数>.<短哈希>`。版本代号为 `100000 + 提交次数 × 2`，同一提交的手动正式版再加 1；克隆源码时保留完整 Git 历史。Manifest 与 Xposed 模块版本信息在构建时同步。

## 致谢

本项目使用 [libxposed](https://github.com/libxposed/api)、[DexKit](https://github.com/LuckyPray/DexKit)、[Jetpack Compose](https://developer.android.com/compose) 和 [Lucide](https://lucide.dev/) 等开源组件。

玻璃效果参考或改编自 [ForbidAd4TieBa](https://github.com/aikavvak12una/ForbidAd4TieBa)、[miuix](https://github.com/compose-miuix-ui/miuix)、[KernelSU](https://github.com/tiann/KernelSU) 和 [WeChat-LiquidGlass](https://github.com/liuran001/WeChat-LiquidGlass)。感谢相关作者与贡献者。

## 许可证

本项目自有代码采用 [AGPL-3.0-only](LICENSE)。修改、分发或通过网络提供修改版服务时，请遵守相应的源码提供义务并保留版权声明。第三方代码与组件保留各自原许可证，完整许可和署名随源码及安装包提供。

## 免责声明

本项目为独立开发的非官方工具，与**阅文集团、起点中文网、起点读书及其运营主体无隶属、合作或授权关系**。相关名称、商标及内容的权利归各自权利人所有。

本项目仅供学习、技术研究与交流。**请在下载后 24 小时内删除用于研究测试的安装包及相关副本**；此研究使用提醒不改变开源许可证授予的权利，也不免除任何法律责任。

软件按「现状」提供，不保证功能持续可用或使用安全。使用模块可能导致应用异常、数据损坏、功能受限或账号风险。请遵守适用法律法规及相关服务条款，提前备份重要数据，自行评估并承担使用风险。
