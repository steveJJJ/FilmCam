package com.filmcam.app.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.provider.Settings
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import com.filmcam.app.R

/**
 * 权限拒绝铭牌：按被拒的权限组给差异化说明，附「去设置」与「知道了」。
 *
 * 视觉与尺寸沿用项目铭牌 dialog 口径：dialogTheme + 78% 屏宽（≤400dp），
 * 且必须先取 decorView 触发 installDecor 再 setLayout，否则 show() 会被
 * PhoneWindow 静默重置成 wrap_content（见 DialogShowJumpTest）。
 * 刻意不做 BodyTheme.applyToViewTree：暗房红 / 暖黄是品牌色，不随机身档位换墨色。
 */
object PermissionNameplate {

    enum class Kind(val titleRes: Int, val bodyRes: Int) {
        CAMERA(R.string.perm_dlg_camera_title, R.string.perm_dlg_camera_body),
        BLUETOOTH(R.string.perm_dlg_bt_title, R.string.perm_dlg_bt_body),
        STORAGE(R.string.perm_dlg_storage_title, R.string.perm_dlg_storage_body),
        NOTIFICATION(R.string.perm_dlg_notif_title, R.string.perm_dlg_notif_body),
    }

    fun show(activity: Activity, kind: Kind) {
        val theme = BodyTheme
        theme.load(activity)
        val view = LayoutInflater.from(activity)
            .inflate(R.layout.dialog_permission_nameplate, null)
        view.findViewById<TextView>(R.id.tvPermTitle).setText(kind.titleRes)
        view.findViewById<TextView>(R.id.tvPermBody).setText(kind.bodyRes)

        val dialog = androidx.appcompat.app.AlertDialog.Builder(activity, theme.dialogTheme)
            .setView(view)
            .create()

        view.findViewById<TextView>(R.id.btnPermDismiss).apply {
            setText(R.string.perm_dlg_dismiss)
            hapticClick(ms = 18, amp = 150) { dialog.dismiss() }
        }
        view.findViewById<TextView>(R.id.btnPermSettings).apply {
            setText(R.string.perm_dlg_go_settings)
            hapticClick(ms = 24, amp = 190) {
                dialog.dismiss()
                val intent = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", activity.packageName, null)
                )
                activity.startActivity(intent)
            }
        }

        val dm = activity.resources.displayMetrics
        val w = (dm.widthPixels * 0.78f).toInt().coerceAtMost((400 * dm.density).toInt())
        val win = dialog.window
        win?.decorView
        win?.setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT)
        win?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        // 个别 ROM 在 addView 阶段可能改写 attrs.width，值相同不产生可见跳变
        win?.setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}
