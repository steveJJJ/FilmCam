package com.filmcam.app

import android.app.Application
import android.util.Log

/**
 * 应用入口。两件事：装崩溃兜底（见 [CrashLog]）+ 进程级初始化全局单例。
 *
 * 之所以要有这个类：相机 HAL、Vulkan 胶片引擎、LibRaw 解码都在 native 层，
 * 崩下来是整进程闪退，而 logcat 经常抓不到（无线调试会断，也不可能一直开着）。
 * 有了 Application 才能在进程咽气前抢一步把堆栈落盘。
 *
 * UndevelopedFilmStore.init 必须在这里而不是 MainActivity：进程在后台被杀后，
 * 系统重建任务是直接 resume 栈顶 Activity（比如暗房页），不经过 MainActivity，
 * 那时 store 没初始化，onResume 一碰就「尚未调用」崩个循环（真机 23:54/00:24 实锤）。
 */
class FilmCamApp : Application() {

    override fun onCreate() {
        super.onCreate()
        CrashLog.attach(this)
        CrashLog.install(Thread.getDefaultUncaughtExceptionHandler())
        com.filmcam.app.film.UndevelopedFilmStore.init(this)
        // v55：竖屏倒过来时保持正竖（不让机身铭牌跑进物理顶部的状态栏 / 挖孔预留区），
        // 但保留两侧横屏 —— 相机主拍摄方向与商店首图都是横屏。
        com.filmcam.app.ui.ReversePortraitGuard.install(this)
        Log.i("FilmCam", "Application 就绪，崩溃日志: " + CrashLog.file(this).absolutePath)
    }
}
