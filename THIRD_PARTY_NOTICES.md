# 第三方组件与资源清单 / Third-Party Notices

本项目源代码采用 Apache License 2.0（见 `LICENSE`）。下列第三方组件通过 Gradle 依赖在构建时解析，未以源码或二进制形式复制进本仓库。银行与卡组织的商标图形**不包含**在本公开版本中；详见 [BRAND_NOTICE.md](BRAND_NOTICE.md) 与 [docs/BRAND_ASSET_INVENTORY.md](docs/BRAND_ASSET_INVENTORY.md)。

The project source is licensed under Apache License 2.0 (`LICENSE`). The components below are resolved by Gradle at build time and are not vendored in this repository. Bank and card-network trademark artwork is **not** included; see the notice documents above.

## 构建期依赖 / Build-time dependencies

| 组件 / Component | 版本 / Version | 许可证 / License | 用途 / Purpose |
| --- | --- | --- | --- |
| Android Gradle Plugin (`com.android.application`) | 9.4.1 | Apache-2.0 | Android 构建插件 |
| Gradle & Gradle Wrapper | 9.6.0 | Apache-2.0 | 构建系统与 Wrapper（`gradle/wrapper/gradle-wrapper.jar`） |
| Kotlin Compose compiler plugin | 2.2.10 | Apache-2.0 | Compose 编译插件 |
| KSP (`com.google.devtools.ksp`) | 2.3.4 | Apache-2.0 | Room 代码生成 |
| Kotlin standard library / kotlinx.coroutines | 随 Kotlin 2.2.10 / 1.10.2 | Apache-2.0 | 语言运行时与协程 |
| AndroidX Core KTX | 1.10.1 | Apache-2.0 | Android 基础扩展 |
| AndroidX Activity Compose | 1.8.0 | Apache-2.0 | Compose Activity 集成 |
| AndroidX Lifecycle (runtime-ktx / viewmodel / runtime-compose) | 2.6.1 / 2.9.4 | Apache-2.0 | 生命周期与 ViewModel |
| AndroidX Navigation Compose | 2.9.5 | Apache-2.0 | 页面导航 |
| AndroidX Biometric | 1.1.0 | Apache-2.0 | 隐私锁认证 |
| AndroidX Fragment KTX | 1.8.9 | Apache-2.0 | FragmentActivity 支持 |
| Jetpack Compose BOM / UI / Material 3 | 2026.02.01 | Apache-2.0 | 界面与设计系统 |
| Material Icons Extended | 1.7.8 | Apache-2.0 | 图标 |
| AndroidX Room (runtime / ktx / compiler / testing) | 2.8.4 | Apache-2.0 | 本地加密记录存储 |
| AndroidX DataStore Preferences | 1.1.7 | Apache-2.0 | 界面偏好设置 |
| Coil 3 (coil-compose) | 3.3.0 | Apache-2.0 | 本地图片解码与显示（未引入网络模块） |

## 仅测试使用 / Test-only

| 组件 / Component | 版本 / Version | 许可证 / License |
| --- | --- | --- |
| JUnit 4 | 4.13.2 | Eclipse Public License 1.0 |
| Robolectric | 4.17 | MIT |
| AndroidX Test Ext JUnit / Espresso | 1.1.5 / 3.5.1 | Apache-2.0 |
| AndroidX Compose UI Test (JUnit4 / Manifest) | 随 Compose BOM | Apache-2.0 |
| `org.json:json` | 20250517 | Public Domain（JSON.org） |
| kotlinx-coroutines-test | 1.10.2 | Apache-2.0 |

## 其他说明 / Other notes

- Android SDK、Android 平台与构建工具受 Android SDK 条款约束，仅用于编译，不随本仓库分发。
- Gradle 的依赖缓存（`~/.gradle`）与其中的许可证文件由 Gradle 管理，不在本仓库内。
- 应用图标（`ic_launcher_*.xml` 与 mipmap）为本项目原创矢量图形；卡面背景由 Compose 程序绘制，不含第三方图片。
- 测试资源 `app/src/test/resources/backups/t3/*.mycard` 是本项目源代码生成的合成测试数据，不含任何真实卡片信息。
- 品牌名称仅作为文字用于识别用户自己的收藏，相关商标归各自权利人，本仓库不附带任何品牌图形文件。
