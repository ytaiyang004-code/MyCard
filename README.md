# MyCard · 离线私人卡包

完全离线的 Android 银行卡卡面收藏与管理应用。只记录后四位，不提供支付、NFC、余额查询、银行连接或交易功能。

> **公开版本说明**：这是私人版本 v1.2.1 的公开源码版本。银行与卡组织图形（第三方商标）**未包含**在本仓库中，界面改为文字显示；本版本也不再附带历史 APK、发布记录与本机代理配置。它并非与私人构建逐字节相同，差异见 [docs/OPEN-SOURCE-EDITION.md](docs/OPEN-SOURCE-EDITION.md) 与 [BRAND_NOTICE.md](BRAND_NOTICE.md)。
>
> **Unofficial project.** MyCard is a personal, offline card-art collection app. It is not affiliated with, sponsored by, or endorsed by any bank or card network. This public source tree contains no third-party brand artwork; names are shown as plain text. Licensed under Apache-2.0 (see `LICENSE`), which does not cover any trademark. Contact: **oceanfish1113@outlook.com**.

## 项目状态

- 版本 **1.2.1 / versionCode 6**，版本源为根目录 `version.properties`，更新日志见 [CHANGELOG.md](CHANGELOG.md)。
- 个人维护项目，**没有**发布预编译 APK、GitHub Releases 或应用商店版本；请自行从源码构建。
- 本仓库未初始化 Git 历史、未配置远程仓库，也不包含原作者的历史提交。公开版本的改动与限制记录在 [docs/OPEN-SOURCE-EDITION.md](docs/OPEN-SOURCE-EDITION.md)。
- 仅提供简体中文界面；minSdk 29（Android 10），compileSdk / targetSdk 37。

## 功能

- **卡片管理**：添加、编辑、删除（含撤销）、置顶、归档、长按拖动排序；搜索与分类筛选可组合使用。
- **三种首页排布**：堆叠卡包、完整列表、翻页卡册；排布与排序在“视图选项”中管理并持久保存。
- **两种卡面模式**：
  - 自定义模板：16 种 Compose 程序绘制背景（原 6 种 + 10 种材质主题），主/辅助色可用二维 HSV 画板与 24 色推荐自定义。
  - 真实卡面：通过系统图片选择器导入 PNG / JPEG / WebP，在固定 1.586:1 画框内平移缩放、去除白边、手工黑色遮挡，原图仅在内存处理。
- **卡面元素**：银行/发卡机构名称、卡组织名称、卡片类型、尾号位置、备注（默认关闭）、MyCard 字标开关、卡组织标志位置与 American Express 侧边布局。
  - 公开版本中银行与卡组织以文字显示；私人版本曾有官网图形，未随本仓库分发。
- **详情页**：同一归档分组内左右滑动切换，编辑、置顶、归档、复制后四位（标记系统敏感剪贴板）。
- **设置**：四套应用主题、跟随系统深浅色、隐私锁（系统生物识别/设备凭据）、本地加密备份与恢复、清空全部卡片、离线更新日志。
- **加密备份**：`.mycard` 文件，PBKDF2 + 分块 AES-GCM，单份最多 5,000 张卡片 / 256 MiB 明文；支持旧 v1 清单，恢复前完整验证并在 Room 事务中提交。

## 隐私特征

- Manifest 不申请 INTERNET、NFC、相机、相册或外部存储权限；仅使用生物识别权限。
- 没有遥测、广告、追踪 SDK，也没有云同步或云备份；`allowBackup=false`，备份规则排除应用数据。
- Room 只保存随机 UUID 与 AES-GCM 加密 payload；图片经裁剪、遮挡、重编码后加密存放于 `noBackupFilesDir`，Coil 禁用磁盘与内存缓存且不引入网络模块。
- 备份密码不保存，忘记后无法恢复。详见 [docs/SECURITY.md](docs/SECURITY.md)。

## 构建

需要 JDK 21+ 启动 Gradle，并准备 Android SDK Platform 37。工程通过 `gradle/gradle-daemon-jvm.properties` 声明 daemon 使用 JDK 25；本机没有时会由 Gradle 工具链解析下载。首次构建会从 Google Maven / Maven Central 下载依赖；仓库中**没有**提交任何代理设置，如需代理请配置在 `~/.gradle/gradle.properties`。

