package app.spektralab.engine

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * 把 assets/resources/（约 30MB：profiles/、vulkan/、*.bin、*.json）一次性拷贝到
 * context.filesDir/resources。用 versionCode 写 .bundle marker 做缓存标记，
 * versionCode 不变则跳过拷贝。
 */
object EngineResources {

    private const val TAG = "EngineResources"
    private const val ASSET_ROOT = "resources"
    private const val MARKER_FILE = ".bundle"
    private const val BUFFER_SIZE = 64 * 1024

    @Volatile
    private var spkNativeLibsLoaded = false

    /**
     * 预加载 spk 引擎的版本化动态库依赖。
     * libjpeg.so.8 / libz.so.1 无法以带版本号文件名进 APK（AGP 只打包 *.so），
     * 故以 libjpeg.so / libz.so 打包，这里先 loadLibrary 注册（SONAME 仍为版本化名），
     * 使 engine.so 的 NEEDED "libjpeg.so.8" / "libz.so.1" 按 SONAME 命中。
     *
     * 必须在任何 SpektraLabEngine 引用（其 static 块会 loadLibrary("spektrafilm_engine")）
     * 之前调用。
     */
    fun ensureNativeLibs(context: Context) {
        if (spkNativeLibsLoaded) return
        try {
            System.loadLibrary("jpeg")   // 文件 libjpeg.so，SONAME libjpeg.so.8
            System.loadLibrary("z")      // 文件 libz.so，SONAME libz.so.1
            spkNativeLibsLoaded = true
        } catch (e: Throwable) {
            Log.e(TAG, "spk 动态库预加载失败", e)
            throw IllegalStateException(
                "预加载 spk 动态库失败: ${e.message}（确认 APK lib/arm64-v8a/ 内含 libjpeg.so 与 libz.so）",
                e
            )
        }
    }

    /**
     * 确保引擎资源就绪，返回 resourcesDir 的绝对路径。
     * 缓存命中（marker 存在且版本号一致）则直接返回；否则递归全量拷贝。
     */
    fun ensureLoaded(context: Context): String {
        val appContext = context.applicationContext
        val destDir = File(appContext.filesDir, ASSET_ROOT)
        val versionCode = try {
            appContext.packageManager
                .getPackageInfo(appContext.packageName, 0).versionCode
        } catch (e: Exception) {
            throw RuntimeException("无法读取 versionCode: ${e.message}", e)
        }

        val marker = File(destDir, MARKER_FILE)
        if (marker.isFile && marker.readText().trim() == versionCode.toString()) {
            return destDir.absolutePath
        }

        // 缓存失效或首次：清空目标目录后重新递归拷贝。
        if (destDir.exists()) {
            destDir.deleteRecursively()
        }
        if (!destDir.mkdirs() && !destDir.isDirectory) {
            throw RuntimeException("无法创建资源目录: ${destDir.absolutePath}")
        }

        try {
            copyAssetDir(appContext, ASSET_ROOT, destDir)
        } catch (e: Exception) {
            // 拷贝失败：清理半成品，避免下次误判缓存命中。
            destDir.deleteRecursively()
            throw RuntimeException("拷贝 SpektraLab 引擎资源失败: ${e.message}", e)
        }

        marker.writeText(versionCode.toString())
        return destDir.absolutePath
    }

    /** 递归拷贝 assets/[assetPath] 整个目录树到 destDir（destDir 已存在）。 */
    private fun copyAssetDir(context: Context, assetPath: String, destDir: File) {
        val children = context.assets.list(assetPath)
        if (children == null || children.isEmpty()) {
            // assetPath 本身是文件（list 返回空数组通常表示文件而非目录）。
            copyAssetFile(context, assetPath, File(destDir, File(assetPath).name))
            return
        }
        for (name in children) {
            val childAssetPath = if (assetPath.isEmpty()) name else "$assetPath/$name"
            // 用 list() 再探一次：若仍有子项则为目录。
            val sub = context.assets.list(childAssetPath)
            if (sub != null && sub.isNotEmpty()) {
                val childDir = File(destDir, name)
                if (!childDir.exists() && !childDir.mkdirs()) {
                    throw RuntimeException("无法创建子目录: ${childDir.absolutePath}")
                }
                copyAssetDir(context, childAssetPath, childDir)
            } else {
                copyAssetFile(context, childAssetPath, File(destDir, name))
            }
        }
    }

    private fun copyAssetFile(context: Context, assetPath: String, destFile: File) {
        destFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
        context.assets.open(assetPath).use { input ->
            FileOutputStream(destFile).use { out ->
                val buf = ByteArray(BUFFER_SIZE)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
                out.fd.sync()
            }
        }
    }
}
