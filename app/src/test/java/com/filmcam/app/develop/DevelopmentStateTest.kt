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
}
