# UIDE

> 一个基于 Jetpack Compose + Material 3 的轻量级安卓代码编辑器（初期版）。在应用私有目录下管理文件，并以 C 语法高亮编辑代码内容。

## 功能特性

- **私有目录文件管理**：基于 `Context.getFilesDir()`，免存储权限即可新建 / 读取 / 写入 / 删除文件；文件名自动 sanitize 防路径穿越。
- **文件列表页**：顶栏 `UIDE`、悬浮按钮（FAB）弹窗新建（默认 `untitled.c`）、列表展示文件名 / 大小 / 修改时间、删除二次确认。
- **代码编辑器页**：采用「高亮 `Text` + 透明 `BasicTextField` 叠层 + 共享滚动」的可编辑高亮方案；内置 **JetBrains Mono** 等宽字体（14sp），顶栏提供返回与保存（写回私有目录并提示）。
- **C 语法高亮**：自实现的字符级状态机，区分行注释 / 块注释、字符串 / 字符字面量、预处理指令、数字、关键字、基础类型、函数调用；明 / 暗主题各有一套配色，零额外依赖。
- **Material 3 主题**：`UIDETheme` 在 Android 12+ 使用 dynamic color（动态取色），低版本回退到内置配色；开启 `enableEdgeToEdge()` 边到边显示。

## 技术栈

| 项 | 版本 / 说明 |
| --- | --- |
| 语言 | Kotlin |
| UI | Jetpack Compose + Material 3 |
| 构建 | Gradle Kotlin DSL（`.kts`） |
| AGP | `8.9.0` |
| Kotlin | `2.1.0`（含 Compose 编译器插件） |
| Compose BOM | `2024.10.01`（Compose 1.7 线） |
| 关键依赖 | `navigation-compose 2.8.4`、`activity-compose 1.9.3`、`lifecycle-viewmodel-compose 2.8.7` |
| 平台 | `minSdk 24` / `compileSdk 36` / `targetSdk 36` |
| 字体 | JetBrains Mono Regular（`res/font/jetbrains_mono.ttf`） |

## 项目结构

```
UIDE/
├── build.gradle.kts              # 根构建：声明插件版本（AGP / Kotlin / Compose）
├── settings.gradle.kts           # 仓库与模块配置
├── gradle.properties
├── gradle/wrapper/               # Gradle Wrapper 配置（见下方说明）
└── app/
    ├── build.gradle.kts          # 应用模块：SDK 版本、依赖
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── kotlin/com/uniaball/uide/
        │   ├── MainActivity.kt           # 入口 + 导航（files ↔ editor/{name}）
        │   ├── data/FileRepository.kt    # 私有目录文件 CRUD
        │   ├── syntax/CSyntaxHighlighter.kt  # C 语法高亮状态机
        │   └── ui/
        │       ├── FileListScreen.kt     # 文件列表 + 新建/删除
        │       ├── EditorScreen.kt       # 编辑器（高亮叠层 + 保存）
        │       └── theme/                # UIDETheme / Color / Type
        └── res/
            ├── font/jetbrains_mono.ttf   # 等宽编辑器字体
            ├── drawable/ic_launcher.xml  # 启动图标（矢量）
            ├── values/strings.xml, themes.xml
            └── values-night/themes.xml
```

## 构建与运行

1. 用 **Android Studio**（建议 Hedgehog / Iguana 或更新版本）打开 `UIDE/` 目录。
2. 点击 **Sync Project with Gradle Files** 同步依赖。
3. 连接安卓设备或启动模拟器，点击 **Run**（▶）运行 `app`。

> **Gradle Wrapper 说明**：仓库中已包含 `gradle-wrapper.properties`（指向 Gradle 8.11.1），但二进制 `gradle-wrapper.jar` 需在本机补齐。导入时若提示缺少 wrapper，可在本机执行 `gradle wrapper --gradle-version 8.11.1`（需已安装 Gradle），或直接让 Android Studio 下载补齐。

> **AGP 兼容提示**：若 `compileSdk = 36` 被当前 AGP 拒绝，请将根 `build.gradle.kts` 中 `com.android.application` 的版本号升到支持 Android 16（API 36）的最新 8.x。

## 持续集成 / 发布

仓库已内置两条 GitHub Actions 工作流（均使用最新版 Action）：

- **`.github/workflows/ci.yml`** —— 推送 / PR 到 `main` 时自动 `./gradlew build`，并上传 debug APK 产物。无需任何密钥即可运行。
- **`.github/workflows/release.yml`** —— 推送版本标签（如 `v1.0.0`）时，用仓库密钥签名并构建 **已签名的 APK + AAB**，上传产物并自动创建 GitHub Release。

Release 流程依赖以下 **仓库 Secrets**（Settings → Secrets and variables → Actions）：

| Secret | 说明 |
| --- | --- |
| `KEYSTORE_BASE64` | 签名密钥库 `keystore.jks` 的 base64 内容（`base64 -w0 keystore.jks`） |
| `KEYSTORE_PASSWORD` | 密钥库密码 |
| `KEY_ALIAS` | 密钥别名 |
| `KEY_PASSWORD` | 密钥密码 |

> 未配置这些密钥时，`ci.yml` 仍可正常构建；`release.yml` 仅在打 tag 时触发，且签名配置在 `app/build.gradle.kts` 中**仅在检测到 `KEYSTORE_BASE64` 时才启用**，因此本地 / CI 日常构建不受影响。

## 已知限制 / 后续计划

- 编辑器叠层方案对光标与选择是初期近似实现；超宽长行滚动已对齐，但极端情况下可能存在像素级偏差，后续可迁移到 `TextFieldState` 做精细化。
- 语法高亮目前仅按 C 语法着色（未按扩展名区分语言），后续可扩展为多语言、行号、自动缩进、查找替换等。
- 当前所有文件保存在应用私有目录，卸载即清除；后续可加入导出 / 导入（需相应存储权限或更现代的存储访问框架）。

## 文件存储位置（常见问题）

文件保存在应用私有目录 `getFilesDir()`，即设备上的 `android/data/com.uniaball.uide/files/`。这是**沙箱私有目录**：文件在应用内的「文件列表」中可见、可编辑，但 **Android 11（API 30）起，通过电脑 USB / 第三方文件管理器访问 `android/data/<包名>/` 会被系统拦截**，因此你在电脑上看不到该文件夹或其中的文件——这属于作用域存储（Scoped Storage）的正常限制，并非文件丢失。如需从电脑访问，后续可改为共享存储（MediaStore / SAF / 存储权限）或增加「导出 / 分享」功能。

> 应用列表只展示用户创建的文件。系统库（如 `profileinstaller`）写入的 `profileinstalled` 等内部文件已被 `FileRepository` 自动过滤，不会出现在列表里。
