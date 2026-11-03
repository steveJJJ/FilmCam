package com.filmcam.app.ui

import android.app.Dialog
import android.graphics.Rect
import android.view.LayoutInflater
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.filmcam.app.R
import com.filmcam.app.filter.FilmRoll
import com.filmcam.app.filter.FilmRollAdapter
import com.filmcam.app.filter.FilmRollRepository
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlin.math.abs

/**
 * 「换卷」底部弹层。
 *
 * 原先整个弹层（列表 + 长按拖拽排序 + 滚动齿轮触感 + 横屏高度修正）都塞在 MainActivity 里，
 * 而这段跟相机、快门、胶卷状态**一点关系都没有** —— 它只负责「选一支卷、把顺序存下来」。
 * 所以挪出来，MainActivity 只留三个回调：选中、触感、弹窗守卫。
 */
object FilmRollPicker {

    /**
     * @param onPick    选中一支卷（MainActivity 负责真的换卷 + 落盘）
     * @param onVibrate 触感入口 (时长ms, 振幅)，跟机身设置里的强度档保持一致
     * @param guard     弹窗关闭守卫（防止关闭那一下的事件甩给取景器）
     */
    fun show(
        activity: AppCompatActivity,
        onPick: (FilmRoll) -> Unit,
        onVibrate: (Long, Int) -> Unit,
        guard: (Dialog) -> Unit,
    ) {
        val dialog = BottomSheetDialog(activity)
        val view = LayoutInflater.from(activity).inflate(R.layout.bottom_sheet_film_roll, null)
        BodyTheme.applyToViewTree(view)
        dialog.setContentView(view)

        val rv = view.findViewById<RecyclerView>(R.id.rvFilmRolls)
        // RecyclerView 是 wrap_content，25 支胶卷的内容高度远超一屏时弹层会被
        // 容器裁掉底部——最后一两项永远被导航区/屏幕底边盖住。这里给列表
        // 设定明确高度上限（屏高 72%），让它在弹层内部滚动，底部 padding 生效。
        rv.layoutParams?.height = (activity.resources.displayMetrics.heightPixels * 0.72f).toInt()
        dialog.behavior.skipCollapsed = true   // 直接全展开，避免半开状态看不到底部
        rv.layoutManager = LinearLayoutManager(activity)
        val adapter = FilmRollAdapter(FilmRollRepository.rolls) { roll ->
            onVibrate(22, 175)        // 选中一支胶卷也有一记触感，和滑动/长按拖拽保持一致
            onPick(roll)
            dialog.dismiss()
        }
        rv.adapter = adapter

        // 长按拖拽排序：把常用的胶卷拖到列表顶部，方便下次切换。
        // 只开上下方向（dragFlags=UP|DOWN，swipeFlags=0 即不支持滑动删除），
        // 松手时统一落盘一次，避免拖动过程中反复写 SharedPreferences。
        val touchHelper = androidx.recyclerview.widget.ItemTouchHelper(
            object : androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(
                androidx.recyclerview.widget.ItemTouchHelper.UP or
                    androidx.recyclerview.widget.ItemTouchHelper.DOWN, 0
            ) {
                // 长按抓起：给一记稍重的触感，等价于「这一项被拿起来了」
                override fun onSelectedChanged(
                    viewHolder: RecyclerView.ViewHolder?, actionState: Int
                ) {
                    super.onSelectedChanged(viewHolder, actionState)
                    if (actionState == androidx.recyclerview.widget.ItemTouchHelper.ACTION_STATE_DRAG) {
                        onVibrate(34, 200)
                    }
                }

                // 每越过一个相邻位置给一记极轻触感，让「调位置」这件事在手上能被数出来
                override fun onMove(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                    target: RecyclerView.ViewHolder
                ): Boolean {
                    adapter.moveItem(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                    onVibrate(18, 130)
                    return true
                }

                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                    // 不支持滑动
                }

                override fun clearView(
                    recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder
                ) {
                    super.clearView(recyclerView, viewHolder)
                    FilmRollRepository.persist(activity)
                }
            }
        )
        touchHelper.attachToRecyclerView(rv)

        // 滑动选胶卷时的"齿轮感"：列表每滚过一格给一记极轻触感，40ms 节流。
        var tickAccum = 0
        var lastTickMs = 0L
        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                tickAccum += abs(dy)
                val threshold = (recyclerView.height / 8).coerceAtLeast(40)
                val now = android.os.SystemClock.elapsedRealtime()
                if (tickAccum >= threshold && now - lastTickMs > 40) {
                    tickAccum = 0
                    lastTickMs = now
                    onVibrate(12, 95)
                }
            }
        })

        guard(dialog)
        dialog.show()
        // 横屏下 BottomSheet 默认只展开到 peek 高度，25 支胶卷的大半被压在屏幕外，
        // 也看不出"往上还能滑"——直接强制全展开，让滚动发生在列表内部。
        dialog.behavior.state =
            com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED

        // 上面 72% 是按竖屏（屏高 2400 左右）定的，横屏屏高只有一半（1080），
        // 列表 72% + 标题/说明 + 上下 padding 会整体顶出屏幕，滑到最后一卷时底部被切掉。
        // 这里按弹层真实可视高度反推，滑到底一定是完整的。
        view.post {
            val r = Rect()
            dialog.window?.decorView?.getWindowVisibleDisplayFrame(r)
            val maxRv = r.height() - rv.top - view.paddingBottom
            val minRv = (120 * activity.resources.displayMetrics.density).toInt()
            val target = rv.height.coerceAtMost(maxRv).coerceAtLeast(minRv)
            if (target != rv.height) {
                rv.layoutParams.height = target
                rv.requestLayout()
            }
        }
    }
}
