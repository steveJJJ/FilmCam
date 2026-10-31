package com.filmcam.app.develop

/**
 * 活动 ↔ 服务共享冲洗进度（规格 §6.2 / §6.3）。
 *
 * 服务在工作线程里写字段；活动用主线程 Handler / [notifyListeners] 刷新 UI。
 * 所有字段都 @Volatile，避免指令重排导致活动读到半初始化值。
 */
object DevelopmentState {

    const val PHASE_PREPARING = "preparing"
    const val PHASE_DEVELOPING = "developing"
    const val PHASE_DONE = "done"
    const val PHASE_ERROR = "error"

    @Volatile var rollId: String? = null
    @Volatile var total: Int = 0
    @Volatile var done: Int = 0
    @Volatile var currentIndex: Int = 0
    @Volatile var phase: String = PHASE_DEVELOPING
    @Volatile var errorCount: Int = 0

    /**
     * 本次冲洗是否需要首次准备（拷贝引擎资源 + 建引擎，约 10 秒）。
     * 用于把「首次冲洗」和「后续冲洗」的准备文案区分开，避免用户以为卡死。
     */
    @Volatile var preparingFirstTime: Boolean = false

    /** 第一帧失败的错误消息（用于 UI/通知展示）；无错误时为 null。 */
    @Volatile var lastError: String? = null

    /** 主线程回调列表：服务每次更新进度后逐个 invoke。 */
    private val listeners = mutableListOf<() -> Unit>()

    /** listener 操作的锁；统一锁 listeners 本身，避免 @Synchronized（锁 this）与 synchronized(listeners) 锁不同对象。 */
    private val listenerLock = Any()

    fun reset() {
        rollId = null
        total = 0
        done = 0
        currentIndex = 0
        phase = PHASE_DEVELOPING
        errorCount = 0
        lastError = null
        preparingFirstTime = false
    }

    fun addListener(l: () -> Unit) {
        synchronized(listenerLock) { listeners.add(l) }
    }

    fun removeListener(l: () -> Unit) {
        synchronized(listenerLock) { listeners.remove(l) }
    }

    /** 由服务/活动在主线程调用；逐个回调。 */
    fun notifyListeners() {
        val snapshot: List<() -> Unit>
        synchronized(listenerLock) { snapshot = listeners.toList() }
        for (l in snapshot) {
            runCatching { l() }
        }
    }
}
