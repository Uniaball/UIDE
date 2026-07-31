# UIDE

> 一个基于 Jetpack Compose + Material 3 的轻量级安卓代码编辑器。用户文件保存在外部存储（SD 卡），并支持以 C / C++ 语法高亮编辑代码。

## 功能特性

- **本地文件管理**：文件保存在外部存储（SD 卡）中，新建 / 读取 / 编辑 / 删除，无需任何存储权限。
- **文件列表**：展示文件名、大小与修改时间，支持一键新建与删除确认。
- **代码编辑器**：内置 JetBrains Mono 等宽字体，支持 C / C++ 语法高亮（注释、字符串、预处理指令、数字、关键字、类型、函数、运算符 `::`/`->`/`<<`/`>>`）。按扩展名自动识别语言：`.c` / `.h` 按 C 高亮，`.cpp` / `.cc` / `.cxx` / `.hpp` / `.hxx` / `.hh` 等按 C++ 高亮，适配明暗主题。
- **Material 3 动态配色**：Android 12+ 自动跟随系统壁纸取色，低版本回退内置配色。

## 构建与运行

1. 使用最新稳定版 **Android Studio** 打开 `UIDE/` 目录。
2. 点击 **Sync Project with Gradle Files** 同步依赖。
3. 连接安卓设备或启动模拟器，点击 **Run**（▶）运行 `app`。

## 技术栈

| 项 | 说明 |
| --- | --- |
| 语言 | Kotlin |
| UI | Jetpack Compose + Material 3 |
| 构建 | Gradle Kotlin DSL（AGP 8.9 / Kotlin 2.1 / Compose BOM 2024.10.01） |
| 平台 | minSdk 24 / compileSdk 36 / targetSdk 36 |
| 字体 | JetBrains Mono（等宽） |

## 持续集成 / 发布

仓库内置两条 GitHub Actions 工作流：

- **ci.yml** —— 推送 / PR 到 `main` 时自动构建，并上传 debug APK 产物。
- **release.yml** —— 推送版本标签（如 `v1.0.0`）时，构建已签名的 APK + AAB 并自动创建 GitHub Release。

发布流程需在仓库 **Settings → Secrets and variables → Actions** 中配置以下 Secrets：

| Secret | 说明 |
| --- | --- |
| `KEYSTORE_BASE64` | 签名密钥库（`.jks`）的 base64 内容 |
| `KEYSTORE_PASSWORD` | 密钥库密码 |
| `KEY_ALIAS` | 密钥别名 |
| `KEY_PASSWORD` | 密钥密码 |

未配置这些 Secrets 时，`ci.yml` 仍可正常构建。

## 文件存储位置

文件保存在外部存储（SD 卡）的应用专属目录下：`Android/data/com.uniaball.uide/files/uide/`。使用 `getExternalFilesDir()` 实现，无需申请任何存储权限。

## 开源协议

本项目基于 [Apache License 2.0](LICENSE) 发布。内置的 JetBrains Mono 字体采用独立的 [SIL Open Font License 1.1](https://openfontlicense.org) 授权。
