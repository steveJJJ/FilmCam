package com.filmcam.app.camera

/**
 * 方案③：app 层等效 TTL —— **预闪差分**反解直闪曝光（11-14 23:3x）。
 *
 * 背景：公开 Camera2 三条联动入口全部证伪（precapture 不收敛 / 解除钳制带爆
 * 主体 / 场景模式无实效），方案A 反馈闭环 +0.6EV 封顶。剩下的差距 = 闪光
 * 功率随距离联动——拿不到，但**预闪本身就是测光脉冲**：
 *
 *  - 环境测光：预览 AE 收敛值 `ambientIso × ambientSec`（相机对「只有环境光」
 *    的场景想要的曝光量 P_a）；
 *  - 闪光测光：precapture 窗口内**最小** ISO×快门乘积 `flashIso × flashSec`
 *    （ALWAYS_FLASH 武装下 HAL 对「环境光 + 预闪」场景想要的曝光量 P_f）。
 *    场景被预闪照亮得越多，P_f 越小。第一轮实测：近距手掌 2733~3984、
 *    远距 18700~50270——跨 7 倍正好是距离衰减。
 *
 * 两帧差分给出 ratio = P_a / P_f = (La + Lf) / La，ratio 大 = 主体近 = 该少给
 * 曝光。P = k × P_f 中的 k 即预闪→正式帧的功率换算系数：预闪与正式帧用同一
 * 颗 LED、同一距离衰减、同一场景反射，AE 已经把正确量级算出来了，所以
 * **k≈1 而不是与距离无关的大常数**（第一轮 k=9.4 系单点拟合，已判死）。
 *
 * 锚定（第二轮校准，11-14 真机判死第一轮后落值）：k=9.4 是单点拟合
 * （3600≈9.4×384），实测预闪 AE 在近距手掌给出 P_f=2733~3984——同一颗灯、
 * 同一距离衰减、同一反射，**P_f 本身就是该灯该距离的正确曝光量级**，
 * 乘 9.4 = 凭空过曝 3.2EV，且 9.4×2733=25690 ≫ 帽，导致所有命中帧全部
 * 撞 5609、近距手掌零纹理白板（取证 tmp/ttl_1114/verdict.html）。故
 * **k=1.0：近距钉回 2733~3984（历史可接受 3600 附近）**；ratio 方向本来
 * 就对（ratio 大=近=该少给），错的是放大方向。
 *
 * 帽（第三轮，11-15 方案B）：k=1 验证成立后，近距过曝改由 P_f 逐帧接管，
 * 全局帽 5609 对远距只剩副作用（远距 P_f=29334~48411 被抽到 5609 =
 * 欠曝 2.4~3.1EV）。帽改 20810 = ISO250×83.24ms 快门包线：vc87 慢同步
 * 实验已实测该档暗场景 mean 100.3 / p50 102 追平 FIMO（113.2/105），当时
 * 判死唯一原因是近距手掌死白——那正是 TTL 接管的部分。半身人像 P_f
 * 11998~13557 也随之解禁（+1.0~1.1EV，主体受光随快门增长的风险由真机验）。
 *
 * 近距距离帽（第四轮，11-15 B2 真机后落值）：上一轮 `ratio ≥ 20 → P ≤ 4200`
 * 判据是错的——ratio 量的是「环境多亮」而非「主体多近」，关灯就掉 4~5 倍，
 * 20cm 手掌只有 12.5、2m 房间反而更高，真机 5 张一次没触发，而 20cm 手掌
 * （P_f=4116）依旧零纹理白板（取证 tmp/ttl_1114/b2_verdict.html）。
 *
 * 真实规律是**主体归一化曝光 P/d²**（ISO·ms·m²）：30cm/3107=34.5k、
 * 30cm/3767=41.9k、50cm/9568=38.3k 都落在 34.5k~41.9k 这条正确线上，唯独
 * 20cm/4116=102.9k 偏 2.4×——即 **P_f 在 30~50cm 是准的，极近距(<25cm) HAL
 * 的闪光测光开始失灵**（预闪帧被手掌占满、边缘溢出，AE 给的量反而偏大）。
 *
 * ⚠️ 范围限定（第四轮 D1 真机）：常数 40,000 是拿**手掌**（浅色高反射）标定的，
 * 一开始按 d<0.8m 全域介入，结果 37cm 自拍被砍到 5388 → 脸发黑（mean 25.7，
 * 同场景 B2 轮 14397 时是 61）。而 30cm 手掌在 k=1 直采 3107~3984 本来就正常
 * ⇒ 真正需要距离帽的只有 <25cm 这一档，故介入范围收为 d < 0.25m
 * （0.25m 处上限 2500、0.20m 处 1600，实测 22cm/2007 成像纹理完整）。
 *
 * 安全链：
 *  1. ratio < 2（预闪没明显照亮场景 / 采样失败）→ 回退既有 vc83+反馈行为；
 *  2. P ≤ ambientProduct × 1.2（亮场景不许比环境测光超 0.26EV，防补光过爆）；
 *  3. P ≤ 20810（ISO250×快门包线，远距用满；近距靠 P_f 直采自保护）；
 *  3b. d < 0.25m 时 P ≤ PRODUCT_PER_M2 × d²（极近距帽，d 无效则不介入）；
 *  4. ISO≤250 与 -0.7EV 锚不动，快门 ≤ 83.24ms、夹设备真实范围。
 *
 * 本对象刻意零 Android import，规则纯 Kotlin 可单测。
 */
