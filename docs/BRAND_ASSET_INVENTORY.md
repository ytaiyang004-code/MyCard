# 品牌素材清单与授权状态 / Brand Asset Inventory

本文件逐项列出私人版本 v1.2.1（提交 `7bbe1b58`）中使用过的银行与卡组织图形。**公开版本没有包含其中任何一项**：所有素材均来自品牌官网或官网引用的 CDN，属于第三方商标，未查到允许在开源仓库中再分发的明确授权。

- 取证日期：2026-10-16（Asia/Shanghai）。
- SHA-256 为私人版本中实际文件（下载原稿或转换后的 Android 资源）的摘要，保留用于核对来源，**不代表**该文件已获再分发许可。
- 公开版本的处理：不打包任何银行/卡组织图形，改为文字显示（`ui/BrandIdentity.kt`）。没有使用仿绘、生成式图片或"看起来像官方"的替代图标。

## 1. 银行徽标 / Bank emblems

| 品牌 | 原始来源（页面 / 直链） | 私有版本资源 | SHA-256 | 授权证据 | 公开版本 |
| --- | --- | --- | --- | --- | --- |
| 中国工商银行 ICBC | https://www.icbc.com.cn/ ；`https://v.icbc.com.cn/userfiles/Resources/ICBC/shouye/images/2017/logo.png` | `drawable-nodpi/bank_icbc.png`（视口裁剪） | `24be2a6d6747c9b8ee2d602d448aeb65d233d1d3380bd2e0d1fc7d3e3df0a90e` | 官网可公开下载，未找到再分发许可 | 不包含，文字显示 |
| 中国农业银行 ABC | https://www.abchina.com/cn/ ；`https://www.abchina.com/cn/images/logo_ue2.png` | `drawable-nodpi/bank_abc.png` | `b0dc140a8bb52abcc1c13a523b010afad76a94af3e270d1fef1b47e15cba57cf` | 同上 | 不包含，文字显示 |
| 中国银行 BOC | https://www.bocusa.com/ ；`https://www.bocusa.com/themes/custom/boc/slice/assets/images/footer-main-logo.svg` | `drawable-anydpi/bank_boc.xml`（由 SVG 行徽路径转换） | 原稿 `c52f842391960c0347da608a68ec1553308ffee40d3ce354cd700bc89ff55730`；转换后 `760df2e9f39738f2125de483a96f2bd68ddcd48062cb6205a811c38c0544c920` | 同上 | 不包含，文字显示 |
| 中国银行（旧版组合图） | https://www.boc.cn/ ；`https://www.boc.cn/images/boc2013_logo.png` | 原稿存档 `docs/brand-sources/bank_boc_lockup.png`（未进 APK） | `a61810be66550d5d97b745636aa540a5fde60bc5576be0c95e0f6a49a7d780dc` | 同上 | 不包含 |
| 中国建设银行 CCB | https://www2.ccb.com/chn/home/index.shtml ；`https://www2.ccb.com/chn/fileDir/bg_image/2020031015231379393.png` | `drawable-nodpi/bank_ccb.png` | `e0eb33430eb24b7844480172a7186181cf7a4d0cd393cb584137c0cefb6c43b6` | 同上 | 不包含，文字显示 |
| 中国邮政储蓄银行 PSBC | https://www.psbc.com/cn/ ；`https://www.psbc.com/cn/images/psbc_hLogo_v2020.PNG` | `drawable-nodpi/bank_psbc.png` | `6f2355a2c6c1bb97a4f1fac7bfad51e949856b83f4940c28e9f118ec4dc7195f` | 同上 | 不包含，文字显示 |
| 交通银行 BOCOM | https://www.bankcomm.com/BankCommSite/default.shtml ；`https://www.bankcomm.com/BankCommSite/shtml/jyjr/cn/images/logo_img_1004.png` | `drawable-nodpi/bank_bocom.png` | `630431be3b209e1023fccf5bdba824193bb93e753cfd5126816bd33f1ed1b921` | 同上 | 不包含，文字显示 |
| 中国中信银行 CITIC | https://www.citicbank.com/ ；`https://www.citicbank.com/images/logo.png` | `drawable-nodpi/bank_citic.png`（64×64 视口） | `1487a667dd4800afa0e8e8ab29d19ab746b0f9b7ab066200b3338e06883467c1` | 同上 | 不包含，文字显示 |
| 招商银行 CMB | https://www.cmbchina.com/ ；`https://wwwcdn.cmbimg.cn/Images/logo.png` | `drawable-nodpi/bank_cmb.png`（红底行徽视口） | `8ef0aa09b7c8a66e7ea715db4b72b10a1d44f7792ef2f057a27cf02d4f85a2fd` | 同上 | 不包含，文字显示 |

