package pochita.hook.canvas

import android.app.Activity
import android.view.MotionEvent

/**
 * 手写笔实体按键状态检测器
 *
 * 识别硬件按键通过 MotionEvent 携带的按键状态（如手写笔主副侧键被按下），调度橡皮擦临时切换
 */
object StylusButtonHandler {

    fun checkStylusButtonState(activity: Activity, event: MotionEvent) {
        val buttonState = event.buttonState
        val isPrimaryStylusPressed = (buttonState and (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_SECONDARY or 32 or 2)) != 0 ||
                (event.pointerCount > 0 && event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER)

        if (isPrimaryStylusPressed) {
            if (!EraserHoldController.isEraserHolding) {
                EraserHoldController.onEraserHoldDown(activity)
            } else {
                EraserHoldController.cancelPendingRevert()
            }
        }
    }
}
