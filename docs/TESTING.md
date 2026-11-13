# MyCard 测试说明与公开版本验证记录

本文件说明测试范围、运行方式，以及在公开源码版本上实际执行的结果。原始私人版的完整交付记录不随仓库发布。

## 运行方式

```bash
# JVM / Robolectric 桌面测试（30+ 个测试类）
./gradlew :app:testDebugUnitTest

# Debug 构建
./gradlew :app:assembleDebug

# 真机 / 模拟器仪器测试（需要连接设备）
./gradlew :app:assembleDebugAndroidTest
adb shell am instrument -w com.mycard.wallet.test/androidx.test.runner.AndroidJUnitRunner
```

Windows 下把 `./gradlew` 换成 `.\gradlew.bat`。首次运行 JVM 测试需要联网下载 Robolectric 的 Android 运行环境，仅发生在开发电脑，不会打包进应用。

## 覆盖重点

- **密钥与加密**：`KeyGenerationLifecycleTest`、`KeyAvailabilityWriteGuardTest`、`CryptoTest` 覆盖首次初始化并发、密钥缺失时不隐式生成、读取/加密/撤销/恢复前的写入守卫。
- **损坏记录隔离**：`DamagedRecordReadIsolationTest`、`DamagedWalletDeletionCleanupTest` 覆盖单条密文/解析/校验失败、整包仍可读、无法读取记录不被重排或删除。
- **备份与图片**：`BackupCompletionTest`、`BackupLifecycleTest`、`BackupRepeatLifecycleTest`、`BackupImageOwnershipTest`、`RestoreDiagnosticsTest`、`StagedArtworkCleanupTest`、`CardImageCacheConcurrencyTest` 覆盖取消、生命周期切换、暂存文件所有权与恢复失败诊断；`TemplateBackupCompatibilityTest` 使用仓库内的合成 `.backups/t3` 固件验证历史备份兼容性。
- **数据协调**：`DataOperationCoordinationTest`、`DataOperationUiTest` 覆盖并发写、过期撤销与界面操作序列。
- **首页与交互**：`HomeLayoutV2Test`、`HomeTopBarV2Test`、`HomeEdgeCaseTest`、`PatchInteractionTest`、`CardVisualTest` 覆盖三种排布、视图选项、窄屏、关闭动画、连续圆角、文字对比度与品牌文字行不额外加底板。
- **AppearanceExperienceTest / DesignUiTest / ShowcaseExperienceTest** 覆盖主题、排布持久化与界面渲染。

## 公开版本的测试调整

私人版本有 3 个测试直接断言官方品牌图形的像素或资源 ID（中国银行行徽、银联矢量、银行别名到图形的映射）。公开版本不包含这些图形，相应测试被删除或改写为文字回退断言，删除位置留有注释说明。除此之外未修改任何业务测试。

## 本次公开版本验证结果

2026-11-13 在维护者电脑（Windows、JDK 25.0.3、Android SDK Platform 37、Gradle 9.6.0）从本公开目录直接构建，未使用私人工程目录。

| 项目 | 结果 |
| --- | --- |
| JVM 单元测试 | `:app:testDebugUnitTest` BUILD SUCCESSFUL（4m 16s）；34 个测试类 / **283 项通过**，0 失败 / 0 错误 / 0 跳过 |
| Debug 构建 | `:app:assembleDebug` BUILD SUCCESSFUL（13s），产物 `app/build/outputs/apk/debug/app-debug.apk`（69,023,390 字节，SHA-256 `F8A3B40ADCCFDAF0F0EFA0E41CB64F7CF56B519EA46022A1C8636735A8D81233`） |
| APK 元数据 | 包名 `com.mycard.wallet`、1.2.1 / versionCode 6、minSdk 29、targetSdk 37；arm64-v8a / armeabi-v7a / x86 / x86_64；`allowBackup=false`；v2 签名验证通过 |
| APK 权限 | 仅 `USE_BIOMETRIC`、`USE_FINGERPRINT` 与 AndroidX 动态接收器权限；无 INTERNET、存储、相机或 NFC 权限 |
| 敏感信息扫描 | 源码、文档与构建脚本中未发现本机绝对路径（用户目录或项目盘符路径）、私人 JRE 路径、本机代理端口、调试证书指纹或私钥片段 |

测试数量比私人版本少 3 项，正是上述品牌图形相关测试被移除的结果。APK 只用于本地验证，**不会**随仓库发布；不同电脑的 Debug 签名不同，不要据此做覆盖安装假设。

## 仍需真机验证

真机覆盖安装、滚动/翻页性能、系统截图浮层/录屏/返回手势、生物识别与锁屏解锁、字体和显示缩放仍需人工验收。桌面测试和模拟器结果不能替代真机结论。