object FlashTtl {

    /**
     * 主体曝光增益 k。第二轮校准（11-15）：预闪 AE 已算出正确量级，直接采用
     * （k=1.0，近距 2733~3984 落在 vc83 可用 3600 附近）；k=9.4 已真机判死。
     * 若近距仍偏白再落 0.8~0.9。
     */
    const val TTL_GAIN = 1.0f

    /**
     * 第三轮帽（11-15 方案B）：ISO250 × 83.24ms 快门包线 = 20810。近距过曝
     * 已由 P_f 直采逐帧接管，帽只管上限；vc87 实测该档远距 mean 100.3 追平
     * FIMO。与 Camera2Controller.FLASH_UNCLAMP_MAX_PRODUCT 同值。
     */
    const val MAX_PRODUCT = 20810f

    /**
     * 主体亮度常数（ISO·ms·m²）：正确成像实测带 34,500~41,900，取上沿 40,000
     * 作上限——即 `P ≤ 40000 × d²`。
     */
    const val PRODUCT_PER_M2 = 40000f

    /**
     * 距离帽介入范围：**只有 d < 0.25m 才夹**（极近距 HAL 闪光测光失灵的那一档）。
     * D1 真机教训：常数按手掌标定，如果扩到 0.8m 会把 37cm 自拍的人脸砍黑；
     * 而 30~59cm 的主体用 P_f 直采本来就是准的，不需要任何距离干预。
     */
    const val NEAR_RANGE_M = 0.25f

    /** 对焦距离可信下限（m）：更小视为超近/无效读数，不介入。 */
    const val MIN_VALID_DISTANCE_M = 0.05f

    /** 下限：极近距 P_f 很小时也别把快门压到闪不匀的程度。 */
    const val MIN_PRODUCT = 900f

    /** 亮场景护栏：P 最多比环境测光想要的量多 20%（+0.26EV）。 */
    const val AMBIENT_HEADROOM = 1.2f

    /** 有效性门限：预闪至少把场景测光推亮 2× 才算拿到了有效差分。 */
    const val MIN_RATIO = 2f

    /** TTL 生效时的快门上限（原厂实测最长常用档，机型无关写法见接线处）。 */
    const val MAX_SHUTTER_SEC = 0.08324f

    /** 一次求解的完整画像（供日志与单测断言）。 */
    data class Solution(
        val ambientProduct: Float,
        val flashProduct: Float,
        val ratio: Float,
        val targetProduct: Float
    )

    /**
     * 由两帧测光反解最终帧总曝光量（ISO·ms）。
     * @param subjectDistanceM 预闪帧 AF 读回的主体距离（m），null/无效则不介入
     * @return null = 差分无效（采样缺失或 ratio 不足），调用侧回退既有链路
     */
    fun solve(
        ambientIso: Int,
        ambientSec: Float,
        flashIso: Int,
        flashSec: Float,
        subjectDistanceM: Float? = null
    ): Solution? {
        if (ambientIso <= 0 || ambientSec <= 0f) return null
        if (flashIso <= 0 || flashSec <= 0f) return null
        val ambientProduct = ambientIso * ambientSec * 1000f
        val flashProduct = flashIso * flashSec * 1000f
        if (flashProduct <= 0f) return null
        val ratio = ambientProduct / flashProduct
        if (ratio < MIN_RATIO) return null
        var p = TTL_GAIN * flashProduct
        p = minOf(p, MAX_PRODUCT, ambientProduct * AMBIENT_HEADROOM)
        if (subjectDistanceM != null &&
            subjectDistanceM >= MIN_VALID_DISTANCE_M &&
            subjectDistanceM < NEAR_RANGE_M
        ) {
            p = minOf(p, PRODUCT_PER_M2 * subjectDistanceM * subjectDistanceM)
        }
        p = p.coerceAtLeast(MIN_PRODUCT)
        return Solution(ambientProduct, flashProduct, ratio, p)
    }
}
