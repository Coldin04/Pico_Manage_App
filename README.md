# Pico Manager

Copyright (C) 2026 Coldin04

Pico Manager 是一款用于管理可供 Pico、Crosspoint 等第三方固件使用的图书文件的应用。

本项目使用独立的 InkReaderLink device bridge（核心 crate：`inkreaderlink-core`；Android UniFFI 库：`com.cold04:inkreaderlink-uniffi`）管理兼容设备和图书内容。

## 兼容性声明

本项目对第三方固件或设备的兼容，不代表其作者或权利人对本项目的认可、赞助或隶属关系。项目名称中的 `Pico` 意为“小”，旨在做一个方便管理小尺寸墨水屏设备（未来可能扩展支持更多阅读设备）的工具。不表示本项目是[官方 Read Pico 应用](https://dot.mindreset.tech/docs/read_0) ，也不表示本项目与 Read Pico 存在第一方关系。

## 许可证

源代码采用 GNU General Public License v3.0 授权，详见 [LICENSE](LICENSE) 和 [NOTICE](NOTICE)。

项目名称、Logo、图标和官方展示素材不在 GPLv3 的授权范围内，详见 [TRADEMARKS.md](TRADEMARKS.md)。


## Android release 签名

Android `applicationId` 和 `namespace` 为 `com.cold04.inkreadermgr`。旧预览版使用不同 ID，不能原位升级。

本地 release 构建读取 `android/keystore.properties` 指定的密钥；CI 可通过环境变量提供同一套签名信息。

本地 debug 和 release APK 使用同一签名及 `applicationId`，以便在版本号允许时互相覆盖安装。Play App Signing 分发的安装包可能使用不同于本地上传密钥的证书，不能据此保证与本地 debug 包互相覆盖。

## Android Public 发布

推送 `vX.Y.Z`、`vX.Y.Z-preview`、`vX.Y.Z-previewN` 或对应的 `Android-` tag 时，GitHub Actions 按该 tag 的代码编译签名 APK、生成更新日志并创建同名 Draft Release。检查 APK 和说明后，可在 GitHub Releases 页面编辑说明并发布；`-preview` 与 `-previewN` 会标为预发布。`iOS-vX.Y.Z` 不触发此工作流；`vX.Y.Z` 目前只编译 Android，后续可增加 iOS 构建。

仓库 Actions Secrets 需要设置 `ANDROID_KEYSTORE_BASE64`（签名密钥文件的 Base64 内容）、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS` 和 `ANDROID_KEY_PASSWORD`。CI 应使用与本地发布相同的签名密钥，以便已安装版本升级。tag 使用 `X.Y.Z` 数字版本，主版本号不超过 20，次版本号和补丁号不超过 999；预览序号为 1 到 98（省略序号时按 1 处理）。版本号会写入 APK 的 `versionName` 和 `versionCode`，同一基础版本的正式版 `versionCode` 高于所有预览版。

每次发布包含 `arm64-v8a`、`armeabi-v7a`、`x86_64` 和通用 APK，以及 `SHA256SUMS.txt`。工作流会在上传前核对全部 APK 的签名、版本号和 SHA-256 校验值。

## Android 提交检查

push 和 Pull Request 会按固定 SDK commit 构建或缓存 InkReaderLink AAR，运行 Android 单元测试并组装 debug APK；同时检查 APK 的 applicationId 和 InkReaderLink 原生库。该工作流不签名、不创建 Release。

## Android InkReaderLink 依赖与开发

App 使用独立的 **InkReaderLink** Android 库 `com.cold04:inkreaderlink-uniffi`，并通过 `android/gradle.properties` 中的 `inkreaderlinkSdkVersion` 固定其版本。版本形式决定 SDK 来源：

下表中的版本号仅为格式示例，不代表当前或已发布版本。

| 版本形式 | 来源与用途 |
| --- | --- |
| `0.2.0`、`0.2.0-preview.3` | 从 Maven Central 获取已发布版本。 |
| `git.<完整40位commit SHA>` | 按 InkReaderLink 仓库中的指定 commit 构建。GitHub 发布工作流会拉取该 commit、构建 AAR 并缓存到 CI 的 Maven 本地仓库，再构建 App。 |
| `local` | 从开发者机器的 `mavenLocal()` 获取。GitHub 发布工作流会拒绝此版本。 |

普通版本可直接用于 App 的 CI 发布。使用 `git.<SHA>` 时，App 的 Draft Release 会附带 `BUILD-INFO.txt`，记录 App commit、InkReaderLink 坐标和 SDK commit。SDK AAR 缓存按完整 SDK SHA 及工具链缓存键保存；SHA 改变时会自动构建新产物，不需要手工清缓存。

SDK 仓库位置可通过 `INKREADERLINK_SDK_REPOSITORY` 配置，默认值为 `https://github.com/Coldin04/InkReaderLink.git`。本地 shell 环境和 App GitHub 仓库的 Actions Variables 都可设置该变量以使用 fork 或镜像。

本地构建的 `local` 与 `git.<SHA>` 产物都必须先存在于 `mavenLocal()`；普通版本只从 Maven Central 解析。版本属性位于 `android/gradle.properties`，InkReaderLink 本地构建和发布命令见 SDK 仓库 README 的“SDK 引用指南”。
