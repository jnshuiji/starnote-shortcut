package pochita.hook.canvas

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import java.lang.ref.WeakReference

/**
 * 「按切松回」橡皮擦状态机控制器
 *
 * 1. 按下快捷键切换至橡皮擦并记忆先前的画笔；
 * 2. 结合 50ms 硬件级轻量防抖定时器，确保松键即时回弹画笔且抵御落笔抖动；
 * 3. 避免在非画笔状态（如套索）下误记忆非画笔工具。
 */
object EraserHoldController {
    private const val TAG = "StarNoteEraserCtrl"

    @Volatile
    var isEraserHolding = false
        private set

    private var previousToolViewRef: WeakReference<View>? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingRevertRunnable: Runnable? = null

    fun clearCaches() {
        cancelPendingRevert()
        isEraserHolding = false
        previousToolViewRef = null
    }

    /**
     * 按切松回 - 按下阶段：
     * 1. 记忆当前画笔工具（若当前为套索等非画笔工具，绝不记忆，松开时直接回默认画笔）；
     * 2. 切换至橡皮擦；
     * 3. 标记 isEraserHolding = true，屏蔽后续多余的按键重发；
     * 4. 立即取消任何挂起的延时回退任务（若处于 50ms 防抖窗口内，直接救回并保持橡皮擦）。
     */
    fun onEraserHoldDown(activity: Activity) {
        cancelPendingRevert()
        if (isEraserHolding) {
            return
        }

        previousToolViewRef = null
        val currentPen = CanvasToolController.getCurrentSelectedPenTool(activity)
        if (currentPen != null) {
            previousToolViewRef = WeakReference(currentPen)
            Log.d(TAG, "onEraserHoldDown: memorized previous pen tool ${CanvasToolController.getToolEnumName(currentPen)}")
        }

        isEraserHolding = true
        Log.d(TAG, "onEraserHoldDown: switching to eraser")
        CanvasToolController.selectEraser(activity)
    }

    /**
     * 按切松回 - 松开阶段：
     * 无论笔尖是否在屏幕表面触控，只要收到按键松开指令，通过 50ms 微轻量防抖定时器缓冲：
     * 1. 若 50ms 内驱动因落笔产生瞬态 DOWN 脉冲，立即在 onEraserHoldDown 中取消回退，平滑无缝保持橡皮擦；
     * 2. 若 50ms 内无新按键，判定为真实松开按键，无论此时笔尖是否在屏幕触控，立即切回画笔，达到极致跟手的“松手即回”体验。
     */
    fun onEraserHoldUp(activity: Activity) {
        if (!isEraserHolding) {
            return
        }
        schedulePendingRevert(activity, delayMs = 50L)
    }

    fun cancelPendingRevert() {
        pendingRevertRunnable?.let {
            mainHandler.removeCallbacks(it)
            pendingRevertRunnable = null
        }
    }

    private fun schedulePendingRevert(activity: Activity, delayMs: Long = 50L) {
        cancelPendingRevert()
        val runnable = Runnable {
            if (isEraserHolding) {
                Log.d(TAG, "schedulePendingRevert: executing revert to pen")
                isEraserHolding = false
                val prev = previousToolViewRef?.get()
                previousToolViewRef = null
                if (prev != null && prev.isAttachedToWindow) {
                    if (!CanvasToolController.isToolSelected(prev)) {
                        prev.performClick()
                        Log.d(TAG, "Reverted to previous pen tool: ${CanvasToolController.getToolEnumName(prev)}")
                    }
                } else {
                    CanvasToolController.selectPen(activity)
                    Log.d(TAG, "Reverted to default pen tool")
                }
            }
        }
        pendingRevertRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }
}
