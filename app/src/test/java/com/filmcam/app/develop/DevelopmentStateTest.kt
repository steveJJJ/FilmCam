package com.filmcam.app.develop

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * 冲洗进度共享状态的契约测试。
 * 服务线程写、UI 主线程读，这里只覆盖纯状态与回调语义（不涉及线程调度）。
 */
class DevelopmentStateTest {

    @Before
    fun setUp() = DevelopmentState.reset()

    @After
    fun tearDown() = DevelopmentState.reset()

    @Test
    fun `reset 清空全部进度字段`() {
        DevelopmentState.rollId = "roll_1"
        DevelopmentState.total = 36
        DevelopmentState.done = 12
        DevelopmentState.currentIndex = 13
        DevelopmentState.phase = DevelopmentState.PHASE_ERROR
        DevelopmentState.errorCount = 2
        DevelopmentState.lastError = "解码失败"
        DevelopmentState.preparingFirstTime = true

        DevelopmentState.reset()

        assertNull(DevelopmentState.rollId)
        assertEquals(0, DevelopmentState.total)
        assertEquals(0, DevelopmentState.done)
        assertEquals(0, DevelopmentState.currentIndex)
        assertEquals(DevelopmentState.PHASE_DEVELOPING, DevelopmentState.phase)
        assertEquals(0, DevelopmentState.errorCount)
        assertNull(DevelopmentState.lastError)
        assertEquals(false, DevelopmentState.preparingFirstTime)
    }

    @Test
    fun `notifyListeners 逐个回调已注册监听`() {
        val a = AtomicInteger(0)
        val b = AtomicInteger(0)
        val la: () -> Unit = { a.incrementAndGet() }
        val lb: () -> Unit = { b.incrementAndGet() }
        DevelopmentState.addListener(la)
        DevelopmentState.addListener(lb)
        try {
            DevelopmentState.notifyListeners()
            assertEquals(1, a.get())
            assertEquals(1, b.get())
        } finally {
            DevelopmentState.removeListener(la)
            DevelopmentState.removeListener(lb)
        }
    }

    @Test
    fun `移除后的监听不再被回调`() {
        val n = AtomicInteger(0)
        val l: () -> Unit = { n.incrementAndGet() }
        DevelopmentState.addListener(l)
        DevelopmentState.removeListener(l)
        DevelopmentState.notifyListeners()
        assertEquals(0, n.get())
    }

    @Test
    fun `单个监听抛异常不影响后续监听`() {
        val ok = AtomicInteger(0)
        val bad: () -> Unit = { throw IllegalStateException("故意失败") }
        val good: () -> Unit = { ok.incrementAndGet() }
        DevelopmentState.addListener(bad)
        DevelopmentState.addListener(good)
        try {
            DevelopmentState.notifyListeners()
            assertEquals("异常监听应被隔离", 1, ok.get())
        } finally {
            DevelopmentState.removeListener(bad)
            DevelopmentState.removeListener(good)
        }
    }

    @Test
    fun `空监听列表调用安全`() {
        DevelopmentState.notifyListeners()
        assertTrue(true)
    }

    @Test
    fun `有实测样本后才给预估`() {
        DevelopmentState.total = 36
        DevelopmentState.done = 6
        // 一个样本都没有：不该凭空猜一个数出来
        assertEquals(-1, DevelopmentState.etaSeconds())
        // 第一帧完成：预估 = 单帧耗时 × 剩余帧数
        DevelopmentState.frameMsEma = 2000f
        assertEquals(60, DevelopmentState.etaSeconds())
    }

    @Test
    fun `第一帧还没出图时用上一卷的速度给预估`() {
        // 首帧要等引擎预热（十几秒），这段最容易让人以为卡死；
        // 拿上一卷的实测速度当种子，第 0 秒就能给「约剩多少」
        DevelopmentState.total = 36
        DevelopmentState.done = 0
        DevelopmentState.frameMsSeed = 3000f
        assertEquals(108, DevelopmentState.etaSeconds())
        // 首帧一完成，实测值直接顶掉种子（EMA 首帧取实测值，不再混旧速度）
        DevelopmentState.done = 1
        DevelopmentState.frameMsEma = 500f
        assertEquals(17, DevelopmentState.etaSeconds())
    }

    @Test
    fun `冲完就不再报预估`() {
        DevelopmentState.total = 36
        DevelopmentState.done = 36
        DevelopmentState.frameMsEma = 2000f
        DevelopmentState.frameMsSeed = 2000f
        assertEquals(-1, DevelopmentState.etaSeconds())
    }

    @Test
    fun `reset 清掉预估种子`() {
        DevelopmentState.frameMsSeed = 3000f
        DevelopmentState.frameMsEma = 900f
        DevelopmentState.reset()
        assertEquals(0f, DevelopmentState.frameMsSeed)
        assertEquals(0f, DevelopmentState.frameMsEma)
        assertEquals(-1, DevelopmentState.etaSeconds())
    }
}
