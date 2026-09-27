package pochita.hook.canvas

import android.app.Activity
import android.view.MotionEvent

/**
 * 手写笔实体按键状态检测器
 *
 * 监听数位笔硬件通过 MotionEvent 携带的主侧键按压状态，调度「按切松回」橡皮擦状态机。
 * 仅识别物理主侧键（BUTTON_STYLUS_PRIMARY），严格隔离副按键及工具类型切换。
 */
object StylusButtonHandler {

    @Volatile
    private var wasPrimaryButtonPressed = false

    /**
     * 重置按键检测状态
     */
    fun clearState() {
        wasPrimaryButtonPressed = false
    }

    /**
     * 检测 MotionEvent 中的手写笔主键按压与释放事件
     */
    fun checkStylusButtonState(activity: Activity, event: MotionEvent) {
        val buttonState = event.buttonState
        // 仅匹配手写笔硬件主侧键掩码（BUTTON_STYLUS_PRIMARY / 32）
        val isPrimaryStylusPressed = (buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0

        val isExiting = event.actionMasked == MotionEvent.ACTION_HOVER_EXIT ||
                event.actionMasked == MotionEvent.ACTION_CANCEL

        if (isPrimaryStylusPressed && !isExiting) {
            if (!wasPrimaryButtonPressed) {
                wasPrimaryButtonPressed = true
                EraserHoldController.onEraserHoldDown(activity)
            } else {
                EraserHoldController.cancelPendingRevert()
            }
        } else {
            if (wasPrimaryButtonPressed) {
                wasPrimaryButtonPressed = false
                EraserHoldController.onEraserHoldUp(activity)
            }
        }
    }
}
