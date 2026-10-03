# UIDE

> 一个基于 Jetpack Compose + Material 3 的轻量级安卓代码编辑器。用户文件保存在外部存储（SD 卡），并支持以 C / C++ 语法高亮编辑代码。

## 功能特性

- **本地文件管理**：文件保存在外部存储（SD 卡）中，新建 / 读取 / 编辑 / 删除，无需任何存储权限。
- **文件列表**：展示文件名、大小与修改时间，支持新建文件夹与文件、目录嵌套浏览（点击进入、顶栏返回上级）、删除确认（目录递归删除）。
- **代码编辑器**：内置 JetBrains Mono 等宽字体，支持 C / C++ 语法高亮（注释、字符串、预处理指令、数字、关键字、类型、函数、运算符 `::`/`->`/`<<`/`>>`）。按扩展名自动识别语言：`.c` / `.h` 按 C 高亮，`.cpp` / `.cc` / `.cxx` / `.hpp` / `.hxx` / `.hh` 等按 C++ 高亮，`CMakeLists.txt` 按 CMake 高亮（注释、字符串、括号参数 `[[…]]`、命令、变量 `${…}`、流程关键字），适配明暗主题。
- **Material 3 动态配色**：Android 12+ 自动跟随系统壁纸取色，低版本回退内置配色。
- **内置 C/C++ 工具链**：应用内自带 clang / cmake / ninja，在“构建环境”页一键安装后即可编译并运行 CMake 工程，无需 root、无需 Termux、无需联网。
- **构建与运行面板**：文件列表中带 `CMakeLists.txt` 的目录会出现构建按钮，一键 configure + build，运行按钮直接执行产物并实时输出日志；编译错误行可点击跳转到编辑器对应行。

## 内置工具链

### 使用方式

1. 文件列表右上角扳手图标进入 **构建环境** 页，点击安装（从 APK 内置的约 90 MiB 压缩包流式解压，无需联网）。
2. 状态变为 **已安装** 后，进入任意含 `CMakeLists.txt` 的目录，点击 ▶ 图标。
3. 点击 **构建**，成功后点击 **运行** 查看程序输出；点击日志中的 `文件:行:列: error:` 行可跳转到编辑器。

### 工作原理

| 环节 | 说明 |
| --- | --- |
| 工具链来源 | Termux 官方 `.deb` 包（clang 21 / cmake 4.4 / ninja 1.13 / make 4.4 / ndk-sysroot 30 / libc++ 30），解包后重定位到应用私有目录，无需 patchelf 重打 `RPATH`/`RUNPATH` |
| 分发方式 | 由 `scripts/build_bootstrap.py` 打成单个 `app/src/main/assets/bootstrap-aarch64.bin`（gzip 流），安装时在 Kotlin 侧流式解包，避免解压中间层与 AssetMerger 自动解压 `.gz` 的冲突 |
| 执行方式 | 通过 `/system/bin/linker64` 启动目标 ELF，从而在 `targetSdk 36` 下依然可执行数据目录中的程序 |
| 环境注入 | `libuidexec.so` 以 `LD_PRELOAD` 注入，hook `execve/execv/execvp/execvpe/posix_spawn/posix_spawnp`，并解析 shebang；同时修正 `/proc/self/exe`（CMake 4.4 依赖可执行文件推导 `CMAKE_ROOT`） |
| 构建目录 | 构建产物放在内部存储 `files/build/<sha1>`，因为 SD 卡 / FUSE 挂载没有 POSIX 权限位，ninja、链接器与 exec 都需要真实权限位 |
| 体积 | bootstrap 压缩包约 90 MiB，安装后约 392 MiB，APK 相应增大约 100 MiB |

### 本地生成 bootstrap

```bash
# --ndk 必填，指向 NDK 安装目录（需要其中的 link stubs）
python scripts/build_bootstrap.py --ndk /path/to/android-sdk/ndk/27.3.13750724

# 指定输出路径；.deb 会按 SHA256 缓存到 .uide-cache/debs，命中缓存即不再联网
python scripts/build_bootstrap.py --ndk /path/to/ndk/27.3.13750724 \
  --out app/src/main/assets/bootstrap-aarch64.bin
```

生成物（`bootstrap-aarch64.bin` / `.json`）不入库，由 `.gitignore` 忽略；CI 中 `bootstrap.yml` 会自动生成，`ci.yml` 在编译 APK 前先执行该脚本。

### 已知限制

- 仅支持 `arm64-v8a`。
- 不提供交互式终端与 `pkg` 包管理器。
- `/system/bin/sh` 等系统可信二进制会忽略 `LD_PRELOAD`，因此脚本需由应用通过内置执行器启动，而不是从系统 shell 间接 exec。

## 诊断入口

设备 logcat 常被厂商安全机制过滤，排查问题时可直接用 adb 触发内置自检并将报告写入外部目录：

```bash
adb shell am start -n com.uniaball.uide/.MainActivity -e uide_self_test 1
adb shell am start -n com.uniaball.uide/.MainActivity -e uide_install_toolchain 1
adb shell am start -n com.uniaball.uide/.MainActivity -e uide_build <项目子目录>
adb shell am start -n com.uniaball.uide/.MainActivity -e uide_env_probe
adb shell am start -n com.uniaball.uide/.MainActivity -e uide_probe

adb pull /sdcard/Android/data/com.uniaball.uide/files/selftest-report.txt
adb pull /sdcard/Android/data/com.uniaball.uide/files/toolchain-report.txt
adb pull /sdcard/Android/data/com.uniaball.uide/files/build-report.txt
```

## 构建与运行

1. 使用最新稳定版 **Android Studio** 打开 `UIDE/` 目录。
2. 点击 **Sync Project with Gradle Files** 同步依赖。
3. 连接安卓设备或启动模拟器，点击 **Run**（▶）运行 `app`。

## 技术栈

| 项 | 说明 |
| --- | --- |
| 语言 | Kotlin |
| UI | Jetpack Compose + Material 3 |
| 构建 | Gradle Kotlin DSL（Gradle 8.13 / AGP 8.13.2 / Kotlin 2.3.20 / Compose BOM 2026.06.01） |
| 平台 | minSdk 24 / compileSdk 36 / targetSdk 36 |
| 原生 | NDK `27.3.13750724` + CMake `3.22.1`（仅 `arm64-v8a`） |
| 字体 | JetBrains Mono（等宽） |

## 持续集成 / 发布

仓库内置两条 GitHub Actions 工作流：

- **ci.yml** —— 推送 / PR 到 `main` 时自动构建，并上传 debug APK 产物（构建前先生成 bootstrap 资产）。
- **bootstrap.yml** —— 手动触发或源码变更时重新生成 `bootstrap-aarch64.bin` 工具链资产。
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
