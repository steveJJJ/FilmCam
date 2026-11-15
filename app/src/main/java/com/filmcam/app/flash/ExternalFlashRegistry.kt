package com.filmcam.app.flash

import android.content.Context

/**
 * 外置闪光灯驱动注册表（品牌无关的路由层）。
 *
 * 职责：**「哪台灯用哪个驱动」的匹配**与**驱动的构造**，仅此而已。
 *  - 匹配维度：BLE 广播名 / 已知 MAC（未来的自动识别入口）；
 *  - 找不到匹配时回退 [default]（第一个注册的驱动 = FotorGear，
 *    兼容现状：目前 App 只认 FotorGear，且它直接按已知 MAC 连接、不扫描）。
 *
 * 加新品牌的完整步骤（对齐架构图 2026-11-15）：
 *  1. 写一个 `XxxFlash : ExternalFlashDriver`（GATT UUID / 帧协议全封装在自己类里）；
 *  2. 在 [ensureDefaults] 里 register 一个 [Entry]（名称前缀 + 已知地址 + 能力档）；
 *  3. 完事。FlashController / MainActivity / 相机层零改动。
 *
 * 后续（自学习配对向导，同日落地为第二层）：[syncLearned] 把 [FlashProfileStore]
 * 里的自学习档案注册成 priority=1 的条目（[LearnedProfileDriver]），
 * [createPreferred] 让 FlashController 在「内置 FotorGear」与「用户自己配对
 * 学习的灯」之间优先后者（最近配对优先）。内置条目 priority=0 兼兜底。
 * 档案格式见 [FlashProfileCodec]（行式文本，非 JSON —— 可直接邮件/论坛贴导入）。
 *
 * JVM 单测纪律：本类的匹配逻辑纯内存；factory 是 lambda，
 * 只有真正 create() 才会加载具体驱动类，单测里不会触发。
 */
object ExternalFlashRegistry {

    /** 一个可注册的驱动条目。 */
    class Entry(
        /** 品牌标识（稳定字符串，档案库 / 日志用）。 */
        val brandId: String,
        /** 人机可读品牌名（状态栏 / 设置页用）。 */
        val displayName: String,
        /** 能力档，见 [FlashCapability]。 */
        val capability: FlashCapability,
        /** BLE 广播名精确 / 前缀匹配表（空 = 不靠名字认）。 */
        val deviceNames: Set<String> = emptySet(),
        /** 已知 MAC 直连表（空 = 不靠地址认）。 */
        val knownAddresses: Set<String> = emptySet(),
        /** 驱动构造工厂（拿到 Context 是因为要 BluetoothManager / 外部文件目录）。 */
        val factory: (Context) -> ExternalFlashDriver,
        /**
         * 优先级：内置条目 0，自学习档案条目 1。[createPreferred] 取最高者
         * （同优先级取后注册的，即最近配对的）。[default] / [pick] 不受影响。
         */
        val priority: Int = 0
    )

    private val entries = mutableListOf<Entry>()

    @Volatile
    private var defaultsRegistered = false

    /** 进程内注册一次内置驱动（幂等）。 */
    @Synchronized
    fun ensureDefaults() {
        if (defaultsRegistered) return
        defaultsRegistered = true
        register(fotorgearEntry())
    }

    /** FotorGear COB（当前唯一在册驱动，从 FlashController 硬编码迁移而来）。 */
    private fun fotorgearEntry() = Entry(
        brandId = "fotorgear",
        displayName = "FotorGear COB",
        capability = FlashCapability.DUMB,
        deviceNames = setOf("FG-COB-FLASH"),
        knownAddresses = setOf("DA:BA:CD:E2:BE:B0"),
        factory = { ctx -> FotorGearFlash(ctx) }
    )

    /** 注册一个驱动（重复 brandId 后者覆盖前者）。 */
    @Synchronized
    fun register(entry: Entry) {
        entries.removeAll { it.brandId == entry.brandId }
        entries.add(entry)
    }

