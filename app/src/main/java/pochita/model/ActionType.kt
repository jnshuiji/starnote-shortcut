package pochita.model

import android.view.KeyEvent

/**
 * 画板快捷键操作类型枚举
 *
 * 交互界面与业务逻辑定义的固定操作项列表：
 * 1. 按切松回（按住切橡皮，松开回画笔）
 * 2. 套索
 * 3. 画笔
 * 4. 橡皮擦
 */
enum class ActionType(
    val id: String,
    val title: String,
    val description: String,
    val defaultKeyCode: Int
) {
    DRAG_CANVAS_HOLD(
        id = "drag_canvas",
        title = "拖动画布",
        description = "按住此键并在画布上移动手写笔可拖动画布",
        defaultKeyCode = KeyEvent.KEYCODE_SPACE
    ),
    TOGGLE_ERASER_HOLD(
        id = "eraser_hold",
        title = "按切松回",
        description = "按住切换为橡皮擦，松开回弹画笔",
        defaultKeyCode = KeyEvent.KEYCODE_B
    ),
    SELECT_LASSO(
        id = "lasso",
        title = "套索",
        description = "一键快速切换至套索圈选工具",
        defaultKeyCode = KeyEvent.KEYCODE_E
    ),
    SELECT_PEN(
        id = "pen",
        title = "画笔",
        description = "切换至普通画笔工具",
        defaultKeyCode = 0
    ),
    SELECT_ERASER(
        id = "eraser",
        title = "橡皮擦",
        description = "切换至橡皮擦工具",
        defaultKeyCode = 0
    );

    companion object {
        fun fromId(id: String): ActionType? {
            return entries.firstOrNull { it.id == id }
        }
    }
}