1. 准备 SDK 路径（二选一）：
   - 在仓库根目录创建 `local.properties`（已被 `.gitignore` 忽略）：`sdk.dir=C\:\\path\\to\\Android\\Sdk`；
   - 或设置环境变量 `ANDROID_HOME`。
2. 构建与测试：

```powershell
# Windows PowerShell
$env:JAVA_HOME = 'C:\path\to\jdk-25'
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
```

```bash
# macOS / Linux
export JAVA_HOME=/path/to/jdk-25
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Debug APK 输出在 `app/build/outputs/apk/debug/app-debug.apk`，同时另存为 `app/build/archives/MyCard-v1.2.1-debug.apk`。

安装到已启用“开发者选项”的设备：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Debug 签名只适合个人测试；正式分发请自行创建 Release 签名，并妥善保管私钥（不要提交到仓库）。可用 `scripts/Verify-Apk.ps1 -Apk <路径>` 检查包名、SDK、权限与签名。

真机测试可用：

```bash
./gradlew :app:assembleDebugAndroidTest
adb shell am instrument -w com.mycard.wallet.test/androidx.test.runner.AndroidJUnitRunner
```

JVM 测试首次运行需要下载 Robolectric 的 Android 运行环境，仅发生在开发电脑，不会打包进应用。

## 目录

| 路径 | 内容 |
| --- | --- |
| `app/src/main/java/com/mycard/wallet/MainActivity.kt` | 启动、身份验证与生命周期；导航入口 `ui/WalletApp.kt` |
| `WalletViewModel.kt` | MVVM 状态与业务操作 |
| `data/` | 加密 Room 记录、仓库、DataStore、校验 / 搜索 / 排序 |
| `security/` | Keystore、AES-GCM、密码派生、流式备份与事务恢复 |
| `images/` | 内存解码、裁剪几何、白边去除、不可逆像素遮挡 |
| `ui/` | 首页、编辑器、详情、排序、设置、Canvas 卡面与文案目录（`Copy.kt`） |
| `app/schemas/` | Room v1 schema；后续升级需提供显式迁移 |
| `app/src/test/` | JVM 与 Robolectric / Compose 桌面测试 |
| `app/src/androidTest/` | 真机 Keystore、存储与界面流程测试 |
| `docs/` | 功能、设计、安全、测试、品牌素材清单与公开版差异 |

## 测试

```bash
./gradlew :app:testDebugUnitTest
```

测试覆盖加密与密钥生命周期、损坏记录隔离、备份/恢复事务、图片所有权与并发、首页三种排布与文字对比度等。公开版本的验证结果与限制见 [docs/TESTING.md](docs/TESTING.md)。

## 素材与商标

- 本仓库不包含任何银行或卡组织图形，也没有使用仿绘或生成式图片替代官方徽标。逐项来源、摘要与未纳入原因见 [docs/BRAND_ASSET_INVENTORY.md](docs/BRAND_ASSET_INVENTORY.md)。
- 品牌名称仅用于识别用户自己保存的卡片，相关商标归各自权利人。本项目为非官方独立项目，与任何品牌没有合作或认可关系。详见 [BRAND_NOTICE.md](BRAND_NOTICE.md)。
- 用户自行导入的卡面图片应由用户自行取得使用权限；应用不会联网抓取素材。
- 第三方组件与许可证见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## 安全

请勿在公开 Issue 中披露漏洞细节或真实卡片数据。报告方式见 [SECURITY.md](SECURITY.md)。

## 联系方式

维护者邮箱：**oceanfish1113@outlook.com**。商标异议、安全问题与一般问题均可通过该邮箱联系。

## 开源许可证

MyCard 项目中由开发者原创且有权授权的源代码，采用 **Apache License 2.0（Apache 2.0 开源许可证）** 发布。

在遵守许可证条款的前提下，允许他人使用、复制、修改和分发相关源代码，包括用于商业用途。

版权所有 © 2026 Taiyang Yu

本项目涉及的第三方银行名称、卡组织名称、商标及品牌标识，其相关权利均归各自权利人所有，**不因本项目采用 Apache License 2.0 而自动获得授权**。

有关品牌与商标的详细声明，请参阅 [BRAND_NOTICE.md](BRAND_NOTICE.md)。

完整许可证条款请参阅 [LICENSE](LICENSE)。