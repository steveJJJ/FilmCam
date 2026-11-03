package com.filmcam.app

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃兜底：把没接住的异常写进 `crash.txt`，然后交回系统默认处理。
 *
 * 这台机器上跑的东西有一半是 native（相机 HAL、Vulkan 胶片引擎、LibRaw 解码），
 * 崩了就是直接闪退——logcat 抓不到（无线调试经常断、也不是每次都开着），
 * 事后完全没有现场。所以这里在进程咽气前抢一步把堆栈落盘：
 * 放在**外部私有目录**（`Android/data/.../files`），release 包也能 `adb pull` 出来，
 * 不需要 root、不需要 run-as。
 *
 * 只做「记录」这一件事：写完照旧把异常交回系统默认处理器，
 * 该弹的「已停止运行」照弹、进程照常退出，不吞异常、不试图复活。
 */
object CrashLog {

    private const val TAG = "FilmCamCrash"
    private const val NAME = "crash.txt"
    /** 只留最近这么多字符：连着崩几次也不会把存储写爆（约 64KB，够放好几段完整堆栈） */
    private const val MAX_KEEP = 64 * 1024
    private const val PREF = "filmcam"
    private const val KEY_PENDING = "crash_pending"

    @Volatile
    private var defaultHandler: Thread.UncaughtExceptionHandler? = null

    /** 在 Application.onCreate 里调一次。重复调用只会把默认处理器套成链，无害。 */
    fun install(handler: Thread.UncaughtExceptionHandler?) {
        defaultHandler = handler
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { record(thread, throwable) }
                .onFailure { android.util.Log.w(TAG, "写崩溃日志失败", it) }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun record(thread: Thread, throwable: Throwable) {
        val text = buildString {
            append("time=").append(
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            ).append('\n')
            append("device=").append(Build.MANUFACTURER).append(' ')
                .append(Build.MODEL).append(" android=").append(Build.VERSION.RELEASE)
                .append(" api=").append(Build.VERSION.SDK_INT).append('\n')
            append("thread=").append(thread.name).append('\n')
            appendTrace(this, throwable)
        }
        android.util.Log.e(TAG, text)
        val file = fileOrNull() ?: return
        runCatching {
            file.parentFile?.mkdirs()
            val old = if (file.exists()) file.readText() else ""
            file.writeText((old + "\n" + text).takeLast(MAX_KEEP))
        }
        runCatching {
            appCtx?.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                ?.edit()?.putBoolean(KEY_PENDING, true)?.apply()
        }
    }

    /** 异常链整条打全：JNI/native 崩下来常常只包了一层，只打最外面那层等于没打。 */
    private fun appendTrace(sb: StringBuilder, t: Throwable) {
        val sw = java.io.StringWriter()
        java.io.PrintWriter(sw).use { t.printStackTrace(it) }
        sb.append(sw.toString())
        var cause = t.cause
        var depth = 0
        while (cause != null && depth++ < 5) {
            val cw = java.io.StringWriter()
            java.io.PrintWriter(cw).use { cause.printStackTrace(it) }
            sb.append("caused by:\n").append(cw.toString())
            cause = cause.cause
        }
    }

    @Volatile
    private var appCtx: Context? = null

    /** 由 [FilmCamApp] 注入（崩溃处理器自己拿不到 Context，只能提前存好）。 */
    fun attach(context: Context) {
        appCtx = context.applicationContext
    }

    private fun fileOrNull(): File? {
        val ctx = appCtx ?: return null
        val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        return File(dir, NAME)
    }

    fun file(context: Context): File {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        return File(dir, NAME)
    }

    /** 日志正文；没有就返回空串（给工程师控制台显示用）。 */
    fun text(context: Context): String =
        runCatching { file(context).readText().trim() }.getOrDefault("")

    /** 有没有过一次还没看过的崩溃（看过后由 [clear] 清掉标记）。 */
    fun hasPending(context: Context): Boolean =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_PENDING, false)

    fun clear(context: Context) {
        runCatching { file(context).delete() }
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().remove(KEY_PENDING).apply()
    }
}
