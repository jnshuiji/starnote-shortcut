package pochita.hook

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import pochita.hook.canvas.CanvasDragController
import pochita.hook.canvas.CanvasToolController
import pochita.hook.canvas.EraserHoldController
import pochita.hook.canvas.StylusButtonHandler
import pochita.model.ActionType
import java.lang.ref.WeakReference

/**
 * StarNote 画板 NoteScribbleActivity 调度控制器（Facade）
 *
 * 统一聚合各画板子系统控制器：
 * 1. [CanvasToolController]：工具栏识别与切换；
 * 2. [EraserHoldController]：「按切松回」橡皮擦防抖状态机；
 * 3. [CanvasDragController]：画布触控拖动与 EventBus 滚动引擎；
 * 4. [StylusButtonHandler]：手写笔硬件按键状态检测。
 */
object NoteScribbleHook {
    private const val TAG = "StarNoteScribbleHook"
    const val ACTION_COMMAND = "com.pochita.starnote.ACTION_COMMAND"
    const val EXTRA_CMD = "cmd"

    private var activeActivityRef: WeakReference<Activity>? = null

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_COMMAND) return
            val cmd = intent.getStringExtra(EXTRA_CMD) ?: return
            Log.d(TAG, "Received stylus command: $cmd")

            val activity = activeActivityRef?.get()
            if (activity == null || activity.isFinishing || activity.isDestroyed) {
                Log.w(TAG, "No active NoteScribbleActivity to handle command: $cmd")
                return
            }

            activity.runOnUiThread {
                handleCommand(activity, cmd)
            }
        }
    }

    fun onActivityResumed(activity: Activity) {
        if (activeActivityRef?.get() == activity) return
        activeActivityRef = WeakReference(activity)
        clearCaches()
        try {
            val filter = IntentFilter(ACTION_COMMAND)
            ContextCompat.registerReceiver(
                activity,
                commandReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
            Log.i(TAG, "Registered StarNote command receiver")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to register receiver in StarNote", t)
        }
    }

    fun onActivityPaused(activity: Activity) {
        if (activeActivityRef?.get() == activity) {
            activeActivityRef = null
        }
        clearCaches()
        try {
            activity.unregisterReceiver(commandReceiver)
            Log.i(TAG, "Unregistered StarNote command receiver")
        } catch (_: Throwable) {}
    }

    private fun clearCaches() {
        EraserHoldController.clearCaches()
        StylusButtonHandler.clearState()
        CanvasDragController.clearCaches()
        CanvasToolController.clearCaches()
    }

    private fun handleCommand(activity: Activity, cmd: String) {
        when (cmd) {
            "pen" -> CanvasToolController.selectPen(activity)
            "eraser" -> {
                EraserHoldController.clearCaches()
                StylusButtonHandler.clearState()
                CanvasToolController.selectEraser(activity)
            }
            "lasso" -> CanvasToolController.selectLasso(activity)
            "eraser_hold_down" -> EraserHoldController.onEraserHoldDown(activity)
            "eraser_hold_up" -> EraserHoldController.onEraserHoldUp(activity)
            "drag_canvas_down" -> CanvasDragController.onDragCanvasDown(activity)
            "drag_canvas_up" -> CanvasDragController.onDragCanvasUp(activity)
            else -> Log.w(TAG, "Unknown stylus command: $cmd")
        }
    }

    fun executeAction(activity: Activity, action: ActionType, isDown: Boolean) {
        when (action) {
            ActionType.DRAG_CANVAS_HOLD -> {
                if (isDown) CanvasDragController.onDragCanvasDown(activity) else CanvasDragController.onDragCanvasUp(activity)
            }
            ActionType.TOGGLE_ERASER_HOLD -> {
                if (isDown) EraserHoldController.onEraserHoldDown(activity) else EraserHoldController.onEraserHoldUp(activity)
            }
            ActionType.SELECT_PEN -> if (isDown) CanvasToolController.selectPen(activity)
            ActionType.SELECT_ERASER -> if (isDown) {
                EraserHoldController.clearCaches()
                StylusButtonHandler.clearState()
                CanvasToolController.selectEraser(activity)
            }
            ActionType.SELECT_LASSO -> if (isDown) CanvasToolController.selectLasso(activity)
        }
    }

    /**
     * 按键长按重复事件处理
     */
    fun onKeyRepeat(activity: Activity, action: ActionType) {
        if (action == ActionType.TOGGLE_ERASER_HOLD) {
            EraserHoldController.cancelPendingRevert()
        }
    }

    /**
     * 屏幕触控事件拦截与画布拖动分发
     */
    fun onTouchEvent(activity: Activity, event: MotionEvent): Boolean {
        StylusButtonHandler.checkStylusButtonState(activity, event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> EraserHoldController.onPenTouchDown(activity)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> EraserHoldController.onPenTouchUp(activity)
        }
        return CanvasDragController.onTouchEvent(activity, event)
    }

    /**
     * 悬停与通用手势事件监听
     */
    fun onGenericMotionEvent(activity: Activity, event: MotionEvent): Boolean {
        StylusButtonHandler.checkStylusButtonState(activity, event)
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_HOVER_ENTER -> {
                EraserHoldController.onPenHover(activity)
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                EraserHoldController.onPenHoverExit(activity)
            }
        }
        return false
    }

    /**
     * 通用 MotionEvent 监听兜底
     */
    fun onMotionEvent(activity: Activity, event: MotionEvent) {
        StylusButtonHandler.checkStylusButtonState(activity, event)
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_HOVER_ENTER -> {
                EraserHoldController.onPenHover(activity)
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                EraserHoldController.onPenHoverExit(activity)
            }
        }
    }

    /**
     * 捕获 EditorView 与 EventBus 绑定事件
     */
    fun notifyEditorViewAndEventBus(view: View, eventBus: Any) {
        CanvasDragController.notifyEditorViewAndEventBus(view, eventBus)
    }
}
