# 公开版本与私人版本的差异

本仓库来自私人工程 **MyCard v1.2.1（提交 `7bbe1b58`）**，2026-11-13 复制为公开源码版本。目标是在不改变核心功能、UI 结构与卡片管理逻辑的前提下，移除不适合公开的内容。

## 未包含的内容

| 内容 | 原因 |
| --- | --- |
| `.git/` 历史、`releases/`、`delivery/`、历史 APK、构建日志 | 私人开发产物，体积大且与源码无关；本仓库未初始化 Git |
| `build/`、`.gradle/`、`.kotlin/`、`.idea/`、`.vscode/`、`local.properties` | 本机构建缓存与 IDE 状态，含本机路径 |
| 银行徽标与卡组织图形（`drawable-nodpi/bank_*.png`、`drawable-anydpi/bank_boc.xml`、`drawable/network_*.xml`） | 第三方商标，未确认再分发许可；处理见 [BRAND_NOTICE.md](../BRAND_NOTICE.md) |
| `docs/brand-sources/` 原始下载文件（SVG / AI / RAR / JPEG / PNG 与清单） | 同上；`.gitignore` 已排除该目录，避免后续误提交 |
| 品牌素材下载与转换脚本（`Fetch-BrandAssets.py`、`Convert-BrandVectors.py`、`Convert-BocEmblem.py`、`Convert-UnionPayVector.py`） | 依赖未公开的原稿目录，公开意义有限 |
| `scripts/Archive-Release.ps1`、`release-history.json` | 依赖私人 `releases/` 归档与 v1.0.1 证书基线，公开仓库不发布 APK |
| 个人开发文档（性能实测、分版本测试记录、发布说明、版本制度） | 含私人交付流程与设备信息；保留功能、设计、安全、测试四类通用文档 |

## 代码改动

1. `ui/BrandCatalog.kt` → `ui/BrandIdentity.kt`：删除品牌图形目录、`BrandAsset` 与位图/矢量绘制逻辑；`BankIdentity` 与 `NetworkIdentity` 只渲染文字，布局位置、字号、对比度与调用点不变。
2. 测试：删除 3 个依赖官方图形像素/资源 ID 的断言（中国银行行徽、银联矢量、银行别名→图形映射），`HomeEdgeCaseTest` 中对应的空图形断言改为纯文字回退渲染测试；其余测试未改。
3. `gradle.properties`：删除本机回环地址的强制代理配置，并注明代理应放在全局 Gradle 配置中。
4. `scripts/Verify-Apk.ps1`：删除硬编码的私人 JBR 路径，改为要求 `JAVA_HOME`/`-JdkRoot`，并在缺少 `local.properties` 时给出明确错误。
5. `.gitignore` / `.gitattributes`：重写为公开仓库规则，排除 APK、签名、日志、`.mycard` 备份与 `docs/brand-sources/`。
6. 新增 `LICENSE`、`BRAND_NOTICE.md`、`THIRD_PARTY_NOTICES.md`、`SECURITY.md`、`README.md`、Issue 模板、`docs/BRAND_ASSET_INVENTORY.md` 与本文件。
7. `CHANGELOG.md` 与 `app/src/main/res/raw/changelog.md` 顶部加入公开版本说明；历史条目保留原样。

## 保持不变的内容

- 包名 `com.mycard.wallet`、版本 1.2.1 / versionCode 6、Room v1 schema 与加密/备份格式。
- 三种首页排布、16 种模板、图片裁剪/遮挡流程、详情、排序、搜索、主题、隐私锁、加密备份与恢复。
- 构建配置：AGP 9.4.1、Gradle 9.6.0、Kotlin Compose 插件 2.2.10、KSP、minSdk 29、compile/targetSdk 37。
- 图标（原创矢量）与所有 Compose 绘制背景。

## 已知功能差异

1. **银行与卡组织不再显示图形**，改为文字。私人版本中的八家银行与五个卡组织徽标不再出现；`LogoPosition` 与 `amexLayout` 设置仍然有效，但现在定位的是文字标签。设置项名称仍沿用“卡组织标志位置”。
2. **不再识别银行别名**（用于选择图形的逻辑随之删除）；银行名称完全按用户输入显示。
3. **不提供预编译 APK**、历史版本归档或签名基线校验；需自行构建。
4. 公开版本构建的 APK 与私人版本 APK 的签名可能不同，不能假定可覆盖安装。切换安装前请备份或保留旧数据。

## 仍需人工判断

- 品牌名称文字使用、卡组织名称与徽标相关的商标问题，需要权利人或法律意见确认；本仓库不声称已获授权。
- LICENSE 附录保留 Apache-2.0 官方示例 `Copyright [yyyy] [name of copyright owner]`；如后续需要明确版权人，可按官方示例自行填写。