    /** 清空（测试用）。 */
    @Synchronized
    fun resetForTest() {
        entries.clear()
        defaultsRegistered = false
    }

    /**
     * 按广播名 / MAC 挑驱动。
     *  - 名字优先（广播里能看到就认），地址兜底（已知设备直连场景）；
     *  - 大小写不敏感（BLE 广播名的坑：同款灯固件大小写漂移过）；
     *  - 认不出返回 null —— 调用方决定回退 [default] 还是走配对向导。
     */
    @Synchronized
    fun pick(deviceName: String?, address: String?): Entry? {
        ensureDefaults()
        val name = deviceName?.trim()?.uppercase()
        if (!name.isNullOrEmpty()) {
            entries.firstOrNull { e ->
                e.deviceNames.any { known ->
                    val k = known.uppercase()
                    name == k || name.startsWith(k)
                }
            }?.let { return it }
        }
        val addr = address?.trim()?.uppercase()
        if (!addr.isNullOrEmpty()) {
            entries.firstOrNull { it.knownAddresses.any { a -> addr == a.uppercase() } }
                ?.let { return it }
        }
        return null
    }

    /** 兜底驱动（现状 = FotorGear：不扫描、按已知 MAC 直连，行为与 vc85 前完全一致）。 */
    @Synchronized
    fun default(): Entry {
        ensureDefaults()
        return entries.first()
    }

    /**
     * 从档案库同步自学习条目（幂等：每次全量重建 learned 条目，删除 / 重配立即生效）。
     * brandId 用 `learned:<MAC>` 隔离命名空间，不与内置条目冲突。
     * 入参是档案列表而非 Store（纯内存逻辑，JVM 可单测；Context 拆在调用方）。
     */
    @Synchronized
    fun syncLearned(profiles: List<FlashProfile>) {
        ensureDefaults()
        entries.removeAll { it.brandId.startsWith("learned:") }
        // 按配对时间升序注册：同优先级取后注册的 ⇒ pairedAt 最大者胜出
        profiles.sortedBy { it.pairedAt }.forEach { p ->
            register(
                Entry(
                    brandId = "learned:${p.address}",
                    displayName = p.displayName,
                    capability = p.capability,
                    deviceNames = setOf(p.deviceName),
                    knownAddresses = setOf(p.address),
                    factory = { ctx -> LearnedProfileDriver(ctx, p) },
                    priority = 1
                )
            )
        }
    }

    /**
     * 建会话用的驱动选择（纯逻辑）：先 [pick] 认品牌；认不出时优先用户
     * 自己配对学习的灯（priority 最高；同级取后注册的 = 最近配对），
     * 没有档案时兜底内置 FotorGear。
     */
    @Synchronized
    fun preferredEntry(deviceName: String? = null, address: String? = null): Entry {
        pick(deviceName, address)?.let { return it }
        // 倒序后取首个最大优先级 = 同级里「最后注册」的（syncLearned 升序注册 ⇒ 最近配对）
        return entries.asReversed().maxByOrNull { it.priority } ?: default()
    }

    /**
     * 构造驱动实例（[preferredEntry] 的 Context 版，FlashController 唯一入口；
     * [create] 保留给显式品牌场景 / 测试）。
     */
    fun createPreferred(context: Context, deviceName: String? = null, address: String? = null): ExternalFlashDriver {
        return preferredEntry(deviceName, address).factory(context)
    }

    /**
     * 构造驱动实例。无扫描信息时用 [default]（兼容现有「直接按已知 MAC 连」的行为）；
     * 有广播信息时先 [pick] 认品牌。
     */
    fun create(context: Context, deviceName: String? = null, address: String? = null): ExternalFlashDriver {
        val entry = pick(deviceName, address) ?: default()
        return entry.factory(context)
    }
}
