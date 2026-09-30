# Pico Manager

Copyright (C) 2026 Coldin04

Pico Manager 是一款用于管理可供 Pico、Crosspoint 等第三方固件使用的图书文件的应用。

本项目通过相关 API，并使用 `picobook_sdk` SDK 组织和管理兼容的图书内容。

## 兼容性声明

本项目对第三方固件或设备的兼容，不代表其作者或权利人对本项目的认可、赞助或隶属关系。项目名称中的 `Pico` 意为“小”，旨在做一个方便管理小尺寸墨水屏设备（未来可能扩展支持更多阅读设备）的工具。不表示本项目是[官方 Read Pico 应用](https://dot.mindreset.tech/docs/read_0) ，也不表示本项目与 Read Pico 存在第一方关系。

## 许可证

源代码采用 GNU General Public License v3.0 授权，详见 [LICENSE](LICENSE) 和 [NOTICE](NOTICE)。

项目名称、Logo、图标和官方展示素材不在 GPLv3 的授权范围内，详见 [TRADEMARKS.md](TRADEMARKS.md)。


## Android release 签名

本地 release 构建读取 `android/keystore.properties` 指定的密钥；CI 可通过环境变量提供同一套签名信息。

本地 debug 和 release APK 使用同一签名及 `applicationId`，以便在版本号允许时互相覆盖安装。Play App Signing 分发的安装包可能使用不同于本地上传密钥的证书，不能据此保证与本地 debug 包互相覆盖。

## Android Public 发布

推送 `vX.Y.Z` 或 `Android-vX.Y.Z` tag 时，GitHub Actions 编译签名的 Android release APK，并将其上传到同名 GitHub Release。附加 `-preview` 后缀（如 `v0.0.1-preview`）会自动发布为预发布版本。`iOS-vX.Y.Z` 不触发此工作流；`vX.Y.Z` 目前只编译 Android，后续可增加 iOS 构建。

仓库 Actions Secrets 需要设置 `ANDROID_KEYSTORE_BASE64`（签名密钥文件的 Base64 内容）、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS` 和 `ANDROID_KEY_PASSWORD`。CI 应使用与本地发布相同的签名密钥，以便已安装版本升级。tag 使用 `X.Y.Z` 数字版本，主版本号不超过 1000，次版本号和补丁号不超过 999；版本号会写入 APK 的 `versionName` 和 `versionCode`。相同版本的预发布 `versionCode` 小于正式版。

每次发布包含 `arm64-v8a`、`armeabi-v7a`、`x86_64` 和通用 APK，以及 `SHA256SUMS.txt`。工作流会在上传前核对全部 APK 的签名、版本号和 SHA-256 校验值。
