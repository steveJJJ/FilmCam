package com.filmcam.app.develop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch

/**
 * v63 诊断聚合器契约：只记录、值不变不置脏、报告可读。
 * 纯 JVM，不依赖 Android（ChromeSafeArea / GL 线程都会调它）。
 */
class DeviceGeometryReportTest {

    @Before
    fun reset() {
        DeviceGeometryReport.clear()
    }

    @Test
    fun `首次写入置脏 相同值重复写入不置脏`() {
        assertTrue(DeviceGeometryReport.put("device.model", "vivo X300 Pro"))
        assertFalse(DeviceGeometryReport.put("device.model", "vivo X300 Pro"))
        assertTrue(DeviceGeometryReport.put("device.model", "SM-S9380"))
        assertEquals(1, DeviceGeometryReport.fieldCount())
    }

    @Test
    fun `报告按 device window camera 分组且包含全部键值`() {
        DeviceGeometryReport.putAll(
            "window.widthDp" to 420,
            "device.model" to "X300 Pro",
            "camera.previewSize" to "1920x1080",
            "gl.geometryMismatch" to false,
        )
        val text = DeviceGeometryReport.render()
        assertTrue(text.contains("=== Relic Cam 跨机型几何诊断 ==="))
        assertTrue(text.contains("[device]"))
        assertTrue(text.contains("device.model = X300 Pro"))
        assertTrue(text.contains("[window]"))
        assertTrue(text.contains("camera.previewSize = 1920x1080"))
        assertTrue(text.contains("gl.geometryMismatch = false"))
        // 分组顺序固定，device 必须在 window 之前
        assertTrue(text.indexOf("[device]") < text.indexOf("[window]"))
        assertTrue(text.indexOf("[window]") < text.indexOf("[camera]"))
        assertTrue(text.endsWith("字段数=4"))
    }

    @Test
    fun `snapshotIfDirty 只在有变化时返回全文`() {
        DeviceGeometryReport.put("device.sdk", 36)
        assertNotNull(DeviceGeometryReport.snapshotIfDirty())
        assertNull(DeviceGeometryReport.snapshotIfDirty())
        DeviceGeometryReport.put("device.sdk", 36)
        assertNull(DeviceGeometryReport.snapshotIfDirty())
        DeviceGeometryReport.put("device.sdk", 35)
        assertNotNull(DeviceGeometryReport.snapshotIfDirty())
    }

    @Test
    fun `值里的换行被压平 空值记成问号 不破坏报告行结构`() {
        DeviceGeometryReport.put("content.rect", "RectF(0, 10,\n100, 200)")
        assertFalse(DeviceGeometryReport.render().contains("\n100"))
        assertTrue(DeviceGeometryReport.render().contains("content.rect = RectF(0, 10, 100, 200)"))
        DeviceGeometryReport.put("camera.previewCandidates", null)
        assertTrue(DeviceGeometryReport.render().contains("camera.previewCandidates = ?"))
    }

    @Test
    fun `clear 后字段数归零但标题仍在`() {
        DeviceGeometryReport.put("device.model", "X")
        DeviceGeometryReport.clear()
        assertEquals(0, DeviceGeometryReport.fieldCount())
        assertTrue(DeviceGeometryReport.render().startsWith("=== Relic Cam"))
    }

    @Test
    fun `GL 线程与 UI 线程并发写入不抛异常`() {
        val n = 8
        val perThread = 50
        val latch = CountDownLatch(n)
        val threads = (0 until n).map { t ->
            Thread {
                repeat(perThread) { i ->
                    DeviceGeometryReport.put("gl.thread$t", i)
                    DeviceGeometryReport.render()
                }
                latch.countDown()
            }
        }
        threads.forEach { it.start() }
        latch.await()
        threads.forEach { it.join() }
        assertEquals(n, DeviceGeometryReport.fieldCount())
    }

    @Test
    fun `矩阵首次写入置脏 相同矩阵不重复写入`() {
        val m = identityMatrix()
        assertTrue(DeviceGeometryReport.putMatrix("gl.texMatrix", m))
        assertFalse(DeviceGeometryReport.putMatrix("gl.texMatrix", m.copyOf()))
    }

    @Test
    fun `冷启动单位阵就绪后变旋转阵会被捕获`() {
        assertTrue(DeviceGeometryReport.putMatrix("gl.texMatrix", identityMatrix()))
        assertTrue(DeviceGeometryReport.render().contains("1.000,0.000,0.000,0.000"))
        val rotated = FloatArray(16).apply {
            this[1] = -1f; this[4] = -1f; this[10] = 1f
            this[12] = 1f; this[13] = 1f; this[15] = 1f
        }
        assertTrue(DeviceGeometryReport.putMatrix("gl.texMatrix", rotated))
        assertTrue(DeviceGeometryReport.render().contains("0.000,-1.000,0.000,0.000"))
    }

    @Test
    fun `小于显示精度的抖动不置脏 报告全文不变`() {
        DeviceGeometryReport.putMatrix("gl.texMatrix", identityMatrix())
        val before = DeviceGeometryReport.render()
        val jitter = identityMatrix().also { it[0] = 1.0002f }
        assertFalse(DeviceGeometryReport.putMatrix("gl.texMatrix", jitter))
        assertEquals(before, DeviceGeometryReport.render())
    }

    @Test
    fun `clear 后矩阵缓存一并清空 可重新记录同一矩阵`() {
        val m = identityMatrix()
        DeviceGeometryReport.putMatrix("gl.texMatrix", m)
        DeviceGeometryReport.clear()
        assertTrue(DeviceGeometryReport.putMatrix("gl.texMatrix", m))
    }

    @Test
    fun `矩阵按三位小数格式化且不破坏报告行结构`() {
        val m = FloatArray(16).also { it[0] = 0.123456f }
        DeviceGeometryReport.putMatrix("gl.texMatrix", m)
        assertTrue(DeviceGeometryReport.render().contains("gl.texMatrix = 0.123,"))
        assertFalse(DeviceGeometryReport.render().contains("\n0.000"))
    }

    private fun identityMatrix(): FloatArray =
        FloatArray(16) { i -> if (i % 5 == 0) 1f else 0f }
}
