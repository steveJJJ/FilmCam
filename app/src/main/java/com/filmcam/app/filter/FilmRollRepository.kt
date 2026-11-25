package com.filmcam.app.filter

import android.content.Context
import com.filmcam.app.R
import com.filmcam.app.ui.AppLocale

/**
 * 胶卷库。
 *
 * 支持用户自定义排序：胶卷列表里长按拖拽调整顺序后，把顺序持久化到
 * SharedPreferences（只存 name 序列），下次打开就按用户的顺序展示——
 * 于是常用的胶卷可以拖到最上面方便切换。
 *
 * 双语：每支胶卷带中英文的 description / tag，对外入口 [rolls] 按
 * AppLocale.isEnglish 投影成当前语言，展示层（Adapter）无需感知。
 */
object FilmRollRepository {

    private const val PREFS = "filmcam"
    private const val KEY_ORDER = "film_order"
    /** 分隔符：胶卷名不含控制字符，用它拼接最安全 */
    private const val SEP = "\u0001"

    /** 引擎 catalog 全量胶卷（与 FilmStockMapper.ROLL_TABLE 一一对应），出厂默认顺序 */
    private val defaultRolls = listOf(
        FilmRoll(
            name = "PORTRA 400",
            iconResId = R.drawable.ic_film_portra400,
            description = "暖调人像，肤色细腻",
            tag = "彩色",
            descriptionEn = "Warm portrait tones, smooth skin",
            tagEn = "Color",
            scene = "人像·婚礼",
            sceneEn = "Portrait·Wedding",
            descriptionTw = "暖調人像，膚色細膩",
            tagTw = "彩色",
            sceneTw = "人像·婚禮",
            descriptionJa = "暖色調のポートレート、肌色が繊細",
            tagJa = "カラー",
            sceneJa = "ポートレート・ウェディング",
            descriptionKo = "따뜻한 인물 사진, 피부톤이 부드럽습니다",
            tagKo = "컬러",
            sceneKo = "인물·웨딩"
        ),
        FilmRoll(
            name = "EKTAR 100",
            iconResId = R.drawable.ic_film_ektar100,
            description = "高饱和，鲜艳明快",
            tag = "彩色",
            descriptionEn = "High saturation, vivid and punchy",
            tagEn = "Color",
            scene = "风光·建筑",
            sceneEn = "Landscape·Arch",
            descriptionTw = "高飽和，鮮艷明快",
            tagTw = "彩色",
            sceneTw = "風光·建築",
            descriptionJa = "高彩度で鮮やかで明るい",
            tagJa = "カラー",
            sceneJa = "風景・建造物",
            descriptionKo = "채도가 높고 선명하며 밝습니다",
            tagKo = "컬러",
            sceneKo = "풍경·건축"
        ),
        FilmRoll(
            name = "GOLD 200",
            iconResId = R.drawable.ic_film_gold200,
            description = "暖黄复古，日常扫街",
            tag = "彩色",
            descriptionEn = "Warm amber retro, everyday street",
            tagEn = "Color",
            scene = "日常·晴天",
            sceneEn = "Daily·Sunny",
            descriptionTw = "暖黃復古，日常掃街",
            tagTw = "彩色",
            sceneTw = "日常·晴天",
            descriptionJa = "暖かい黄色のレトロ調、日常のスナップ",
            tagJa = "カラー",
            sceneJa = "日常・晴天",
            descriptionKo = "따뜻한 노란 빈티지 톤, 일상 거리 촬영에 적합",
            tagKo = "컬러",
            sceneKo = "일상·맑은날"
        ),
        FilmRoll(
            name = "SUPERIA 400",
            iconResId = R.drawable.ic_film_superia400,
            description = "富士清新，通透自然",
            tag = "彩色",
            descriptionEn = "Fuji fresh, clean and natural",
            tagEn = "Color",
            scene = "日常·街拍",
            sceneEn = "Daily·Street",
            descriptionTw = "富士清新，通透自然",
            tagTw = "彩色",
            sceneTw = "日常·街拍",
            descriptionJa = "富士のさわやかさ、クリアで自然",
            tagJa = "カラー",
            sceneJa = "日常・ストリートスナップ",
            descriptionKo = "Fuji 특유의 산뜻하고 투명한 자연스러움",
            tagKo = "컬러",
            sceneKo = "일상·거리사진"
        ),
        FilmRoll(
            name = "KODACHROME 64",
            iconResId = R.drawable.ic_film_kodachrome64,
            description = "柯达经典反转片，浓郁暖红调",
            tag = "反转",
            descriptionEn = "Kodak classic slide, deep warm reds",
            tagEn = "Slide",
            scene = "纪实·旅行",
            sceneEn = "Doc·Travel",
            descriptionTw = "柯達經典反轉片，濃郁暖紅調",
            tagTw = "反轉",
            sceneTw = "紀實·旅行",
            descriptionJa = "コダックのクラシックなリバーサル、濃厚な暖かい赤み",
            tagJa = "リバーサル",
            sceneJa = "ドキュメンタリー・旅行",
            descriptionKo = "Kodak의 클래식 슬라이드 필름, 진한 따뜻한 붉은 톤",
            tagKo = "슬라이드",
            sceneKo = "다큐·여행"
        ),
        FilmRoll(
            name = "VISION3 250D",
            iconResId = R.drawable.ic_film_vision3_250d,
            description = "柯达电影卷，日光平衡柔和",
            tag = "电影",
            descriptionEn = "Kodak cine stock, soft daylight balance",
            tagEn = "Cine",
            scene = "日光·广告",
            sceneEn = "Daylight·Editorial",
            descriptionTw = "柯達電影卷，日光平衡柔和",
            tagTw = "電影",
            sceneTw = "日光·廣告",
            descriptionJa = "コダックのシネマフィルム、日光バランスでやわらかい",
            tagJa = "シネマ",
            sceneJa = "日光・広告",
            descriptionKo = "Kodak 시네마 필름, 일광 밸런스의 부드러운 색감",
            tagKo = "시네마",
            sceneKo = "일광·광고"
        ),
        FilmRoll(
            name = "TRI-X 400",
            iconResId = R.drawable.ic_film_trix400,
            description = "黑白胶片，颗粒浓郁",
            tag = "黑白",
            descriptionEn = "Classic B&W, heavy grain",
            tagEn = "B&W",
            scene = "街拍·纪实",
            sceneEn = "Street·Doc",
            descriptionTw = "黑白膠片，顆粒濃郁",
            tagTw = "黑白",
            sceneTw = "街拍·紀實",
            descriptionJa = "モノクロフィルム、豊かな粒状感",
            tagJa = "モノクロ",
            sceneJa = "ストリートスナップ・ドキュメンタリー",
            descriptionKo = "흑백 필름, 그레인이 진하게 표현됩니다",
            tagKo = "흑백",
            sceneKo = "거리사진·다큐"
        ),
        FilmRoll(
            name = "HP5 PLUS",
            iconResId = R.drawable.ic_film_hp5,
            description = "黑白高对比，硬朗",
            tag = "黑白",
            descriptionEn = "High-contrast B&W, hard edged",
            tagEn = "B&W",
            scene = "人像·通用",
            sceneEn = "Portrait·Allround",
            descriptionTw = "黑白高對比，硬朗",
            tagTw = "黑白",
            sceneTw = "人像·通用",
            descriptionJa = "モノクロの高コントラスト、力強い",
            tagJa = "モノクロ",
            sceneJa = "ポートレート・汎用",
            descriptionKo = "흑백 고대비, 강렬하고 선명한 느낌",
            tagKo = "흑백",
            sceneKo = "인물·범용"
        ),
        FilmRoll(
            name = "CINESTILL 800T",
            iconResId = R.drawable.ic_film_cinestill800t,
            description = "钨丝灯平衡，夜色霓虹",
            tag = "彩色",
            descriptionEn = "Tungsten balanced, night neon",
            tagEn = "Color",
            scene = "夜景·霓虹",
            sceneEn = "Night·Neon",
            descriptionTw = "鎢絲燈平衡，夜色霓虹",
            tagTw = "彩色",
            sceneTw = "夜景·霓虹",
            descriptionJa = "タングステン光バランス、夜のネオン",
            tagJa = "カラー",
            sceneJa = "夜景・ネオン",
            descriptionKo = "텅스텐 밸런스, 밤의 네온을 담기에 적합",
            tagKo = "컬러",
            sceneKo = "야경·네온"
        ),
        // ---- 引擎 catalog 全量胶卷（与 FilmStockMapper.ROLL_TABLE 一一对应）----
        FilmRoll(
            name = "PORTRA 160",
            iconResId = R.drawable.ic_film_portra160,
            description = "柯达人像，柔润低反差",
            tag = "彩色",
            descriptionEn = "Kodak portrait, soft low contrast",
            tagEn = "Color",
            scene = "棚拍·人像",
            sceneEn = "Studio·Portrait",
            descriptionTw = "柯達人像，柔潤低反差",
            tagTw = "彩色",
            sceneTw = "棚拍·人像",
            descriptionJa = "コダックのポートレート、やわらかく低コントラスト",
            tagJa = "カラー",
            sceneJa = "スタジオ撮影・ポートレート",
            descriptionKo = "Kodak 인물용 필름, 부드럽고 낮은 대비",
            tagKo = "컬러",
            sceneKo = "스튜디오·인물"
        ),
        FilmRoll(
            name = "PORTRA 800",
            iconResId = R.drawable.ic_film_portra800,
            description = "柯达高感人像，弱光可用",
            tag = "彩色",
            descriptionEn = "Kodak fast portrait, holds up in low light",
            tagEn = "Color",
            scene = "弱光·活动",
            sceneEn = "LowLight·Event",
            descriptionTw = "柯達高感人像，弱光可用",
            tagTw = "彩色",
            sceneTw = "弱光·活動",
            descriptionJa = "コダックの高感度ポートレート、暗い場所でも使える",
            tagJa = "カラー",
            sceneJa = "弱光・イベント",
            descriptionKo = "Kodak 고감도 인물 필름, 저조도에서도 사용 가능",
            tagKo = "컬러",
            sceneKo = "저조도·이벤트"
        ),
        FilmRoll(
            name = "PORTRA 800 PUSH 1",
            iconResId = R.drawable.ic_film_portra800p1,
            description = "波特拉 800 增感 1 档",
            tag = "彩色",
            descriptionEn = "Portra 800 pushed 1 stop",
            tagEn = "Color",
            scene = "弱光·手持",
            sceneEn = "LowLight·Handheld",
            descriptionTw = "PORTRA 800 增感 1 檔",
            tagTw = "彩色",
            sceneTw = "弱光·手持",
            descriptionJa = "ポルトラ 800 を 1 段増感",
            tagJa = "カラー",
            sceneJa = "弱光・手持ち撮影",
            descriptionKo = "PORTRA 800 1스톱 푸시 현상",
            tagKo = "컬러",
            sceneKo = "저조도·핸드헬드"
        ),
        FilmRoll(
            name = "PORTRA 800 PUSH 2",
            iconResId = R.drawable.ic_film_portra800p2,
            description = "波特拉 800 增感 2 档",
            tag = "彩色",
            descriptionEn = "Portra 800 pushed 2 stops",
            tagEn = "Color",
            scene = "极暗·高感",
            sceneEn = "VeryDark·HighISO",
            descriptionTw = "PORTRA 800 增感 2 檔",
            tagTw = "彩色",
            sceneTw = "極暗·高感",
            descriptionJa = "ポルトラ 800 を 2 段増感",
            tagJa = "カラー",
            sceneJa = "極暗所・高感度",
            descriptionKo = "PORTRA 800 2스톱 푸시 현상",
            tagKo = "컬러",
            sceneKo = "매우 어두운 곳·고감도"
        ),
        FilmRoll(
            name = "ULTRAMAX 400",
            iconResId = R.drawable.ic_film_ultramax400,
            description = "柯达日常卷，活泼色彩",
            tag = "彩色",
            descriptionEn = "Kodak everyday stock, lively color",
            tagEn = "Color",
            scene = "日常·聚会",
            sceneEn = "Daily·Party",
            descriptionTw = "柯達日常卷，活潑色彩",
            tagTw = "彩色",
            sceneTw = "日常·聚會",
            descriptionJa = "コダックの日常用フィルム、生き生きした色",
            tagJa = "カラー",
            sceneJa = "日常・パーティー",
            descriptionKo = "Kodak 일상용 필름, 생동감 있는 색감",
            tagKo = "컬러",
            sceneKo = "일상·모임"
        ),
        FilmRoll(
            name = "C200",
            iconResId = R.drawable.ic_film_c200,
            description = "富士日光卷，清新自然",
            tag = "彩色",
            descriptionEn = "Fuji daylight stock, fresh and natural",
            tagEn = "Color",
            scene = "日光·人像",
            sceneEn = "Daylight·Portrait",
            descriptionTw = "富士日光卷，清新自然",
            tagTw = "彩色",
            sceneTw = "日光·人像",
            descriptionJa = "富士の日光用フィルム、さわやかで自然",
            tagJa = "カラー",
            sceneJa = "日光・ポートレート",
            descriptionKo = "Fuji 일광용 필름, 산뜻하고 자연스러움",
            tagKo = "컬러",
            sceneKo = "일광·인물"
        ),
        FilmRoll(
            name = "PRO 400H",
            iconResId = R.drawable.ic_film_pro400h,
            description = "富士人像，柔和粉调",
            tag = "彩色",
            descriptionEn = "Fuji portrait, soft pastel tones",
            tagEn = "Color",
            scene = "婚礼·人像",
            sceneEn = "Wedding·Portrait",
            descriptionTw = "富士人像，柔和粉調",
            tagTw = "彩色",
            sceneTw = "婚禮·人像",
            descriptionJa = "富士のポートレート、やわらかいピンク調",
            tagJa = "カラー",
            sceneJa = "ウェディング・ポートレート",
            descriptionKo = "Fuji 인물용 필름, 부드러운 핑크 톤",
            tagKo = "컬러",
            sceneKo = "웨딩·인물"
        ),
        FilmRoll(
            name = "T-MAX 100",
            iconResId = R.drawable.ic_film_tmax100,
            description = "柯达黑白，细腻锐利",
            tag = "黑白",
            descriptionEn = "Kodak B&W, fine grained and sharp",
            tagEn = "B&W",
            scene = "静物·建筑",
            sceneEn = "Still·Arch",
            descriptionTw = "柯達黑白，細膩銳利",
            tagTw = "黑白",
            sceneTw = "靜物·建築",
            descriptionJa = "コダックのモノクロ、繊細でシャープ",
            tagJa = "モノクロ",
            sceneJa = "静物・建造物",
            descriptionKo = "Kodak 흑백 필름, 섬세하고 날카로움",
            tagKo = "흑백",
            sceneKo = "정물·건축"
        ),
        FilmRoll(
            name = "NEOPAN ACROS 100 II",
            iconResId = R.drawable.ic_film_acros100,
            description = "富士黑白，超细颗粒",
            tag = "黑白",
            descriptionEn = "Fuji B&W, ultra-fine grain",
            tagEn = "B&W",
            scene = "黑白·长曝",
            sceneEn = "B&W·LongExp",
            descriptionTw = "富士黑白，超細顆粒",
            tagTw = "黑白",
            sceneTw = "黑白·長曝",
            descriptionJa = "富士のモノクロ、超微細な粒子",
            tagJa = "モノクロ",
            sceneJa = "モノクロ・長時間露光",
            descriptionKo = "Fuji 흑백 필름, 초미세 그레인",
            tagKo = "흑백",
            sceneKo = "흑백·장노출"
        ),
        FilmRoll(
            name = "PROVIA 100F",
            iconResId = R.drawable.ic_film_provia100f,
            description = "富士反转片，纯正色彩",
            tag = "反转",
            descriptionEn = "Fuji slide, faithful color",
            tagEn = "Slide",
            scene = "产品·建筑",
            sceneEn = "Product·Arch",
            descriptionTw = "富士反轉片，純正色彩",
            tagTw = "反轉",
            sceneTw = "產品·建築",
            descriptionJa = "富士のリバーサル、純正な色",
            tagJa = "リバーサル",
            sceneJa = "商品撮影・建造物",
            descriptionKo = "Fuji 슬라이드 필름, 순수하고 정확한 색감",
            tagKo = "슬라이드",
            sceneKo = "제품·건축"
        ),
        FilmRoll(
            name = "VELVIA 100",
            iconResId = R.drawable.ic_film_velvia100,
            description = "富士反转片，浓郁风光",
            tag = "反转",
            descriptionEn = "Fuji slide, saturated landscapes",
            tagEn = "Slide",
            scene = "风光·自然",
            sceneEn = "Landscape·Nature",
            descriptionTw = "富士反轉片，濃郁風光",
            tagTw = "反轉",
            sceneTw = "風光·自然",
            descriptionJa = "富士のリバーサル、濃厚な風景",
            tagJa = "リバーサル",
            sceneJa = "風景・自然",
            descriptionKo = "Fuji 슬라이드 필름, 풍경의 색감이 진하게 표현됩니다",
            tagKo = "슬라이드",
            sceneKo = "풍경·자연"
        ),
        FilmRoll(
            name = "EKTACHROME 100",
            iconResId = R.drawable.ic_film_ektachrome100,
            description = "柯达反转片，明亮通透",
            tag = "反转",
            descriptionEn = "Kodak slide, bright and clean",
            tagEn = "Slide",
            scene = "风光·时尚",
            sceneEn = "Landscape·Fashion",
            descriptionTw = "柯達反轉片，明亮通透",
            tagTw = "反轉",
            sceneTw = "風光·時尚",
            descriptionJa = "コダックのリバーサル、明るくクリア",
            tagJa = "リバーサル",
            sceneJa = "風景・ファッション",
            descriptionKo = "Kodak 슬라이드 필름, 밝고 투명한 색감",
            tagKo = "슬라이드",
            sceneKo = "풍경·패션"
        ),
        FilmRoll(
            name = "VERITA 200D",
            iconResId = R.drawable.ic_film_verita200d,
            description = "柯达电影分装，复古色调",
            tag = "电影",
            descriptionEn = "Kodak cine respool, vintage tones",
            tagEn = "Cine",
            scene = "复古·街拍",
            sceneEn = "Retro·Street",
            descriptionTw = "柯達電影分裝，復古色調",
            tagTw = "電影",
            sceneTw = "復古·街拍",
            descriptionJa = "コダックのシネマフィルム（分装）、レトロな色調",
            tagJa = "シネマ",
            sceneJa = "レトロ・ストリートスナップ",
            descriptionKo = "Kodak 시네마 필름 소분, 빈티지한 색조",
            tagKo = "시네마",
            sceneKo = "빈티지·거리사진"
        ),
        FilmRoll(
            name = "VISION3 50D",
            iconResId = R.drawable.ic_film_vision3_50d,
            description = "柯达电影，日光下最细腻",
            tag = "电影",
            descriptionEn = "Kodak cine, finest grain in daylight",
            tagEn = "Cine",
            scene = "日光·棚拍",
            sceneEn = "Daylight·Studio",
            descriptionTw = "柯達電影，日光下最細膩",
            tagTw = "電影",
            sceneTw = "日光·棚拍",
            descriptionJa = "コダックのシネマ、日光下で最も繊細",
            tagJa = "シネマ",
            sceneJa = "日光・スタジオ撮影",
            descriptionKo = "Kodak 시네마 필름, 일광에서 가장 섬세함",
            tagKo = "시네마",
            sceneKo = "일광·스튜디오"
        ),
        FilmRoll(
            name = "VISION3 200T",
            iconResId = R.drawable.ic_film_vision3_200t,
            description = "柯达电影，灯光色温平衡",
            tag = "电影",
            descriptionEn = "Kodak cine, balanced for tungsten",
            tagEn = "Cine",
            scene = "室内·人像",
            sceneEn = "Indoor·Portrait",
            descriptionTw = "柯達電影，燈光色溫平衡",
            tagTw = "電影",
            sceneTw = "室內·人像",
            descriptionJa = "コダックのシネマ、タングステン光の色温度バランス",
            tagJa = "シネマ",
            sceneJa = "室内・ポートレート",
            descriptionKo = "Kodak 시네마 필름, 조명 색온도에 맞춰진 밸런스",
            tagKo = "시네마",
            sceneKo = "실내·인물"
        ),
        FilmRoll(
            name = "VISION3 500T",
            iconResId = R.drawable.ic_film_vision3_500t,
            description = "柯达电影，夜景利器",
            tag = "电影",
            descriptionEn = "Kodak cine, made for night",
            tagEn = "Cine",
            scene = "夜景·婚礼",
            sceneEn = "Night·Wedding",
            descriptionTw = "柯達電影，夜景利器",
            tagTw = "電影",
            sceneTw = "夜景·婚禮",
            descriptionJa = "コダックのシネマ、夜景に最適",
            tagJa = "シネマ",
            sceneJa = "夜景・ウェディング",
            descriptionKo = "Kodak 시네마 필름, 야경 촬영에 강점",
            tagKo = "시네마",
            sceneKo = "야경·웨딩"
        )
    )

