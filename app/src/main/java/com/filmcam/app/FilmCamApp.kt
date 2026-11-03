package com.filmcam.app

import android.app.Application
import android.util.Log

/**
 * 应用入口。目前只干一件事：装崩溃兜底（见 [CrashLog]）。
 *
 * 之所以要有这个类：相机 HAL、Vulkan 胶片引擎、LibRaw 解码都在 native 层，
 * 崩下来是整进程闪退，而 logcat 经常抓不到（无线调试会断，也不可能一直开着）。
 * 有了 Application 才能在进程咽气前抢一步把堆栈落盘。
 */
class FilmCamApp : Application() {

    override fun onCreate() {
        super.onCreate()
        CrashLog.attach(this)
        CrashLog.install(Thread.getDefaultUncaughtExceptionHandler())
        Log.i("FilmCam", "Application 就绪，崩溃日志: " + CrashLog.file(this).absolutePath)
    }
}
