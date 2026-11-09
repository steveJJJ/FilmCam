package com.filmcam.app.update

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.filmcam.app.R
import com.filmcam.app.ui.BodyTheme
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability

/**
 * v65「检查更新」实现。
 *
 * 全程**零新增权限**：Play 应用内更新走 Play 商店的 IPC；跳转页交给浏览器 / 商店 App。
 * 所有 Play Core 调用都包在 runCatching 里 —— 没有 Play 服务、库初始化失败、
 * Task 失败、更新流程抛异常，一律降级到 [openFallback]，不让按钮点了没反应。
 *
 * 只用 FLEXIBLE：后台下载、下载完由用户确认重启，不强制打断拍摄。
 */
class UpdateChecker(
    private val activity: AppCompatActivity,
    private val launcher: ActivityResultLauncher<IntentSenderRequest>,
) {

    private var manager: AppUpdateManager? = null
    private var listener: InstallStateUpdatedListener? = null

    fun check() {
        val installer = runCatching {
            activity.packageManager.getInstallerPackageName(activity.packageName)
        }.getOrNull()
        val playInstall = UpdateRouting.isPlayInstall(installer)
        toast(R.string.update_checking)

        if (!playInstall) {
            // 侧载内测包：Play 应用内更新无效，直接打开下载页
            openFallback(false)
            return
        }
        val m = runCatching { AppUpdateManagerFactory.create(activity) }.getOrNull()
        if (m == null) {
            openFallback(true)
            return
        }
        manager = m
        val ok = runCatching {
            m.appUpdateInfo
                .addOnSuccessListener { info ->
                    when (
                        UpdateRouting.decide(
                            playInstall = true,
                            updateAvailable = info.updateAvailability() ==
                                UpdateAvailability.UPDATE_AVAILABLE,
                            flexibleAllowed = info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE),
                        )
                    ) {
                        UpdateRouting.Action.FLEXIBLE -> startFlexible(m, info)
                        UpdateRouting.Action.NO_UPDATE -> toast(R.string.update_no_update)
                        UpdateRouting.Action.FALLBACK -> openFallback(true)
                    }
                }
                .addOnFailureListener { openFallback(true) }
            true
        }.getOrDefault(false)
        if (!ok) openFallback(true)
    }

    private fun startFlexible(m: AppUpdateManager, info: AppUpdateInfo) {
        val ok = runCatching {
            ensureListener(m)
            toast(R.string.update_available)
            m.startUpdateFlowForResult(
                info,
                launcher,
                AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build()
            )
            true
        }.getOrDefault(false)
        if (!ok) openFallback(true)
    }

    private fun ensureListener(m: AppUpdateManager) {
        if (listener != null) return
        val l = InstallStateUpdatedListener { state ->
            if (state.installStatus() == InstallStatus.DOWNLOADED) promptRestart(m)
        }
        m.registerListener(l)
        listener = l
    }

    /** 官方要求：下载完成后由用户确认再 completeUpdate()，不要后台静默重启 */
    private fun promptRestart(m: AppUpdateManager) {
        runCatching {
            AlertDialog.Builder(activity, BodyTheme.dialogTheme)
                .setMessage(R.string.update_downloaded)
                .setPositiveButton(R.string.update_restart) { _, _ ->
                    runCatching { m.completeUpdate() }
                }
                .setNegativeButton(R.string.update_later, null)
                .show()
        }
    }

    /** 用户切走又回来：补提示已下载但还没安装的更新 */
    fun onResume() {
        val m = manager ?: return
        runCatching {
            m.appUpdateInfo.addOnSuccessListener { info ->
                if (info.installStatus() == InstallStatus.DOWNLOADED) promptRestart(m)
            }
        }
    }

    /** 监听器持有 Activity，页面销毁必须摘掉 */
    fun onDestroy() {
        val m = manager ?: return
        listener?.let { runCatching { m.unregisterListener(it) } }
        listener = null
    }

    private fun openFallback(playInstall: Boolean) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(UpdateRouting.fallbackUrl(playInstall)))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val ok = runCatching { activity.startActivity(intent) }.isSuccess
        toast(if (ok) R.string.update_open_page else R.string.update_no_browser)
    }

    private fun toast(resId: Int) {
        runCatching {
            Toast.makeText(activity, activity.getString(resId), Toast.LENGTH_SHORT).show()
        }
    }
}
