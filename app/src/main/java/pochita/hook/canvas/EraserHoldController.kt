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

    @Volatile
    var isPenTouchingScreen = false
        private set

    @Volatile
    private var isPendingRevertOnPenUp = false

    private var previousToolViewRef: WeakReference<View>? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingRevertRunnable: Runnable? = null

    fun clearCaches() {
        cancelPendingRevert()
        isEraserHolding = false
        isPenTouchingScreen = false
        isPendingRevertOnPenUp = false
        previousToolViewRef = null
    }

    /**
     * 按切松回 - 按下阶段：
     * 1. 记忆当前画笔工具（若当前为套索等非画笔工具，绝不记忆，松开时直接回默认画笔）；
     * 2. 切换至橡皮擦；
     * 3. 标记 isEraserHolding = true，屏蔽后续多余的按键重发；
     * 4. 立即取消任何挂起的延时回退任务。
     */
    fun onEraserHoldDown(activity: Activity) {
        cancelPendingRevert()
        isPendingRevertOnPenUp = false
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
     * 若笔尖仍与屏幕接触擦除中，暂缓执行回退，待提笔时再触发，防止擦除过程中误切回画笔；
     * 若处于悬浮状态，启动 50ms 防抖定时器回退画笔。
     */
    fun onEraserHoldUp(activity: Activity) {
        if (!isEraserHolding) {
            return
        }
        if (isPenTouchingScreen) {
            isPendingRevertOnPenUp = true
            return
        }
        schedulePendingRevert(activity, delayMs = 50L)
    }

    /**
     * 笔尖接触屏幕落笔阶段：
     * 标记笔尖接触中，并立即取消挂起的回退任务，保障擦除过程不被中断。
     */
    fun onPenTouchDown(activity: Activity) {
        isPenTouchingScreen = true
        cancelPendingRevert()
    }

    /**
     * 笔尖抬起离开屏幕阶段：
     * 若按键在落笔期间已松开，则在提笔后立即执行画笔回退。
     */
    fun onPenTouchUp(activity: Activity) {
        isPenTouchingScreen = false
        if (isEraserHolding && isPendingRevertOnPenUp) {
            isPendingRevertOnPenUp = false
            schedulePendingRevert(activity, delayMs = 50L)
        }
    }

    /**
     * 手写笔悬浮离开感应范围阶段：
     * 若处于橡皮擦暂态，在笔尖脱离感应区时立即切回默认画笔。
     */
    fun onPenHoverExit(activity: Activity) {
        isPenTouchingScreen = false
        if (isEraserHolding) {
            isPendingRevertOnPenUp = false
            schedulePendingRevert(activity, delayMs = 0L)
        }
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
                isPendingRevertOnPenUp = false
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