    /** 出厂定义按 name 索引：投影时用它取回双语原值，切语言来回切都不会丢 */
    private val byName = defaultRolls.associateBy { it.name }

    /** 权威顺序（中文原值）。用户拖拽的顺序体现在 [displayCache] 上，落盘只读 name 序列。 */
    private var ordered: List<FilmRoll> = defaultRolls

    /** 当前语言的展示列表：实例在同一语言下保持稳定，Adapter 就地在它上面调顺序。 */
    private var displayCache: MutableList<FilmRoll>? = null
    private var displayLang: Int = AppLocale.contentMode()

    /**
     * 当前展示顺序（用户可拖拽调整），已按当前语言渲染 description/tag。
     * 所有读取方请直接用这个属性。
     *
     * 语言切换（AppLocale.isEnglish 变化）时按缓存里现有的 name 顺序重建，
     * 拖拽出来的顺序不会丢。
     */
    var rolls: MutableList<FilmRoll>
        get() {
            val lang = AppLocale.contentMode()
            val cache = displayCache
            if (cache != null && displayLang == lang) return cache
            val src = cache ?: ordered
            val out = ArrayList<FilmRoll>(src.size)
            src.mapTo(out) { (byName[it.name] ?: it).localized(lang) }
            displayCache = out
            displayLang = lang
            return out
        }
        private set(value) {
            ordered = value
            displayCache = null
        }

    /**
     * 从 SharedPreferences 恢复用户的排序（应在 Activity 初始化、restoreSettings 之前调用）。
     * 容错：存档里没有的新胶卷（比如升级后新增的）追加到末尾，避免它们在列表里消失。
     */
    fun restore(context: Context) {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ORDER, null)
        if (saved.isNullOrBlank()) {
            rolls = defaultRolls.toMutableList()
            return
        }
        val names = saved.split(SEP)
        val nameSet = names.toHashSet()
        val defByName = defaultRolls.associateBy { it.name }
        val orderedList = ArrayList<FilmRoll>(defaultRolls.size)
        names.mapNotNullTo(orderedList) { defByName[it] }
        defaultRolls.filterNot { it.name in nameSet }.forEach { orderedList.add(it) }
        rolls = orderedList
    }

    /** 落盘当前顺序（拖拽松手时调用一次）。 */
    fun persist(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ORDER, rolls.joinToString(SEP) { it.name })
            .apply()
    }

    /** 恢复出厂默认顺序。 */
    fun reset(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_ORDER).apply()
        rolls = defaultRolls.toMutableList()
    }
}
