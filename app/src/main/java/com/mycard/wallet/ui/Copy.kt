package com.mycard.wallet.ui

/** Main UI copy is collected here; enum labels and bank presets are data catalogs. */
object Copy {
    const val tagline = "私人卡包 · 只属于你"
    /** Home top-bar title. Single source for every home state and arrangement. */
    const val collection = "我的卡片"
    const val offline = "完全离线 · 私密保存"
    const val emptyTitle = "每一张卡，都值得珍藏。"
    const val emptyBody = "添加一张银行卡，开启你的私人收藏。\n仅保存卡面与后四位，无需连接银行。"
    const val add = "添加卡片"
    const val edit = "编辑卡片"
    const val save = "保存卡片"
    const val cancel = "取消"
    const val back = "返回"
    const val search = "搜索银行、卡组织、尾号、昵称或标签"
    const val noResults = "没有找到卡片"
    const val noResultsBody = "试试其他关键词或分类。"
    const val details = "卡片详情"
    const val settings = "设置"
    const val bank = "银行或发卡机构"
    const val network = "卡组织"
    const val type = "卡片类型"
    const val lastFour = "卡号后四位"
    const val fourHint = "仅填写 4 位数字"
    const val nickname = "自定义昵称（可选）"
    const val tags = "用途标签（可选）"
    const val notes = "备注（可选）"
    const val credentialsHint = "请勿填写完整卡号、CVV、有效期或密码。"
    const val realFace = "真实卡面"
    const val template = "自定义模板"
    const val face = "卡面外观"
    const val import = "选择卡面图片"
    const val replace = "更换 / 重新裁剪图片"
    const val imageHint = "支持 PNG、JPG、WebP。裁剪和遮挡在本机完成，原图不会写入应用磁盘。"
    const val chooseImage = "请先选择并处理一张卡面图片"
    const val templates = "选择模板"
    const val primary = "主颜色"
    const val secondary = "辅助颜色"
    const val gradient = "渐变效果"
    const val texture = "背景纹理"
    const val tailMode = "真实卡面尾号"
    const val tailPosition = "尾号位置"
    const val pin = "置顶"
    const val unpin = "取消置顶"
    const val archive = "归档"
    const val unarchive = "取消归档"
    const val delete = "删除卡片"
    const val deleteBody = "确定删除这张卡片？删除后可在底部提示中短时间撤销。"
    const val undo = "撤销"
    const val copy = "复制后四位"
    const val copied = "后四位已复制"
    const val arrangement = "排布方式"
    const val viewOptions = "视图选项"
    const val currentLayout = "当前排布"
    const val currentSort = "当前排序"
    const val reorder = "调整顺序"
    const val reorderHint = "长按右侧手柄上下拖动；置顶卡片在各自分组内排序。"
    const val open = "查看详情"
    const val unsaved = "放弃未保存的修改？"
    const val discard = "放弃修改"
    const val theme = "应用主题"
    const val followSystem = "跟随系统深浅色"
    const val themeHint = "手动选择主题会关闭跟随系统。开启后保留当前风格，由系统决定明暗；不修改银行卡颜色。"
    const val changeLog = "更新日志"
    const val previousCard = "上一张卡片"
    const val nextCard = "下一张卡片"
    const val pagerHint = "左右滑动切换，点击当前卡片查看详情"
    const val firstCard = "已到第一张"
    const val lastCard = "已到最后一张"
    const val layout = "首页布局"
    const val animations = "界面动画"
    const val sorting = "卡片排序"
    const val privacy = "隐私与安全"
    const val privacyLock = "隐私锁"
    const val privacyLockHint = "使用系统生物识别或设备凭据；离开应用后自动锁定。"
    const val backup = "本地加密备份"
    const val restore = "从备份恢复"
    const val clear = "清空全部卡片"
    const val clearBody = "将删除所有卡片与卡面文件，此操作无法撤销。建议先创建加密备份。"
    const val about = "关于 MyCard"
    val aboutBody get() = "MyCard 私人卡包 ${com.mycard.wallet.BuildConfig.VERSION_NAME}\n\n卡面收藏与管理工具，支持完全离线使用。没有账户、广告、追踪或云同步。\n\n不支持支付、NFC、余额查询或转账。银行卡名称仅用于识别你的私人收藏。请仅导入有权使用的素材。"
    const val showWordmark = "显示 MyCard 字标"
    const val showNotes = "在卡面上显示备注"
    const val logoPosition = "卡组织标志位置"
    const val amexLayout = "American Express 侧边布局"
    const val appearance = "外观与显示"
    const val storage = "本地数据"
    const val locked = "你的卡包，只有你能打开。"
    const val unlock = "解锁卡包"
    const val loading = "正在打开私人卡包"
}
object TemplateCatalog {
    // IDs 0..5 are permanent: append, never reorder or replace stored template identities.
    val names = listOf("极简纯色", "深色商务", "蓝色科技", "磨砂金属", "优雅金色", "几何纹理",
        "曜石黑金", "深色磨砂", "香槟金属", "拉丝金属", "极光流纹", "碳纤维", "星空微粒", "丝绸波纹", "极简陶瓷", "柔和渐层")
    val colors = listOf(
        0xFF304F46L to 0xFF7B9B80L, 0xFF151F2DL to 0xFF424B61L,
        0xFF123A79L to 0xFF2587A0L, 0xFF454C54L to 0xFF949FA6L,
        0xFF685020L to 0xFFD3B979L, 0xFF4D385BL to 0xFFB18B9EL,
        0xFF101014L to 0xFFB49761L, 0xFF24262AL to 0xFF4D5258L,
        0xFFC5B69AL to 0xFFF2E4CAL, 0xFF555E69L to 0xFFB8C2CCL,
        0xFF102C3CL to 0xFF66C6B1L, 0xFF141A20L to 0xFF3F4C54L,
        0xFF101B36L to 0xFF5868A0L, 0xFF4B344DL to 0xFFAF8DA0L,
        0xFFE2E7E4L to 0xFFBACCC5L, 0xFFB9B6D7L to 0xFFECD7CBL
    )
    val swatches = listOf(0xFF183D32L, 0xFF304F46L, 0xFF151F2DL, 0xFF123A79L, 0xFF2587A0L, 0xFF454C54L,
        0xFF949FA6L, 0xFF685020L, 0xFFD3B979L, 0xFF4D385BL, 0xFFB18B9EL, 0xFF8B393CL,
        0xFF101014L, 0xFFC5B69AL, 0xFF102C3CL, 0xFF66C6B1L, 0xFFE2E7E4L, 0xFFB9B6D7L,
        0xFFECD7CBL, 0xFFCA735EL, 0xFF456681L, 0xFF303C54L, 0xFFB9C6AEL, 0xFF7F6460L)
}
val BankPresets = listOf("中国工商银行", "中国农业银行", "中国银行", "中国建设银行", "交通银行", "中国邮政储蓄银行",
    "招商银行", "中信银行", "中国光大银行", "华夏银行", "中国民生银行", "广发银行", "平安银行", "兴业银行",
    "浦发银行", "北京银行", "上海银行", "宁波银行", "江苏银行", "浙商银行", "汇丰银行", "渣打银行", "花旗银行", "Chase", "Revolut", "Wise")