## 2. 卡组织图形 / Card network marks

| 品牌 | 原始来源 | 私有版本资源 | SHA-256 | 授权证据 | 公开版本 |
| --- | --- | --- | --- | --- | --- |
| 银联 UnionPay | https://www.unionpayintl.com/en/mediaCenter/brandCenter/artworkDownloadCenter/identification.shtml ；`.../zip/Brandlogo.rar` 内 `Brand logo/Brand logo.ai` | `drawable/network_unionpay_mark.xml` | 原稿 `9427a491cdaabe200f9a5734e24c076adb56b2a8da5fa7aac86b85be82e053e4`；转换后 `9ec3fe5271f1e27e2fadbca0802aab39dc2415b800b250beb1f6a514ac01392e` | 官方品牌包可下载，条款未明确允许开源再分发 | 不包含，文字显示 |
| Visa（浅色卡） | https://corporate.visa.com/en/about-visa/brand.html ；`https://cdn.visa.com/v2/assets/images/logos/visa/blue/logo.png` | `drawable-nodpi/network_visa.png` | `0ebb1697ec9ed895955069d5aca347918d92cd59da740f98f772f468707e2361` | 同上 | 不包含，文字显示 |
| Visa（深色卡反白版） | https://partner.visa.com/ ；`https://partner.visa.com/etc/designs/gpp/assets/images/visa-logo--white.svg` | `drawable/network_visa_white.xml` | 原稿 `a1055a5bbc617c696e71edb8082650433ad80a4d0121694dc293e306c266a899`；转换后 `c414ed5c5e351c45e242518375969a2ae3a27ed1d5b6053ce28f24672096d4b2` | 同上 | 不包含，文字显示 |
| Mastercard | https://www.mastercard.com/news/press/media-resources/ ；`https://www.mastercard.com/news/media/jbcmoato/mastercard-symbol_transparentbg.png` | `drawable-nodpi/network_mastercard.png` | `39d0c3c16f08c675e24907b4c5e3d3411a0f3d5d9b1b56aca5b42cc8551dc8ac` | 同上 | 不包含，文字显示 |
| JCB | https://www.global.jcb/en/ ；`https://www.global.jcb/en/common/images/svg/jcb_emblem.svg` | `drawable/network_jcb.xml` | 原稿 `3691cf3ffd71c76af02943cd72d6fa476465184fc72ee0b0483756f3182ef2d4`；转换后 `76b0eee6b69df8af029fc9079dd181bbfe40165cbcc1a986868cc909546261f7` | 同上 | 不包含，文字显示 |
| American Express | https://www.americanexpress.com/ja-jp/ ；`https://www.aexp-static.com/cdaas/one/statics/axp-static-assets/1.8.0/package/dist/img/logos/dls-logo-bluebox-solid.svg` | `drawable/network_amex.xml` | 原稿 `028f643755987211bf2f3add6c62ae1870a888cf2f4fe3040a4fac7dce2543ab`；转换后 `6989d4248e485d08dc32dc828343e8787c4ccabb6913e267970d833cc3a01eb8` | 同上 | 不包含，文字显示 |

## 3. 未公开的原始下载文件 / Raw downloads not published

私人版本 `docs/brand-sources/` 内的以下文件**不进入公开仓库**（`.gitignore` 已排除该目录）：`bank_boc_footer_logo.svg`、`bank_boc_lockup.png`、`network_unionpay.ai`、`network_unionpay.svg`、`network_unionpay.jpg`、`unionpay-official.rar`、`network_jcb.svg`、`network_amex.svg`、`network_visa_white.svg`、`manifest.json`。

对应下载/转换脚本（`Fetch-BrandAssets.py`、`Convert-BrandVectors.py`、`Convert-BocEmblem.py`、`Convert-UnionPayVector.py`）也未包含在公开仓库中，避免误把未授权素材带回来。

## 4. 人工判断事项 / Items that still need human review

1. 上述每一项的再分发许可都需要权利人书面条款或明确许可才能纳入公开仓库；仅"官网可下载"或"媒体资料页"不能视为授权。
2. 若希望公开版本包含图形，建议取得书面许可，或改用自有设计并接受其与官方徽标不一致的清晰说明。
3. 银行/卡组织名称作为文字用于识别用户自己的卡片，是否属于合理使用需按所在司法辖区判断；本仓库只做文字显示，不做背书暗示。
4. 若权利人提出异议，请通过 [BRAND_NOTICE.md](../BRAND_NOTICE.md) 中的邮箱联系维护者。
